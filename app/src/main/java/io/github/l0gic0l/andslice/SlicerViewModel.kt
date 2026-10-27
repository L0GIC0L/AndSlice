package io.github.l0gic0l.andslice

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.andslice.engine.NativeEngine
import io.github.l0gic0l.andslice.engine.OptionDefs
import io.github.l0gic0l.andslice.engine.PlateObject
import io.github.l0gic0l.andslice.engine.PresetLists
import io.github.l0gic0l.andslice.engine.PresetType
import io.github.l0gic0l.andslice.engine.PreviewInfo
import io.github.l0gic0l.andslice.engine.SliceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ViewMode { Plate, Preview }

class SlicerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("andslice", Context.MODE_PRIVATE)

    var initError by mutableStateOf<String?>(null); private set
    var ready by mutableStateOf(false); private set

    var presets by mutableStateOf<PresetLists?>(null); private set
    var printer by mutableStateOf(""); private set
    var print by mutableStateOf(""); private set
    var filament by mutableStateOf(""); private set
    /** Values of the selected presets, before overrides. */
    var config by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** User changes on top of the presets (key -> PrusaSlicer serialized value). */
    var overrides by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var optionDefs by mutableStateOf<OptionDefs?>(null); private set

    var objects by mutableStateOf<List<PlateObject>>(emptyList()); private set
    var selectedId by mutableStateOf(-1L); private set
    var busyMessage by mutableStateOf<String?>(null); private set

    var slicing by mutableStateOf(false); private set
    var sliceProgress by mutableIntStateOf(0); private set
    var sliceStatus by mutableStateOf(""); private set
    var result by mutableStateOf<SliceResult?>(null); private set
    /** True when the plate or settings changed after the last slice. */
    var resultStale by mutableStateOf(false); private set

    var mode by mutableStateOf(ViewMode.Plate); private set
    var previewInfo by mutableStateOf(PreviewInfo.EMPTY); private set
    var layerRange by mutableStateOf(0..0); private set
    var moveFraction by mutableStateOf(1f); private set
    var viewType by mutableIntStateOf(0); private set
    var showTravel by mutableStateOf(false); private set
    var layFaceMode by mutableStateOf(false); private set

    /** Bumped whenever the scene needs a redraw. */
    var renderTick by mutableIntStateOf(0); private set

    var message by mutableStateOf<String?>(null)

    val selectedObject: PlateObject? get() = objects.firstOrNull { it.id == selectedId }

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { initEngine(app) }
                ready = true
            } catch (t: Throwable) {
                initError = t.message ?: t.toString()
            }
        }
    }

    // ------------------------------------------------------------------ setup

    private fun initEngine(app: Application) {
        val profiles = File(app.filesDir, "profiles").apply { mkdirs() }
        val bundle = File(profiles, BUNDLE_ASSET.substringAfterLast('/'))
        app.assets.open(BUNDLE_ASSET).use { input -> bundle.outputStream().use { input.copyTo(it) } }
        val dataDir = File(app.filesDir, "prusaslicer").apply { mkdirs() }
        val resources = File(app.filesDir, "resources").apply { mkdirs() }
        NativeEngine.init(dataDir.absolutePath, resources.absolutePath, bundle.absolutePath)

        val defs = OptionDefs.parse(NativeEngine.optionDefsJson())
        val savedOverrides = JSONObject(prefs.getString(KEY_OVERRIDES, "{}") ?: "{}")
        val restored = savedOverrides.keys().asSequence().associateWith { savedOverrides.getString(it) }

        val savedPrinter = prefs.getString(KEY_PRINTER, "") ?: ""
        var lists = PresetLists.parse(NativeEngine.presetsJson(savedPrinter))
        if (savedPrinter.isNotEmpty() && savedPrinter !in lists.printers) {
            lists = PresetLists.parse(NativeEngine.presetsJson(""))
        }
        val p = lists.printer
        val pr = prefs.getString(KEY_PRINT, "")?.takeIf { it in lists.prints } ?: lists.defaultPrint.ifEmpty { lists.prints.first() }
        val fi = prefs.getString(KEY_FILAMENT, "")?.takeIf { it in lists.filaments } ?: lists.defaultFilament.ifEmpty { lists.filaments.first() }
        NativeEngine.setPrinter(p)
        val cfg = loadConfig(p, pr, fi)

        viewModelScope.launch {
            optionDefs = defs
            presets = lists
            printer = p
            print = pr
            filament = fi
            config = cfg
            overrides = restored
            updateArrangeDistance()
            redraw()
        }
    }

    private fun loadConfig(printer: String, print: String, filament: String): Map<String, String> {
        val o = JSONObject(NativeEngine.configJson(printer, print, filament))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun savePresetSelection() {
        prefs.edit()
            .putString(KEY_PRINTER, printer)
            .putString(KEY_PRINT, print)
            .putString(KEY_FILAMENT, filament)
            .putString(KEY_OVERRIDES, JSONObject(overrides).toString())
            .apply()
    }

    fun redraw() {
        renderTick++
    }

    // ------------------------------------------------------------------ presets

    fun selectPrinter(name: String) = launchEngine {
        val lists = PresetLists.parse(NativeEngine.presetsJson(name))
        NativeEngine.setPrinter(name)
        val newPrint = print.takeIf { it in lists.prints } ?: lists.defaultPrint.ifEmpty { lists.prints.first() }
        val newFilament = filament.takeIf { it in lists.filaments } ?: lists.defaultFilament.ifEmpty { lists.filaments.first() }
        val cfg = loadConfig(name, newPrint, newFilament)
        val objs = PlateObject.parseList(NativeEngine.objectsJson())
        withContext(Dispatchers.Main) {
            presets = lists
            printer = name
            print = newPrint
            filament = newFilament
            config = cfg
            objects = objs
            dropOverrides(PresetType.Printer)
            settingsChanged()
        }
    }

    fun selectPrint(name: String) = changePresets(print = name, reset = PresetType.Print)
    fun selectFilament(name: String) = changePresets(filament = name, reset = PresetType.Filament)

    private fun changePresets(print: String = this.print, filament: String = this.filament, reset: PresetType) = launchEngine {
        val cfg = loadConfig(printer, print, filament)
        withContext(Dispatchers.Main) {
            this@SlicerViewModel.print = print
            this@SlicerViewModel.filament = filament
            config = cfg
            dropOverrides(reset)
            settingsChanged()
        }
    }

    /** Overrides belong to a preset type; picking another preset of that type discards them. */
    private fun dropOverrides(type: PresetType) {
        val defs = optionDefs ?: return
        val kept = overrides.filterKeys { defs.typeOf(it) != type }
        if (kept.size != overrides.size) {
            overrides = kept
            message = "Your ${type.name.lowercase()} setting changes were reset for the new profile."
        }
    }

    fun value(key: String): String? = overrides[key] ?: config[key]

    fun setOverride(key: String, value: String) {
        overrides = if (config[key] == value) overrides - key else overrides + (key to value)
        settingsChanged()
    }

    fun resetOverride(key: String) {
        overrides = overrides - key
        settingsChanged()
    }

    fun resetAllOverrides() {
        overrides = emptyMap()
        settingsChanged()
    }

    private fun settingsChanged() {
        savePresetSelection()
        updateArrangeDistance()
        if (result != null) resultStale = true
    }

    /** Keeps brims of neighbouring objects from touching when arranging. */
    private fun updateArrangeDistance() {
        val brim = value("brim_width")?.toFloatOrNull() ?: 0f
        NativeEngine.setArrangeDistance(6f + 2f * brim)
    }

    // ------------------------------------------------------------------ plate

    fun importModels(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchEngine("Loading model…") {
            var lastIds: List<Long> = emptyList()
            for (uri in uris) {
                val name = displayName(context, uri)
                val dir = File(context.cacheDir, "models/${UUID.randomUUID()}").apply { mkdirs() }
                val file = File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("Cannot open $name")
                val json = JSONObject(NativeEngine.addModel(file.absolutePath, name.substringBeforeLast('.')))
                val ids = json.getJSONArray("ids")
                lastIds = (0 until ids.length()).map { ids.getLong(it) }
            }
            refreshObjects()
            lastIds.lastOrNull()?.let { selectOnEngine(it) }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val base = (name ?: uri.lastPathSegment ?: "model").substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext in MODEL_EXTENSIONS) return base
        // Some file managers drop the extension; fall back on the MIME type.
        val mime = context.contentResolver.getType(uri) ?: ""
        val guessed = when {
            "3mf" in mime || "3dmanufacturing" in mime -> "3mf"
            "obj" in mime -> "obj"
            else -> "stl"
        }
        return "$base.$guessed"
    }

    private suspend fun refreshObjects() {
        val objs = PlateObject.parseList(NativeEngine.objectsJson())
        withContext(Dispatchers.Main) {
            objects = objs
            if (objs.none { it.id == selectedId }) selectedId = -1
            if (result != null) resultStale = true
            redraw()
        }
    }

    private suspend fun selectOnEngine(id: Long) {
        NativeEngine.select(id)
        withContext(Dispatchers.Main) {
            selectedId = id
            redraw()
        }
    }

    fun select(id: Long) {
        if (mode == ViewMode.Preview) return
        selectedId = id
        NativeEngine.select(id)
        redraw()
    }

    fun onTap(id: Long, origin: FloatArray, dir: FloatArray) {
        if (layFaceMode && selectedId >= 0) {
            val target = selectedId
            layFaceMode = false
            launchEngine {
                if (!NativeEngine.layOnFace(target, origin, dir)) {
                    withContext(Dispatchers.Main) { message = "Tap a face of the selected object." }
                }
                refreshObjects()
            }
            return
        }
        select(id)
    }

    fun toggleLayFaceMode() {
        layFaceMode = !layFaceMode && selectedId >= 0
        if (layFaceMode) message = "Tap the face that should rest on the bed."
    }

    /** Live update while dragging; the object list is refreshed when the finger lifts. */
    fun dragObject(id: Long, x: Double, y: Double, done: Boolean) {
        val obj = objects.firstOrNull { it.id == id } ?: return
        NativeEngine.setTransform(id, obj.transform(x = x, y = y))
        if (done) launchEngine { refreshObjects() }
        redraw()
    }

    fun setTransform(id: Long, t: DoubleArray) = launchEngine {
        NativeEngine.setTransform(id, t)
        refreshObjects()
    }

    fun rotateSelected(axis: Int, degrees: Double) {
        val obj = selectedObject ?: return
        val r = obj.rotation.toMutableList()
        r[axis] = (r[axis] + degrees) % 360.0
        setTransform(obj.id, obj.transform(rx = r[0], ry = r[1], rz = r[2]))
    }

    fun scaleSelected(percent: Double) {
        val obj = selectedObject ?: return
        val s = (percent / 100.0).coerceIn(0.01, 100.0)
        setTransform(obj.id, obj.transform(sx = s, sy = s, sz = s))
    }

    fun deleteSelected() {
        val id = selectedId.takeIf { it >= 0 } ?: return
        launchEngine {
            NativeEngine.removeObject(id)
            refreshObjects()
        }
    }

    fun duplicateSelected() {
        val id = selectedId.takeIf { it >= 0 } ?: return
        launchEngine {
            val newId = NativeEngine.duplicateObject(id)
            refreshObjects()
            if (newId > 0) selectOnEngine(newId)
        }
    }

    fun centerSelected() {
        val id = selectedId.takeIf { it >= 0 } ?: return
        launchEngine {
            NativeEngine.centerObject(id)
            refreshObjects()
        }
    }

    fun arrange() = launchEngine("Arranging…") {
        NativeEngine.arrange()
        refreshObjects()
    }

    fun clearPlate() = launchEngine {
        NativeEngine.clearPlate()
        NativeEngine.clearPreview()
        withContext(Dispatchers.Main) {
            result = null
            mode = ViewMode.Plate
        }
        refreshObjects()
    }

    // ------------------------------------------------------------------ slicing

    fun slice(context: Context) {
        if (slicing) return
        slicing = true
        sliceProgress = 0
        sliceStatus = "Preparing"
        layFaceMode = false
        val outDir = File(context.cacheDir, "gcode").apply {
            deleteRecursively()
            mkdirs()
        }
        val keys = overrides.keys.toTypedArray()
        val values = keys.map { overrides.getValue(it) }.toTypedArray()
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                SliceResult.parse(
                    NativeEngine.slice(printer, print, filament, keys, values, outDir.absolutePath + "/") { percent, text ->
                        viewModelScope.launch(Dispatchers.Main) {
                            sliceProgress = percent
                            sliceStatus = text
                        }
                    }
                )
            }
            slicing = false
            if (r.ok) {
                result = r
                resultStale = false
                mode = ViewMode.Preview
                layerRange = 0..(r.layerZs.size - 1).coerceAtLeast(0)
                moveFraction = 1f
                if (r.warnings.isNotEmpty()) message = r.warnings.joinToString("\n")
            } else if (!r.canceled) {
                message = r.error ?: "Slicing failed."
            }
            redraw()
        }
    }

    fun cancelSlice() = NativeEngine.cancelSlice()

    fun showPlate() {
        mode = ViewMode.Plate
        redraw()
    }

    fun showPreview() {
        if (result != null) {
            mode = ViewMode.Preview
            redraw()
        }
    }

    fun changeLayerRange(range: IntRange) {
        layerRange = range
        redraw()
    }

    fun changeMoveFraction(f: Float) {
        moveFraction = f
        redraw()
    }

    fun changeViewType(type: Int) {
        viewType = type
        redraw()
    }

    fun changeShowTravel(show: Boolean) {
        showTravel = show
        redraw()
    }

    fun onPreviewInfo(info: PreviewInfo) {
        previewInfo = info
    }

    // ------------------------------------------------------------------ helpers

    private fun launchEngine(busy: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy != null) busyMessage = busy
            try {
                withContext(Dispatchers.Default) { block() }
            } catch (t: Throwable) {
                message = t.message ?: t.toString()
            } finally {
                if (busy != null) busyMessage = null
            }
        }
    }

    companion object {
        const val BUNDLE_ASSET = "profiles/AnycubicKobra3.ini"
        val MODEL_EXTENSIONS = setOf("stl", "3mf", "obj", "amf")
        private const val KEY_PRINTER = "printer"
        private const val KEY_PRINT = "print"
        private const val KEY_FILAMENT = "filament"
        private const val KEY_OVERRIDES = "overrides"
    }
}

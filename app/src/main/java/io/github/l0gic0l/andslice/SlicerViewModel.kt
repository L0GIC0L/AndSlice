package io.github.l0gic0l.andslice

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.andslice.engine.NativeEngine
import io.github.l0gic0l.andslice.engine.OptionDefs
import io.github.l0gic0l.andslice.engine.PlateObject
import io.github.l0gic0l.andslice.engine.PresetLists
import io.github.l0gic0l.andslice.engine.PresetType
import io.github.l0gic0l.andslice.engine.PreviewInfo
import io.github.l0gic0l.andslice.engine.SliceResult
import io.github.l0gic0l.andslice.engine.VIEW_TYPE_TOOL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ViewMode { Plate, Preview }

class SlicerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("andslice", Context.MODE_PRIVATE)
    /** Completed once the native engine has loaded the profiles; engine calls wait for it. */
    private val engineReady = CompletableDeferred<Unit>()

    var initError by mutableStateOf<String?>(null); private set
    var ready by mutableStateOf(false); private set

    var presets by mutableStateOf<PresetLists?>(null); private set
    var printer by mutableStateOf(""); private set
    var print by mutableStateOf(""); private set
    /** Filament preset of each extruder; the Kobra 3 Combo has one per ACE slot. */
    var filaments by mutableStateOf<List<String>>(emptyList()); private set
    /** Color of each filament slot, as #RRGGBB. */
    var slotColors by mutableStateOf<List<String>>(emptyList()); private set
    /** Wipe tower position (front left corner) on the bed, in mm. */
    var wipeTowerX by mutableFloatStateOf(0f); private set
    var wipeTowerY by mutableFloatStateOf(0f); private set
    /** Values of the selected presets, before overrides. */
    var config by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** User changes on top of the presets (key -> PrusaSlicer serialized value). */
    var overrides by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var optionDefs by mutableStateOf<OptionDefs?>(null); private set

    var objects by mutableStateOf<List<PlateObject>>(emptyList()); private set
    var selectedId by mutableLongStateOf(-1L); private set
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
    var moveFraction by mutableFloatStateOf(1f); private set
    var viewType by mutableIntStateOf(0); private set
    var showTravel by mutableStateOf(false); private set
    var layFaceMode by mutableStateOf(false); private set

    /** Bumped whenever the scene needs a redraw. */
    var renderTick by mutableIntStateOf(0); private set

    var message by mutableStateOf<String?>(null)

    val selectedObject: PlateObject? get() = objects.firstOrNull { it.id == selectedId }
    /** Filament of the first slot, which single-extruder printers use. */
    val filament: String get() = filaments.firstOrNull() ?: ""
    val extruderCount: Int get() = presets?.extruders ?: 1
    val multiMaterial: Boolean get() = extruderCount > 1

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { initEngine(app) }
                ready = true
                engineReady.complete(Unit)
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
        val savedFilaments = prefs.getString(KEY_FILAMENTS, null)?.let { JSONArray(it).strings() }
            ?: listOfNotNull(prefs.getString(KEY_FILAMENT, null))
        val fi = fitSlots(savedFilaments.filter { it in lists.filaments }, lists.extruders,
                          lists.defaultFilament.ifEmpty { lists.filaments.first() })
        val savedColors = prefs.getString(KEY_COLORS, null)?.let { JSONArray(it).strings() } ?: emptyList()
        NativeEngine.setPrinter(p)
        val cfg = loadConfig(p, pr, fi)
        val colors = slotColorsFor(lists.extruders, savedColors, cfg)

        viewModelScope.launch {
            optionDefs = defs
            presets = lists
            printer = p
            print = pr
            filaments = fi
            slotColors = colors
            config = cfg
            overrides = restored
            resetWipeTowerPosition()
            updateArrangeDistance()
            syncSlots()
            redraw()
        }
    }

    private fun loadConfig(printer: String, print: String, filaments: List<String>): Map<String, String> {
        val o = JSONObject(NativeEngine.configJson(printer, print, filaments.toTypedArray()))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** One filament per extruder: keeps the chosen ones and repeats the last for new slots. */
    private fun fitSlots(chosen: List<String>, count: Int, fallback: String): List<String> =
        List(count) { i -> chosen.getOrNull(i) ?: chosen.lastOrNull() ?: fallback }

    /**
     * Slot colors: the saved ones, then distinct defaults for multi-material printers, or the
     * filament preset's color for a single extruder.
     */
    private fun slotColorsFor(count: Int, saved: List<String>, cfg: Map<String, String>): List<String> {
        val presetColor = cfg["filament_colour"]?.split(';')?.firstOrNull()?.trim('"')?.takeIf { it.startsWith("#") }
        return List(count) { i ->
            saved.getOrNull(i) ?: if (count == 1) presetColor ?: DEFAULT_SLOT_COLORS[0] else DEFAULT_SLOT_COLORS[i % DEFAULT_SLOT_COLORS.size]
        }
    }

    private fun savePresetSelection() {
        prefs.edit {
            putString(KEY_PRINTER, printer)
            putString(KEY_PRINT, print)
            putString(KEY_FILAMENTS, JSONArray(filaments).toString())
            putString(KEY_COLORS, JSONArray(slotColors).toString())
            putString(KEY_OVERRIDES, JSONObject(overrides).toString())
        }
    }

    fun redraw() {
        renderTick++
    }

    // ------------------------------------------------------------------ presets

    fun selectPrinter(name: String) = launchEngine {
        val lists = PresetLists.parse(NativeEngine.presetsJson(name))
        NativeEngine.setPrinter(name)
        val newPrint = print.takeIf { it in lists.prints } ?: lists.defaultPrint.ifEmpty { lists.prints.first() }
        val newFilaments = fitSlots(filaments.filter { it in lists.filaments }, lists.extruders,
                                    lists.defaultFilament.ifEmpty { lists.filaments.first() })
        val cfg = loadConfig(name, newPrint, newFilaments)
        val colors = slotColorsFor(lists.extruders, if (lists.extruders == extruderCount) slotColors else emptyList(), cfg)
        val objs = PlateObject.parseList(NativeEngine.objectsJson())
        withContext(Dispatchers.Main) {
            val bedChanged = config["bed_shape"] != cfg["bed_shape"]
            presets = lists
            printer = name
            print = newPrint
            filaments = newFilaments
            slotColors = colors
            config = cfg
            objects = objs
            if (bedChanged) resetWipeTowerPosition()
            dropOverrides(PresetType.Printer)
            settingsChanged()
        }
    }

    fun selectPrint(name: String) = changePresets(print = name, reset = PresetType.Print)

    /** Picks the filament preset of one slot (0-based). */
    fun selectFilament(slot: Int, name: String) =
        changePresets(filaments = filaments.toMutableList().also { if (slot in it.indices) it[slot] = name }, reset = PresetType.Filament)

    fun setSlotColor(slot: Int, color: String) {
        if (slot !in slotColors.indices) return
        slotColors = slotColors.toMutableList().also { it[slot] = color }
        settingsChanged()
        syncSlots()
    }

    private fun changePresets(print: String = this.print, filaments: List<String> = this.filaments, reset: PresetType) = launchEngine {
        val cfg = loadConfig(printer, print, filaments)
        withContext(Dispatchers.Main) {
            this@SlicerViewModel.print = print
            this@SlicerViewModel.filaments = filaments
            config = cfg
            dropOverrides(reset)
            settingsChanged()
        }
    }

    /**
     * Overrides belong to a preset type; picking another preset of that type discards them,
     * except the quick settings, which are choices about the model rather than the profile.
     */
    private fun dropOverrides(type: PresetType) {
        val defs = optionDefs ?: return
        val kept = overrides.filterKeys { it in QUICK_SETTINGS || defs.typeOf(it) != type }
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
        syncSlots()
        if (result != null) resultStale = true
    }

    // ------------------------------------------------------------------ filament slots

    /** Extruders the plate uses; painted objects may use any of them. */
    val usedExtruders: Set<Int>
        get() = if (objects.any { it.painted }) (1..extruderCount).toSet()
                else objects.flatMap { it.extruders }.map { it.coerceIn(1, extruderCount) }.toSet()

    /** PrusaSlicer prints a wipe tower when it is enabled and the plate uses more than one filament. */
    val wipeTowerVisible: Boolean
        get() = multiMaterial && value("wipe_tower") == "1" && usedExtruders.size > 1

    /** Wipe tower {x, y, width, depth, brim, height} in mm. */
    private fun wipeTowerShape(): FloatArray {
        val width = value("wipe_tower_width")?.toFloatOrNull() ?: 60f
        val brim = value("wipe_tower_brim_width")?.toFloatOrNull() ?: 2f
        // Like PrusaSlicer's estimate before slicing: the purge volume spread over 0.2 mm layers.
        val purge = (value("multimaterial_purging")?.toFloatOrNull() ?: 140f).coerceAtLeast(15f)
        val estimate = usedExtruders.size * purge / 0.2f / width.coerceAtLeast(1f)
        // The last slice knows the real depth, which beats the estimate even after small changes.
        val depth = result?.wipeTowerDepth?.takeIf { it > 0 }?.toFloat() ?: estimate
        val height = objects.maxOfOrNull { it.size[2] }?.toFloat() ?: 0f
        return floatArrayOf(wipeTowerX, wipeTowerY, width, depth, brim, height)
    }

    /** Box of the wipe tower for touch handling: {x, y, width, depth, height}. */
    fun wipeTowerFootprint(): FloatArray = wipeTowerShape().let { floatArrayOf(it[0], it[1], it[2], it[3], it[5]) }

    /** Sends the wipe tower to the engine. */
    private fun syncSlots() {
        if (presets == null) return
        val visible = wipeTowerVisible
        val tower = wipeTowerShape()
        launchEngine {
            NativeEngine.setWipeTower(visible, tower)
            withContext(Dispatchers.Main) { redraw() }
        }
    }

    /** Puts the wipe tower in the back right corner of the bed. */
    private fun resetWipeTowerPosition() {
        val pts = config["bed_shape"]?.split(',')?.mapNotNull { p ->
            p.split('x').takeIf { it.size == 2 }?.let { (x, y) -> x.toFloatOrNull()?.let { fx -> y.toFloatOrNull()?.let { fx to it } } }
        }.orEmpty()
        val maxX = pts.maxOfOrNull { it.first } ?: 255f
        val maxY = pts.maxOfOrNull { it.second } ?: 255f
        val width = value("wipe_tower_width")?.toFloatOrNull() ?: 60f
        wipeTowerX = maxX - width - 20f
        wipeTowerY = maxY - 40f
    }

    /** Assigns a filament slot (1-based) to the whole object, or to one part when [part] >= 0. */
    fun setObjectExtruder(id: Long, part: Int, extruder: Int) = launchEngine {
        NativeEngine.setExtruder(id, part, extruder)
        refreshObjects()
    }

    /** Live update while dragging the wipe tower. */
    fun dragWipeTower(x: Float, y: Float, done: Boolean) {
        wipeTowerX = x
        wipeTowerY = y
        if (done && result != null) resultStale = true
        syncSlots()
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
        // Placing new objects and arranging move the wipe tower along with them.
        val tower = NativeEngine.wipeTowerPosition()
        withContext(Dispatchers.Main) {
            objects = objs
            if (wipeTowerVisible) {
                wipeTowerX = tower[0]
                wipeTowerY = tower[1]
            }
            if (objs.none { it.id == selectedId }) selectedId = -1
            if (result != null) resultStale = true
            syncSlots()
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
        // Slot colors go to PrusaSlicer as the filament colors (preview and G-code metadata).
        val settings = overrides + ("filament_colour" to slotColors.joinToString(";"))
        val keys = settings.keys.toTypedArray()
        val values = keys.map { settings.getValue(it) }.toTypedArray()
        val filamentNames = filaments.toTypedArray()
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                SliceResult.parse(
                    NativeEngine.slice(printer, print, filamentNames, keys, values, outDir.absolutePath + "/") { percent, text ->
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
                // Multi-color prints open in the filament view, single-color ones keep the chosen view.
                if (r.extrudersUsed.size > 1 && viewType == 0) viewType = VIEW_TYPE_TOOL
                syncSlots()
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
        // The moves slider works on the top layer; start a new top layer at its end, as PrusaSlicer does.
        if (range.last != layerRange.last) moveFraction = 1f
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
                engineReady.await()
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
        /** Keys behind the quick settings bar, kept when the print profile changes. */
        val QUICK_SETTINGS = setOf("support_material", "support_material_buildplate_only", "fill_density", "brim_width")
        private const val KEY_PRINTER = "printer"
        private const val KEY_PRINT = "print"
        private const val KEY_FILAMENT = "filament"
        private const val KEY_FILAMENTS = "filaments"
        private const val KEY_COLORS = "slot_colors"
        /** Starting colors of the ACE slots until the user picks the loaded ones. */
        val DEFAULT_SLOT_COLORS = listOf("#F2F2F2", "#2B2B2B", "#D32F2F", "#1E88E5")
        private const val KEY_OVERRIDES = "overrides"
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

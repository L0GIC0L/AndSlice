package io.github.l0gic0l.andslice

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import io.github.l0gic0l.andslice.engine.PaintBrush
import io.github.l0gic0l.andslice.engine.PaintMode
import io.github.l0gic0l.andslice.engine.PaintState
import io.github.l0gic0l.andslice.engine.PlateObject
import io.github.l0gic0l.andslice.engine.PresetLists
import io.github.l0gic0l.andslice.engine.PresetType
import io.github.l0gic0l.andslice.engine.PreviewInfo
import io.github.l0gic0l.andslice.engine.SliceResult
import io.github.l0gic0l.andslice.engine.VIEW_TYPE_TOOL
import io.github.l0gic0l.andslice.printers.PrinterStore
import io.github.l0gic0l.andslice.printers.SavedPrinter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ViewMode { Plate, Preview }

/** Files that wait for the user's choice before they go on the plate. */
sealed interface PendingImport {
    /** Models unpacked from zip archives; the user picks the ones to add. */
    data class Archive(val models: List<ModelFiles.Unpacked>) : PendingImport
    /** A project, while other objects are on the plate: open it in their place, or add its objects. */
    data class Project(val file: File) : PendingImport
}

class SlicerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("andslice", Context.MODE_PRIVATE)
    /** Bundles loaded in the engine, as asset paths; set on the engine's thread during start-up. */
    @Volatile private var loadedBundles: List<String> = BASE_BUNDLES
    /** Completed once the native engine has loaded the profiles; engine calls wait for it. */
    private val engineReady = CompletableDeferred<Unit>()
    /** The plate of the last session, saved a moment after every change. */
    private val autosaveFile = File(app.filesDir, "plate/plate.3mf")
    private var autosaveJob: Job? = null
    /** Project files are written one at a time. */
    private val projectThread = Dispatchers.IO.limitedParallelism(1)

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
        // Built-in bundles, then the vendor bundles of the saved printers.
        loadedBundles = (BASE_BUNDLES + PrinterStore.bundles(app)).distinct()
        // Imported files are copied here; the engine keeps what it needs in memory.
        File(app.cacheDir, "models").deleteRecursively()
        val dataDir = File(app.filesDir, "prusaslicer").apply { mkdirs() }
        val resources = File(app.filesDir, "resources").apply { mkdirs() }
        NativeEngine.init(dataDir.absolutePath, resources.absolutePath, bundleFiles(loadedBundles))

        val defs = OptionDefs.parse(NativeEngine.optionDefsJson())
        val savedOverrides = JSONObject(prefs.getString(KEY_OVERRIDES, "{}") ?: "{}")
        val restored = savedOverrides.keys().asSequence().associateWith { savedOverrides.getString(it) }

        // The active saved printer decides the printer profile.
        val store = PrinterStore.load(app)
        val savedPrinter = store.printers.firstOrNull { it.id == store.activeId }?.preset ?: ""
        var lists = PresetLists.parse(NativeEngine.presetsJson(savedPrinter))
        if (savedPrinter.isNotEmpty() && savedPrinter !in lists.printers) {
            lists = PresetLists.parse(NativeEngine.presetsJson(""))
        }
        val p = lists.printer
        val pr = prefs.getString(KEY_PRINT, "")?.takeIf { it in lists.prints } ?: lists.defaultPrint.ifEmpty { lists.prints.first() }
        val savedFilaments = prefs.getString(KEY_FILAMENTS, null)?.let { JSONArray(it).strings() }
            ?: listOfNotNull(prefs.getString(KEY_FILAMENT, null))
        val fi = fitSlots(savedFilaments.filter { it in lists.allFilaments }, lists.extruders, defaultFilament(lists))
        val savedColors = prefs.getString(KEY_COLORS, null)?.let { JSONArray(it).strings() } ?: emptyList()
        NativeEngine.setPrinter(p)
        val cfg = loadConfig(p, pr, fi)
        val colors = slotColorsFor(lists.extruders, savedColors, cfg)
        val plate = restorePlate()
        val tower = NativeEngine.wipeTowerPosition()

        viewModelScope.launch {
            optionDefs = defs
            presets = lists
            printer = p
            print = pr
            filaments = fi
            slotColors = colors
            config = cfg
            overrides = restored
            objects = plate
            resetWipeTowerPosition()
            // The plate of the last session has its wipe tower where it was left.
            if (plate.isNotEmpty() && (tower[0] != 0f || tower[1] != 0f)) {
                wipeTowerX = tower[0]
                wipeTowerY = tower[1]
            }
            updateArrangeDistance()
            syncSlots()
            redraw()
        }
    }

    /** Copies bundles from the assets to files the engine can read; returns their paths. */
    private fun bundleFiles(bundles: List<String>): Array<String> = bundles.map { asset ->
        val file = File(getApplication<Application>().filesDir, asset)
        file.parentFile?.mkdirs()
        getApplication<Application>().assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        file.absolutePath
    }.toTypedArray()

    private fun defaultFilament(lists: PresetLists) =
        lists.defaultFilament.takeIf { it in lists.allFilaments } ?: lists.allFilaments.firstOrNull() ?: ""

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

    fun selectPrinter(name: String) = launchEngine { selectPrinterNow(name) }

    private suspend fun selectPrinterNow(name: String) {
        val lists = PresetLists.parse(NativeEngine.presetsJson(name))
        NativeEngine.setPrinter(name)
        val newPrint = print.takeIf { it in lists.prints } ?: lists.defaultPrint.ifEmpty { lists.prints.first() }
        val newFilaments = fitSlots(filaments.filter { it in lists.allFilaments }, lists.extruders, defaultFilament(lists))
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

    // ------------------------------------------------------------------ printers and profiles

    /** Loads a vendor bundle into the engine unless it is loaded already (this can take a few seconds). */
    private suspend fun ensureBundle(bundle: String) {
        if (bundle.isEmpty() || bundle in loadedBundles) return
        withContext(Dispatchers.Main) { busyMessage = "Loading printer profiles…" }
        try {
            val bundles = loadedBundles + bundle
            NativeEngine.loadProfiles(bundleFiles(bundles))
            loadedBundles = bundles
        } finally {
            withContext(Dispatchers.Main) { busyMessage = null }
        }
    }

    /** Slices for [printer]: loads its vendor profiles when needed and selects its preset. */
    fun useSavedPrinter(printer: SavedPrinter) = launchEngine {
        ensureBundle(printer.bundle)
        if (printer.preset != this.printer || presets?.printer != printer.preset) selectPrinterNow(printer.preset)
    }

    /** The printer preset of a vendor's model and nozzle, loading the vendor's profiles first. */
    suspend fun findPrinterPreset(bundle: String, model: String, variant: String): String {
        engineReady.await()
        return withContext(Dispatchers.Default) {
            ensureBundle(bundle)
            NativeEngine.printerForModel(model, variant)
        }
    }

    /** Saves a printer without a vendor profile as a user printer preset; returns its name. */
    suspend fun createCustomPrinter(name: String, settings: Map<String, String>): String {
        engineReady.await()
        return withContext(Dispatchers.Default) {
            val keys = settings.keys.toTypedArray()
            NativeEngine.savePreset(
                "printer", name, CUSTOM_PRINTER_BASE, CUSTOM_PRINT, arrayOf(CUSTOM_FILAMENT),
                keys, keys.map { settings.getValue(it) }.toTypedArray(),
            )
        }
    }

    /**
     * Saves the selected [type] profile with the changes made to it as the user profile [name],
     * then uses it. Returns the name; for a printer profile the caller updates its saved printer.
     */
    suspend fun saveUserPreset(type: PresetType, name: String): String {
        engineReady.await()
        val defs = optionDefs ?: error("Profiles are not loaded yet.")
        val own = overrides.filterKeys { defs.typeOf(it) == type }
        val saved = withContext(Dispatchers.Default) {
            NativeEngine.savePreset(
                type.name.lowercase(), name, printer, print, filaments.toTypedArray(),
                own.keys.toTypedArray(), own.values.toTypedArray(),
            )
        }
        overrides = overrides - own.keys
        when (type) {
            PresetType.Print -> changePresets(print = saved, reset = PresetType.Print)
            PresetType.Filament -> changePresets(filaments = filaments.toMutableList().also { it[0] = saved }, reset = PresetType.Filament)
            PresetType.Printer -> Unit // the saved printer switches to it
        }
        refreshPresetLists()
        return saved
    }

    /** Deletes one of the user's own profiles; selections fall back to the defaults. */
    fun deleteUserPreset(type: PresetType, name: String) = launchEngine {
        NativeEngine.deletePreset(type.name.lowercase(), name)
        val lists = PresetLists.parse(NativeEngine.presetsJson(printer))
        withContext(Dispatchers.Main) {
            presets = lists
            message = "Deleted \"$name\"."
        }
        when (type) {
            PresetType.Print -> if (print == name) changePresets(print = lists.defaultPrint.ifEmpty { lists.prints.first() }, reset = type)
            PresetType.Filament -> if (name in filaments) {
                changePresets(filaments = filaments.map { if (it == name) defaultFilament(lists) else it }, reset = type)
            }
            PresetType.Printer -> Unit
        }
    }

    private fun refreshPresetLists() = launchEngine {
        val lists = PresetLists.parse(NativeEngine.presetsJson(printer))
        withContext(Dispatchers.Main) { presets = lists }
    }

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

    /** Counts imports, so the app can show the plate they land on. */
    var importRequests by mutableIntStateOf(0); private set

    /** Models, zip archives of models and projects opened with, shared to or imported into the app. */
    fun importModels(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        importRequests++
        launchEngine("Loading model…") {
            addFiles(uris.map { copyToCache(context, it) })
        }
    }

    /** Adds files on this device: a project on an empty plate opens with its settings, archives are unpacked. */
    fun importFiles(files: List<File>) {
        importRequests++
        launchEngine("Loading model…") { addFiles(files) }
    }

    private suspend fun addFiles(files: List<File>) {
        files.singleOrNull()?.takeIf { ModelFiles.isProject(it) }?.let { project ->
            if (objects.isEmpty()) openProjectFile(project) else withContext(Dispatchers.Main) { pendingImport = PendingImport.Project(project) }
            return
        }
        val unpacked = mutableListOf<ModelFiles.Unpacked>()
        val models = mutableListOf<File>()
        for (file in files) {
            if (!ModelFiles.isArchive(file.name)) {
                models += file
                continue
            }
            val found = ModelFiles.extractModels(file, File(file.parentFile, file.nameWithoutExtension))
            if (found.isEmpty()) withContext(Dispatchers.Main) { message = "${file.name} has no STL, 3MF or OBJ files in it." }
            unpacked += found
        }
        // A single model goes straight on the plate; from several the user picks.
        if (unpacked.size == 1) models += unpacked.single().file
        addModelFiles(models)
        if (unpacked.size > 1) withContext(Dispatchers.Main) { pendingImport = PendingImport.Archive(unpacked) }
    }

    private suspend fun addModelFiles(files: List<File>) {
        if (files.isEmpty()) return
        var lastIds: List<Long> = emptyList()
        for (file in files) {
            val json = JSONObject(NativeEngine.addModel(file.absolutePath, file.nameWithoutExtension))
            val ids = json.getJSONArray("ids")
            lastIds = (0 until ids.length()).map { ids.getLong(it) }
        }
        refreshObjects()
        lastIds.lastOrNull()?.let { selectOnEngine(it) }
    }

    var pendingImport by mutableStateOf<PendingImport?>(null); private set

    /** Adds the models picked from an archive. */
    fun addUnpacked(models: List<ModelFiles.Unpacked>) {
        pendingImport = null
        launchEngine("Loading model…") { addModelFiles(models.map { it.file }) }
    }

    /** Opens the waiting project in place of the plate, or adds its objects to the plate. */
    fun openPendingProject(replacePlate: Boolean) {
        val project = (pendingImport as? PendingImport.Project)?.file ?: return
        pendingImport = null
        launchEngine("Loading model…") { if (replacePlate) openProjectFile(project) else addModelFiles(listOf(project)) }
    }

    fun dismissImport() {
        pendingImport = null
    }

    /** Copies a document to the cache, where the engine can read it. */
    private fun copyToCache(context: Context, uri: Uri): File {
        val name = displayName(context, uri)
        val dir = File(context.cacheDir, "models/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Cannot open $name")
        return file
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val base = (name ?: uri.lastPathSegment ?: "model").substringAfterLast('/')
        if (ModelFiles.isModel(base) || ModelFiles.isArchive(base)) return base
        // Some file managers drop the extension; fall back on the MIME type.
        val mime = context.contentResolver.getType(uri) ?: ""
        val guessed = when {
            "3mf" in mime || "3dmanufacturing" in mime -> "3mf"
            "obj" in mime -> "obj"
            "zip" in mime -> "zip"
            else -> "stl"
        }
        return "$base.$guessed"
    }

    // ------------------------------------------------------------------ projects

    /** File name for saving the plate as a project. */
    val projectName: String
        get() = (objects.firstOrNull()?.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "plate") + ".3mf"

    /** The settings that go with the plate into slicing and projects: overrides, and the slot colors as filament colors. */
    private fun plateSettings(): Map<String, String> = overrides + ("filament_colour" to slotColors.joinToString(";"))

    private fun saveProjectTo(file: File) {
        val settings = plateSettings()
        val keys = settings.keys.toTypedArray()
        NativeEngine.saveProject(
            file.absolutePath, printer, print, filaments.toTypedArray(), keys, keys.map { settings.getValue(it) }.toTypedArray(),
        )
    }

    /** Saves the plate, its painting and the settings as a 3MF project that PrusaSlicer opens too. */
    fun saveProject(context: Context, uri: Uri) = launchEngine("Saving project…", projectThread) {
        val temp = File(context.cacheDir, "project.3mf")
        try {
            saveProjectTo(temp)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Cannot write the project file.")
        } finally {
            temp.delete()
        }
        withContext(Dispatchers.Main) { message = "Project saved." }
    }

    /** Replaces the plate with a 3MF project; its settings come along when it is for this printer. */
    fun openProject(context: Context, uri: Uri) = launchEngine("Opening project…") {
        openProjectFile(copyToCache(context, uri))
    }

    private suspend fun openProjectFile(file: File) {
        val json = JSONObject(NativeEngine.openProject(file.absolutePath))
        NativeEngine.clearPreview()
        withContext(Dispatchers.Main) {
            result = null
            mode = ViewMode.Plate
            transformOpen = false
            layFaceMode = false
        }
        val project = json.optJSONObject("config")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        val name = file.nameWithoutExtension
        if (project != null) applyProjectSettings(project, name) else withContext(Dispatchers.Main) { message = "Opened $name." }
        refreshObjects()
    }

    /**
     * Uses the profiles and setting changes of a project made for the selected printer. A project
     * for another printer keeps the current settings, which are the ones this printer prints with.
     */
    private suspend fun applyProjectSettings(project: Map<String, String>, name: String) {
        val lists = presets ?: return
        val projectPrinter = ModelFiles.parseStrings(project["printer_settings_id"].orEmpty()).firstOrNull().orEmpty()
        if (projectPrinter != printer) {
            withContext(Dispatchers.Main) {
                message = "Opened $name. It was set up for ${projectPrinter.ifEmpty { "another printer" }}, so your current settings apply."
            }
            return
        }
        val newPrint = ModelFiles.parseStrings(project["print_settings_id"].orEmpty()).firstOrNull()?.takeIf { it in lists.prints } ?: print
        val projectFilaments = ModelFiles.parseStrings(project["filament_settings_id"].orEmpty())
        val newFilaments = List(lists.extruders) { i ->
            projectFilaments.getOrNull(i)?.takeIf { it in lists.allFilaments } ?: filaments.getOrElse(i) { defaultFilament(lists) }
        }
        val cfg = loadConfig(printer, newPrint, newFilaments)
        val defs = optionDefs
        val restored = ModelFiles.projectOverrides(project, cfg) { defs?.typeOf(it) != null }
        val colors = ModelFiles.parseStrings(project["filament_colour"].orEmpty()).filter { it.startsWith("#") }
        withContext(Dispatchers.Main) {
            print = newPrint
            filaments = newFilaments
            config = cfg
            overrides = restored
            if (colors.size >= lists.extruders) slotColors = colors.take(lists.extruders)
            settingsChanged()
            message = "Opened $name" + if (restored.isEmpty()) "." else " with its ${restored.size} changed settings."
        }
    }

    /** Puts the plate of the last session back; a file that does not load is dropped. */
    private fun restorePlate(): List<PlateObject> {
        if (!autosaveFile.exists()) return emptyList()
        return try {
            NativeEngine.openProject(autosaveFile.absolutePath)
            PlateObject.parseList(NativeEngine.objectsJson())
        } catch (e: Exception) {
            Log.w(TAG, "Dropping the saved plate", e)
            autosaveFile.delete()
            emptyList()
        }
    }

    /** Saves the plate a moment after it changed, so that it is there again next time. */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            autosave()
        }
    }

    /** Saves a pending change right away, e.g. when the app goes to the background. */
    fun flushAutosave() {
        if (autosaveJob?.isActive != true) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch { autosave() }
    }

    private suspend fun autosave() {
        val empty = objects.isEmpty()
        withContext(projectThread) {
            try {
                if (empty) {
                    autosaveFile.delete()
                } else {
                    autosaveFile.parentFile?.mkdirs()
                    saveProjectTo(autosaveFile)
                }
            } catch (e: Exception) {
                // Not worth interrupting the user; the next change tries again.
                Log.w(TAG, "Saving the plate failed", e)
            }
        }
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
            if (objs.none { it.id == selectedId }) {
                selectedId = -1
                // The engine ends the paint session of a removed object.
                paintMode = null
            }
            if (result != null) resultStale = true
            syncSlots()
            redraw()
            scheduleAutosave()
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
        if (id != selectedId) {
            stopPainting()
            transformOpen = false
        }
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

    /** The move, rotate and scale panel is open for the selected object. */
    var transformOpen by mutableStateOf(false)

    // ------------------------------------------------------------------ painting

    // Paint calls run one at a time in the order they were made, so ending one session and
    // starting the next cannot overtake each other.
    private val paintThread = Dispatchers.Default.limitedParallelism(1)

    /** [PaintMode.SUPPORTS] or [PaintMode.COLORS] while the selected object is painted, else null. */
    var paintMode by mutableStateOf<Int?>(null); private set
    var paintTool by mutableIntStateOf(PaintMode.TOOL_BRUSH)
    /** What the brush puts down: a support state, or a 1-based extruder; 0 erases. */
    var paintState by mutableIntStateOf(PaintMode.ENFORCE)
    /** Brush radius in mm. */
    var brushRadius by mutableFloatStateOf(3f)
    /** A sphere brush also paints faces turned away from the camera, such as both sides of a thin wall. */
    var brushSphere by mutableStateOf(false)
    /** Smart fill stops at edges sharper than this, in degrees. */
    var fillAngle by mutableFloatStateOf(30f)
    /** Height of the band the height tool paints, in mm. */
    var heightRange by mutableFloatStateOf(1f)
    /** Supports: paint only overhangs tilted less than [overhangAngle] from the bed. */
    var overhangsOnly by mutableStateOf(false)
    var showOverhangs by mutableStateOf(false)
    var overhangAngle by mutableFloatStateOf(45f)
    var paintCanUndo by mutableStateOf(false); private set

    /** Brush for the 3D view while painting. */
    val paintBrush: PaintBrush?
        get() = paintMode?.let { mode ->
            PaintBrush(
                state = paintState, radius = brushRadius, tool = paintTool, sphere = brushSphere,
                fillAngle = fillAngle, heightRange = heightRange,
                overhangAngle = if (mode == PaintMode.SUPPORTS && overhangsOnly) overhangAngle else 0f,
            )
        }

    /** Shows the overhangs at [overhangAngle] over the object while painting supports. */
    fun updateOverhangHighlight() {
        val angle = if (paintMode == PaintMode.SUPPORTS && (showOverhangs || overhangsOnly)) overhangAngle else 0f
        launchEngine(dispatcher = paintThread) { NativeEngine.paintHighlightOverhangs(angle) }
    }

    /** Paints the selected support state on every overhang at [overhangAngle]. */
    fun paintAllOverhangs() {
        val state = paintState
        val angle = overhangAngle
        launchEngine(dispatcher = paintThread) {
            val painted = NativeEngine.paintOverhangs(state, angle)
            withContext(Dispatchers.Main) {
                if (painted) supportsFollowPainting() else message = "No overhangs flatter than ${angle.toInt()}° on this object."
            }
            refreshPaint()
        }
    }

    fun startPainting(mode: Int) {
        val obj = selectedObject ?: return
        layFaceMode = false
        transformOpen = false
        // Filament painting starts with a slot the object does not print with yet.
        paintState = if (mode == PaintMode.COLORS) (1..extruderCount).firstOrNull { it !in obj.extruders } ?: 1 else PaintMode.ENFORCE
        launchEngine("Preparing the brush…", paintThread) {
            val ok = NativeEngine.paintBegin(obj.id, mode)
            withContext(Dispatchers.Main) {
                paintMode = if (ok) mode else null
                paintCanUndo = false
                redraw()
            }
        }
    }

    fun stopPainting() {
        if (paintMode == null) return
        paintMode = null
        launchEngine(dispatcher = paintThread) {
            NativeEngine.paintEnd()
            refreshObjects()
        }
    }

    /** A stroke was stored in the model. */
    fun onPainted() {
        supportsFollowPainting()
        launchEngine(dispatcher = paintThread) { refreshPaint() }
    }

    /** Painted enforcers only print with supports on; turn on the ones that follow the painting. */
    private fun supportsFollowPainting() {
        if (paintMode == PaintMode.SUPPORTS && paintState == PaintMode.ENFORCE && value("support_material") != "1") {
            setOverride("support_material", "1")
            setOverride("support_material_auto", "0")
            message = "Supports now print where you paint them. The Supports chip changes this."
        }
    }

    fun undoPaint() = launchEngine(dispatcher = paintThread) {
        NativeEngine.paintUndo()
        refreshPaint()
    }

    fun clearPaint() = launchEngine(dispatcher = paintThread) {
        NativeEngine.paintClear()
        refreshPaint()
    }

    private suspend fun refreshPaint() {
        val state = PaintState.parse(NativeEngine.paintStateJson())
        withContext(Dispatchers.Main) { paintCanUndo = state.canUndo }
        refreshObjects()
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

    /** Slices the plate; with [openPreview] the preview opens when it is done. */
    fun slice(context: Context, openPreview: Boolean = true) {
        if (slicing) return
        stopPainting()
        slicing = true
        sliceProgress = 0
        sliceStatus = "Preparing"
        layFaceMode = false
        val outDir = File(context.cacheDir, "gcode").apply {
            deleteRecursively()
            mkdirs()
        }
        val settings = plateSettings()
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
                if (openPreview) mode = ViewMode.Preview
                layerRange = 0..(r.layerZs.size - 1).coerceAtLeast(0)
                moveFraction = 1f
                // Multi-color prints open in the filament view, single-color ones keep the chosen view.
                if (r.extrudersUsed.size > 1 && viewType == 0) viewType = VIEW_TYPE_TOOL
                syncSlots()
                if (r.warnings.isNotEmpty()) {
                    message = r.warnings.joinToString("\n")
                } else if (mode != ViewMode.Preview) {
                    message = "Sliced. Open the preview to print or save the G-code."
                }
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

    /**
     * Shows the preview. As in desktop slicers, the plate is sliced first when there is no
     * preview yet or the plate or settings changed since.
     */
    fun openPreview(context: Context) {
        when {
            slicing -> Unit
            result != null && !resultStale -> showPreview()
            objects.isNotEmpty() -> slice(context, openPreview = true)
            result != null -> showPreview()
            else -> message = "Add a model to the plate first."
        }
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

    private fun launchEngine(busy: String? = null, dispatcher: CoroutineDispatcher = Dispatchers.Default, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy != null) busyMessage = busy
            try {
                engineReady.await()
                withContext(dispatcher) { block() }
            } catch (t: Throwable) {
                message = t.message ?: t.toString()
            } finally {
                if (busy != null) busyMessage = null
            }
        }
    }

    companion object {
        const val BUNDLE_ASSET = "profiles/AnycubicKobra3.ini"
        /** Always loaded: AndSlice's Kobra 3 profiles, the custom printer base and PrusaSlicer's generic filaments. */
        val BASE_BUNDLES = listOf(BUNDLE_ASSET, "profiles/CustomPrinter.ini", "profiles/vendors/Templates.ini")
        const val CUSTOM_PRINTER_BASE = "Custom printer"
        const val CUSTOM_PRINT = "0.20mm Standard @Custom"
        const val CUSTOM_FILAMENT = "Generic PLA @Template"
        private const val TAG = "AndSlice"
        private const val AUTOSAVE_DELAY_MS = 2_000L
        /** Keys behind the quick settings bar, kept when the print profile changes. */
        val QUICK_SETTINGS = setOf("support_material", "support_material_auto", "support_material_buildplate_only", "fill_density", "brim_width")
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

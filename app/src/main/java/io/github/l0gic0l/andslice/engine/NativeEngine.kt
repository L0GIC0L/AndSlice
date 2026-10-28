package io.github.l0gic0l.andslice.engine

/** Receives slicing progress from PrusaSlicer's status callback. */
fun interface SliceListener {
    fun onProgress(percent: Int, message: String)
}

/**
 * JNI binding to libandslice.so (PrusaSlicer core + libvgcode). Plate and
 * profile calls are thread safe and may block, so call them off the main
 * thread. The renderer and preview calls must run on the GL thread.
 */
object NativeEngine {
    init {
        System.loadLibrary("andslice")
    }

    /** Loads [bundles] (config bundle files; the first is the default vendor) and the user's presets. */
    external fun init(dataDir: String, resourcesDir: String, bundles: Array<String>)
    /** Reloads the profiles from [bundles], keeping the plate. */
    external fun loadProfiles(bundles: Array<String>)
    /** Printer preset of a vendor model's nozzle variant, or "" when its bundle is not loaded. */
    external fun printerForModel(model: String, variant: String): String
    /** Saves the selected [type] ("print", "filament", "printer") preset with the overrides of that type as [name]. */
    external fun savePreset(
        type: String, name: String, printer: String, print: String, filaments: Array<String>,
        keys: Array<String>, values: Array<String>,
    ): String
    external fun deletePreset(type: String, name: String)

    // Profiles
    external fun presetsJson(printer: String): String
    /** [filaments] holds the filament preset of each extruder (ACE slot). */
    external fun configJson(printer: String, print: String, filaments: Array<String>): String
    external fun optionDefsJson(): String
    external fun setPrinter(printer: String)

    // Plate
    /** Gap in mm that arrange and automatic placement keep between objects. */
    external fun setArrangeDistance(mm: Float)
    external fun addModel(path: String, name: String): String
    external fun removeObject(id: Long)
    external fun clearPlate()
    external fun duplicateObject(id: Long): Long
    /** [t] = offset x, y; rotation x, y, z in degrees; scale x, y, z. */
    external fun setTransform(id: Long, t: DoubleArray)
    external fun layOnFace(id: Long, origin: FloatArray, dir: FloatArray): Boolean
    external fun pick(origin: FloatArray, dir: FloatArray): Long
    external fun arrange()
    external fun centerObject(id: Long)
    external fun objectsJson(): String
    external fun select(id: Long)
    /** Gives a whole object (part < 0) or one of its model parts the 1-based [extruder]. */
    external fun setExtruder(id: Long, part: Int, extruder: Int)
    /** values = {x, y, width, depth, brim, height} of the wipe tower in mm. */
    external fun setWipeTower(visible: Boolean, values: FloatArray)
    /** Wipe tower {x, y}; arranging may move it. */
    external fun wipeTowerPosition(): FloatArray

    // Per-object settings
    /** Variable layer height at PrusaSlicer's quality/speed factor (0 thinnest layers, 1 thickest); below 0 turns it off. */
    external fun setLayerQuality(id: Long, quality: Float)
    /** {"options", "ranges", "layer_quality", "object_keys", "range_keys"}; see [ObjectSettings]. */
    external fun objectSettingsJson(id: Long): String
    /** Sets an option of the object, or of its height range starting at [rangeMin] (>= 0); "" removes it. */
    external fun setObjectOption(id: Long, rangeMin: Double, key: String, value: String)
    /** Adds ([oldMin] < 0), moves or (min >= max) removes a height range. */
    external fun editHeightRange(id: Long, oldMin: Double, min: Double, max: Double)

    // Projects
    /** Saves the plate with these settings (presets plus overrides) as a 3MF project at [path]. */
    external fun saveProject(
        path: String, printer: String, print: String, filaments: Array<String>, keys: Array<String>, values: Array<String>,
    )
    /** Replaces the plate with the objects of a 3MF project; returns {"ids", "config"}. */
    external fun openProject(path: String): String

    // Painting (PrusaSlicer's TriangleSelector); see PaintMode for modes and states.
    /** Starts painting the object's surface in [mode] ([PaintMode.SUPPORTS] or [PaintMode.COLORS]). */
    external fun paintBegin(id: Long, mode: Int): Boolean
    /**
     * Paints where the ray from [camera] along [dir] hits the object, as [params] says
     * ([PaintBrush.params]). Returns whether the object was hit.
     */
    external fun paintAt(camera: FloatArray, dir: FloatArray, params: FloatArray, continueStroke: Boolean): Boolean
    /** Paints [state] on every overhang tilted less than [angle] degrees from the bed. */
    external fun paintOverhangs(state: Int, angle: Float): Boolean
    /** Shows the overhangs tilted less than [angle] degrees from the bed; 0 hides them. */
    external fun paintHighlightOverhangs(angle: Float)
    /** Ends the stroke and stores the painting where slicing reads it. */
    external fun paintCommit()
    /** Takes back the stroke in progress. */
    external fun paintCancelStroke()
    external fun paintUndo(): Boolean
    external fun paintClear()
    external fun paintEnd()
    /** {"active", "object", "mode", "can_undo", "painted"} */
    external fun paintStateJson(): String

    // Slicing
    external fun slice(
        printer: String,
        print: String,
        filaments: Array<String>,
        keys: Array<String>,
        values: Array<String>,
        outputPath: String,
        listener: SliceListener?,
    ): String
    external fun cancelSlice()
    external fun clearPreview()

    // Renderer, GL thread only
    external fun rendererSurfaceCreated(): String
    external fun rendererSurfaceChanged(width: Int, height: Int)
    external fun rendererDraw(view: FloatArray, projection: FloatArray, mode: Int)
    /** 7 RGBA colors: background, bed, grid, bed edges, object, selected object, object outside the bed. */
    external fun rendererSetPalette(colors: FloatArray)
    /** RGBA per extruder; an empty array draws objects in the theme color. */
    external fun rendererSetExtruderColors(colors: FloatArray)
    external fun previewSetLayerRange(first: Int, last: Int)
    external fun previewSetMoveFraction(fraction: Float)
    external fun previewSetViewType(type: Int)
    external fun previewSetOptionVisible(option: Int, visible: Boolean)
    external fun previewSetRoleVisible(role: Int, visible: Boolean)
    external fun previewInfoJson(): String
}

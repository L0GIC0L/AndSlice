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

    external fun init(dataDir: String, resourcesDir: String, bundlePath: String)

    // Profiles
    external fun presetsJson(printer: String): String
    external fun configJson(printer: String, print: String, filament: String): String
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

    // Slicing
    external fun slice(
        printer: String,
        print: String,
        filament: String,
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
    external fun previewSetLayerRange(first: Int, last: Int)
    external fun previewSetMoveFraction(fraction: Float)
    external fun previewSetViewType(type: Int)
    external fun previewSetOptionVisible(option: Int, visible: Boolean)
    external fun previewSetRoleVisible(role: Int, visible: Boolean)
    external fun previewInfoJson(): String
}

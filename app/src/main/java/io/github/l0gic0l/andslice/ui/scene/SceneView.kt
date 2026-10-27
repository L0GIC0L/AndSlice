package io.github.l0gic0l.andslice.ui.scene

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import io.github.l0gic0l.andslice.engine.NativeEngine
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** What the 3D view needs from the app state, and what it reports back. */
interface SceneCallbacks {
    /** Currently selected object id, or -1. */
    fun selectedId(): Long
    fun onTap(objectId: Long, origin: FloatArray, dir: FloatArray)
    /** The selected object was dragged to (x, y); [done] is true when the finger lifts. */
    fun onDragObject(id: Long, x: Double, y: Double, done: Boolean)
    /** Start offset of the object, used while dragging it. */
    fun objectOffset(id: Long): Pair<Double, Double>?
    fun onRendererError(message: String)
    fun onPreviewInfo(json: String)
}

/**
 * GLSurfaceView that draws the plate through libandslice and turns touch input
 * into camera moves, object selection and object dragging.
 */
@SuppressLint("ViewConstructor")
class SceneView(context: Context, private val callbacks: SceneCallbacks) : GLSurfaceView(context) {

    val camera = OrbitCamera()
    @Volatile var mode: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Matrices handed to the GL thread; replaced, never mutated after publishing.
    @Volatile private var matrices: Pair<FloatArray, FloatArray> = FloatArray(16) to FloatArray(16)

    private var lastPreviewInfo = ""

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                val error = NativeEngine.rendererSurfaceCreated()
                lastPreviewInfo = ""
                if (error.isNotEmpty()) mainHandler.post { callbacks.onRendererError(error) }
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                NativeEngine.rendererSurfaceChanged(width, height)
                mainHandler.post {
                    camera.aspect = width.toFloat() / height.coerceAtLeast(1)
                    publishCamera()
                }
            }

            override fun onDrawFrame(gl: GL10?) {
                val (view, projection) = matrices
                NativeEngine.rendererDraw(view, projection, mode)
                val info = NativeEngine.previewInfoJson()
                if (info != lastPreviewInfo) {
                    lastPreviewInfo = info
                    mainHandler.post { callbacks.onPreviewInfo(info) }
                }
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
        camera.update()
        publishCamera()
    }

    fun publishCamera() {
        camera.update()
        matrices = camera.view.copyOf() to camera.projection.copyOf()
        requestRender()
    }

    /** Runs [block] on the GL thread, then redraws. */
    fun onGlThread(block: () -> Unit) {
        queueEvent(block)
        requestRender()
    }

    // ------------------------------------------------------------------ touch

    private var draggingId = -1L
    private var dragStart: FloatArray? = null
    private var dragOffset: Pair<Double, Double>? = null
    private var dragMoved = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var multiTouch = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            publishCamera()
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val (o, d) = camera.ray(e.x, e.y, width, height)
            callbacks.onTap(NativeEngine.pick(o, d), o, d)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            camera.zoom(1.6f)
            publishCamera()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                lastX = event.x
                lastY = event.y
                startObjectDrag(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                finishObjectDrag()
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val fx = focusX(event)
                    val fy = focusY(event)
                    camera.pan(fx - lastFocusX, fy - lastFocusY, height)
                    lastFocusX = fx
                    lastFocusY = fy
                    publishCamera()
                } else if (!multiTouch) {
                    if (draggingId >= 0) {
                        moveObjectDrag(event.x, event.y, done = false)
                    } else {
                        camera.orbit(event.x - lastX, event.y - lastY)
                        publishCamera()
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue with the remaining finger without a jump.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingId >= 0) moveObjectDrag(event.x, event.y, done = true)
                finishObjectDrag()
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    private fun startObjectDrag(x: Float, y: Float) {
        val selected = callbacks.selectedId()
        if (selected < 0 || mode != 0) return
        val (o, d) = camera.ray(x, y, width, height)
        if (NativeEngine.pick(o, d) != selected) return
        draggingId = selected
        dragStart = camera.hitPlane(x, y, width, height)
        dragOffset = callbacks.objectOffset(selected)
        dragMoved = false
        if (dragStart == null || dragOffset == null) draggingId = -1
    }

    private fun moveObjectDrag(x: Float, y: Float, done: Boolean) {
        val start = dragStart ?: return
        val offset = dragOffset ?: return
        val now = camera.hitPlane(x, y, width, height) ?: return
        if (!done) dragMoved = true
        if (done && !dragMoved) return
        callbacks.onDragObject(draggingId, offset.first + (now[0] - start[0]), offset.second + (now[1] - start[1]), done)
        requestRender()
    }

    private fun finishObjectDrag() {
        draggingId = -1
        dragStart = null
        dragOffset = null
    }
}

/** RGBA8888 + depth, with 4x MSAA when the GPU offers it. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3Bit = 0x40 // EGL_OPENGL_ES3_BIT_KHR
        fun attribs(samples: Int) = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24,
            EGL10.EGL_RENDERABLE_TYPE, es3Bit,
            EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
            EGL10.EGL_SAMPLES, samples,
            EGL10.EGL_NONE,
        )
        for (samples in intArrayOf(4, 0)) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (egl.eglChooseConfig(display, attribs(samples), configs, 1, count) && count[0] > 0) {
                return configs[0]!!
            }
        }
        throw IllegalStateException("No OpenGL ES 3 configuration available")
    }
}

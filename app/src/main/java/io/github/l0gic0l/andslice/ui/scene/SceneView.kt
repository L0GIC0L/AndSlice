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
import io.github.l0gic0l.andslice.engine.PaintBrush
import io.github.l0gic0l.andslice.engine.PaintMode
import java.util.concurrent.atomic.AtomicReference
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
    /** Wipe tower box {x, y, width, depth, height} when one is shown, or null. */
    fun wipeTower(): FloatArray?
    /** The wipe tower was dragged to (x, y); [done] is true when the finger lifts. */
    fun onDragWipeTower(x: Float, y: Float, done: Boolean)
    /** Brush while the selected object is being painted, else null. */
    fun paintBrush(): PaintBrush?
    /** A paint stroke was stored in the model. */
    fun onPainted()
}

/**
 * GLSurfaceView that draws the plate through libandslice and turns touch input
 * into camera moves, object selection and object dragging.
 */
@SuppressLint("ViewConstructor")
class SceneView(
    context: Context,
    private val callbacks: SceneCallbacks,
    val camera: OrbitCamera,
    /** Multisample anti-aliasing samples to ask for; fixed for the life of the view. */
    samples: Int = 4,
) : GLSurfaceView(context) {

    @Volatile var mode: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Matrices handed to the GL thread; replaced, never mutated after publishing.
    @Volatile private var matrices: Pair<FloatArray, FloatArray> = FloatArray(16) to FloatArray(16)

    private var lastPreviewInfo = ""

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser(samples))
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

    /** Fraction of the view's resolution the scene is drawn at; less is faster on slow GPUs. */
    var renderScale = 1f
        set(value) {
            if (field == value) return
            field = value
            applyRenderScale()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // After the layout pass, as resizing the surface asks for another one.
        post { applyRenderScale() }
    }

    /** A smaller surface than the view, which the compositor scales up. */
    private fun applyRenderScale() {
        if (renderScale >= 1f || width <= 0 || height <= 0) {
            holder.setSizeFromLayout()
        } else {
            holder.setFixedSize((width * renderScale).toInt().coerceAtLeast(1), (height * renderScale).toInt().coerceAtLeast(1))
        }
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
    private var draggingTower = false
    private var dragStart: FloatArray? = null
    private var dragOffset: Pair<Double, Double>? = null
    private var dragMoved = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var multiTouch = false

    // Painting: a finger that lands on the painted object paints; elsewhere it turns the view.
    private var painting = false
    private var paintMoved = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private class PaintDab(val eye: FloatArray, val dir: FloatArray, val brush: PaintBrush, val continueStroke: Boolean)

    // Latest dab waiting for the GL thread; dabs that arrive meanwhile replace it, so slow strokes don't queue up.
    private val pendingDab = AtomicReference<PaintDab?>(null)

    private fun queueDab(x: Float, y: Float, brush: PaintBrush, continueStroke: Boolean) {
        val (_, dir) = camera.ray(x, y, width, height)
        if (pendingDab.getAndSet(PaintDab(camera.eye(), dir, brush, continueStroke)) == null) {
            queueEvent {
                val dab = pendingDab.getAndSet(null) ?: return@queueEvent
                NativeEngine.paintAt(dab.eye, dab.dir, dab.brush.radius, dab.brush.tool, dab.brush.state, dab.continueStroke)
            }
        }
        requestRender()
    }

    private fun commitStroke() {
        queueEvent {
            NativeEngine.paintCommit()
            mainHandler.post { callbacks.onPainted() }
        }
        requestRender()
    }

    private fun cancelStroke() {
        queueEvent {
            pendingDab.set(null)
            NativeEngine.paintCancelStroke()
        }
        requestRender()
    }

    private fun hitsSelected(x: Float, y: Float): Boolean {
        val (o, d) = camera.ray(x, y, width, height)
        val selected = callbacks.selectedId()
        return selected >= 0 && NativeEngine.pick(o, d) == selected
    }

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
        val brush = if (mode == 0) callbacks.paintBrush() else null
        scaleDetector.onTouchEvent(event)
        // Taps paint while painting, so they neither select nor zoom.
        if (brush == null) tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                lastX = event.x
                lastY = event.y
                if (brush != null) {
                    painting = hitsSelected(event.x, event.y)
                    paintMoved = false
                    downX = event.x
                    downY = event.y
                    if (painting && brush.tool == PaintMode.TOOL_BRUSH) queueDab(event.x, event.y, brush, false)
                } else {
                    startObjectDrag(event.x, event.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                // A second finger makes it a camera gesture; take back what the first one painted.
                if (painting) {
                    painting = false
                    if (brush?.tool == PaintMode.TOOL_BRUSH) cancelStroke()
                }
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
                    if (painting && brush != null) {
                        if (kotlin.math.hypot(event.x - downX, event.y - downY) > touchSlop) paintMoved = true
                        if (brush.tool == PaintMode.TOOL_BRUSH) queueDab(event.x, event.y, brush, true)
                    } else if (draggingId >= 0 || draggingTower) {
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
                if (painting && brush != null) {
                    painting = false
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        cancelStroke()
                    } else {
                        // The fill tool fills the area under a tap.
                        if (brush.tool == PaintMode.TOOL_FILL && !paintMoved) queueDab(event.x, event.y, brush, false)
                        commitStroke()
                    }
                }
                if (draggingId >= 0 || draggingTower) moveObjectDrag(event.x, event.y, done = true)
                finishObjectDrag()
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    /** Starts dragging the selected object, or the wipe tower when the touch lands on it. */
    private fun startObjectDrag(x: Float, y: Float) {
        val selected = callbacks.selectedId()
        if (mode != 0 || (selected < 0 && callbacks.wipeTower() == null)) return
        val (o, d) = camera.ray(x, y, width, height)
        val picked = NativeEngine.pick(o, d)
        dragMoved = false
        dragStart = camera.hitPlane(x, y, width, height)
        if (selected >= 0 && picked == selected) {
            draggingId = selected
            dragOffset = callbacks.objectOffset(selected)
        } else if (picked < 0) {
            val tower = callbacks.wipeTower()
            if (tower != null && rayHitsBox(o, d, tower)) {
                draggingTower = true
                dragOffset = tower[0].toDouble() to tower[1].toDouble()
            }
        }
        if (dragStart == null || dragOffset == null) finishObjectDrag()
    }

    private fun moveObjectDrag(x: Float, y: Float, done: Boolean) {
        val start = dragStart ?: return
        val offset = dragOffset ?: return
        val now = camera.hitPlane(x, y, width, height) ?: return
        if (!done) dragMoved = true
        if (done && !dragMoved) return
        val nx = offset.first + (now[0] - start[0])
        val ny = offset.second + (now[1] - start[1])
        if (draggingTower) callbacks.onDragWipeTower(nx.toFloat(), ny.toFloat(), done)
        else callbacks.onDragObject(draggingId, nx, ny, done)
        requestRender()
    }

    private fun finishObjectDrag() {
        draggingId = -1
        draggingTower = false
        dragStart = null
        dragOffset = null
    }

    /** Slab test of the ray against the box {x, y, width, depth, height} standing on the bed. */
    private fun rayHitsBox(o: FloatArray, d: FloatArray, box: FloatArray): Boolean {
        val min = floatArrayOf(box[0], box[1], 0f)
        val max = floatArrayOf(box[0] + box[2], box[1] + box[3], maxOf(box[4], 1f))
        var near = 0f
        var far = Float.MAX_VALUE
        for (i in 0..2) {
            if (kotlin.math.abs(d[i]) < 1e-9f) {
                if (o[i] < min[i] || o[i] > max[i]) return false
                continue
            }
            val a = (min[i] - o[i]) / d[i]
            val b = (max[i] - o[i]) / d[i]
            near = maxOf(near, minOf(a, b))
            far = minOf(far, maxOf(a, b))
            if (near > far) return false
        }
        return true
    }
}

/** RGBA8888 + depth, with [samples]x MSAA when the GPU offers it. */
private class MultisampleConfigChooser(private val samples: Int) : GLSurfaceView.EGLConfigChooser {
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
        for (samples in listOf(samples, 0).distinct()) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (egl.eglChooseConfig(display, attribs(samples), configs, 1, count) && count[0] > 0) {
                return configs[0]!!
            }
        }
        throw IllegalStateException("No OpenGL ES 3 configuration available")
    }
}

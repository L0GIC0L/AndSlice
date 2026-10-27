package io.github.l0gic0l.andslice.ui.scene

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Z-up orbit camera around a target point, in millimetres like the bed. */
class OrbitCamera {
    var targetX = 127.5f
    var targetY = 127.5f
    var targetZ = 0f
    var distance = 480f
    var yawDeg = -110f
    var pitchDeg = 35f
    var aspect = 1f
    private val fovDeg = 40f

    val view = FloatArray(16)
    val projection = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)

    fun reset(bedCenterX: Float, bedCenterY: Float, bedSize: Float) {
        targetX = bedCenterX
        targetY = bedCenterY
        targetZ = 0f
        distance = bedSize * 1.9f
        yawDeg = -110f
        pitchDeg = 35f
        update()
    }

    fun orbit(dxPixels: Float, dyPixels: Float) {
        yawDeg -= dxPixels * 0.3f
        pitchDeg = (pitchDeg + dyPixels * 0.3f).coerceIn(-5f, 89f)
        update()
    }

    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(20f, 3000f)
        update()
    }

    /** Moves the target so the scene follows the fingers. */
    fun pan(dxPixels: Float, dyPixels: Float, viewHeightPixels: Int) {
        val worldPerPixel = 2f * distance * tan(Math.toRadians(fovDeg / 2.0)).toFloat() / max(viewHeightPixels, 1)
        val yaw = Math.toRadians(yawDeg.toDouble())
        // Screen right and screen up projected onto the camera basis.
        val rightX = -sin(yaw).toFloat()
        val rightY = cos(yaw).toFloat()
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val upX = (-sin(pitch) * cos(yaw)).toFloat()
        val upY = (-sin(pitch) * sin(yaw)).toFloat()
        val upZ = cos(pitch).toFloat()
        targetX += (-dxPixels * rightX + dyPixels * upX) * worldPerPixel
        targetY += (-dxPixels * rightY + dyPixels * upY) * worldPerPixel
        targetZ += dyPixels * upZ * worldPerPixel
        update()
    }

    fun eye(): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        return floatArrayOf(
            targetX + (distance * cos(pitch) * cos(yaw)).toFloat(),
            targetY + (distance * cos(pitch) * sin(yaw)).toFloat(),
            targetZ + (distance * sin(pitch)).toFloat(),
        )
    }

    fun update() {
        val e = eye()
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], targetX, targetY, targetZ, 0f, 0f, 1f)
        Matrix.perspectiveM(projection, 0, fovDeg, aspect, max(1f, distance / 100f), distance * 10f + 1000f)
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        Matrix.invertM(invViewProj, 0, viewProj, 0)
    }

    /** World-space ray through the given view pixel: (origin, direction). */
    fun ray(x: Float, y: Float, width: Int, height: Int): Pair<FloatArray, FloatArray> {
        val nx = 2f * x / max(width, 1) - 1f
        val ny = 1f - 2f * y / max(height, 1)
        val near = unproject(nx, ny, -1f)
        val far = unproject(nx, ny, 1f)
        val dir = floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
        return near to dir
    }

    /** Point where the ray through the pixel meets the plane z = [z], or null. */
    fun hitPlane(x: Float, y: Float, width: Int, height: Int, z: Float = 0f): FloatArray? {
        val (o, d) = ray(x, y, width, height)
        if (kotlin.math.abs(d[2]) < 1e-6f) return null
        val t = (z - o[2]) / d[2]
        if (t < 0f) return null
        return floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, z)
    }

    private fun unproject(nx: Float, ny: Float, nz: Float): FloatArray {
        val v = floatArrayOf(nx, ny, nz, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invViewProj, 0, v, 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }
}

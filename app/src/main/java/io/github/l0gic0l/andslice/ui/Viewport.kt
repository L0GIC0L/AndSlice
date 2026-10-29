package io.github.l0gic0l.andslice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode
import io.github.l0gic0l.andslice.engine.NativeEngine
import io.github.l0gic0l.andslice.engine.PreviewInfo
import io.github.l0gic0l.andslice.engine.PreviewOption
import io.github.l0gic0l.andslice.ui.scene.OrbitCamera
import io.github.l0gic0l.andslice.ui.scene.SceneCallbacks
import io.github.l0gic0l.andslice.ui.scene.SceneView

/**
 * The 3D plate / G-code preview, kept in sync with the view model. [antialiasing] is fixed
 * when the view is created, so callers wrap the view in a key of it.
 */
@Composable
fun Viewport(
    vm: SlicerViewModel,
    camera: OrbitCamera,
    modifier: Modifier = Modifier,
    antialiasing: Int = 4,
    renderScale: Float = 1f,
    showGrid: Boolean = true,
    /** One of SceneView's DETAIL_ values. */
    toolpathDetail: Int = SceneView.DETAIL_SIMPLE_WHILE_MOVING,
    orthographic: Boolean = false,
    turnSpeed: Float = 1f,
    invertVertical: Boolean = false,
) {
    val context = LocalContext.current
    val view = remember {
        SceneView(context, camera = camera, samples = antialiasing, callbacks = object : SceneCallbacks {
            override fun selectedId() = vm.selectedId
            override fun onTap(objectId: Long, origin: FloatArray, dir: FloatArray) = vm.onTap(objectId, origin, dir)
            override fun onDragObject(id: Long, x: Double, y: Double, done: Boolean) = vm.dragObject(id, x, y, done)
            override fun objectOffset(id: Long) = vm.objects.firstOrNull { it.id == id }?.let { it.offset[0] to it.offset[1] }
            override fun onRendererError(message: String) {
                vm.message = "G-code preview is unavailable on this device: $message"
            }
            override fun onPreviewInfo(json: String) = vm.onPreviewInfo(PreviewInfo.parse(json))
            override fun wipeTower() = if (vm.wipeTowerVisible) vm.wipeTowerFootprint() else null
            override fun onDragWipeTower(x: Float, y: Float, done: Boolean) = vm.dragWipeTower(x, y, done)
            override fun paintBrush() = vm.paintBrush
            override fun onPainted() = vm.onPainted()
        })
    }

    // Pause rendering with the activity so the GL thread stops in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Frame the bed once the printer is known, and again only when the bed changes.
    LaunchedEffect(vm.config["bed_shape"]) {
        val bed = vm.config["bed_shape"] ?: return@LaunchedEffect
        if (camera.framedBed == bed) return@LaunchedEffect
        camera.framedBed = bed
        val pts = bed.split(',').mapNotNull { p ->
            val xy = p.split('x')
            if (xy.size == 2) xy[0].toFloatOrNull()?.let { x -> xy[1].toFloatOrNull()?.let { y -> x to y } } else null
        }
        if (pts.isEmpty()) return@LaunchedEffect
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        view.camera.reset((minX + maxX) / 2, (minY + maxY) / 2, maxOf(maxX - minX, maxY - minY))
        view.publishCamera()
    }

    // Scene colors come from the Material color scheme, so they follow dynamic color and dark mode.
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.surfaceContainerLow, scheme.surfaceContainerHighest, scheme.outlineVariant.copy(alpha = if (showGrid) 0.8f else 0f),
        scheme.outline.copy(alpha = 0.9f), scheme.secondary, scheme.primary, scheme.error,
    )
    LaunchedEffect(palette) {
        val values = FloatArray(palette.size * 4)
        palette.forEachIndexed { i, c: Color ->
            values[i * 4] = c.red
            values[i * 4 + 1] = c.green
            values[i * 4 + 2] = c.blue
            values[i * 4 + 3] = c.alpha
        }
        view.onGlThread { NativeEngine.rendererSetPalette(values) }
    }

    // On multi-material printers objects show the color of their filament slot.
    LaunchedEffect(vm.slotColors, vm.multiMaterial) {
        val colors = if (vm.multiMaterial) vm.slotColors.flatMap { hex ->
            val c = parseHexColor(hex)
            listOf(c.red, c.green, c.blue, 1f)
        }.toFloatArray() else FloatArray(0)
        view.onGlThread { NativeEngine.rendererSetExtruderColors(colors) }
    }

    LaunchedEffect(vm.mode) {
        view.mode = if (vm.mode == ViewMode.Preview) 1 else 0
        view.requestRender()
    }
    // Sliders move faster than the preview can follow on big prints; the view keeps only the latest.
    LaunchedEffect(vm.layerRange, vm.moveFraction) {
        view.setPreviewView(vm.layerRange.first, vm.layerRange.last, vm.moveFraction)
    }
    LaunchedEffect(vm.viewType) {
        val t = vm.viewType
        view.onGlThread { NativeEngine.previewSetViewType(t) }
    }
    LaunchedEffect(vm.showTravel) {
        val show = vm.showTravel
        view.onGlThread { NativeEngine.previewSetOptionVisible(PreviewOption.TRAVELS, show) }
    }
    // A new slice result is loaded by the renderer on the next frame; re-apply the ranges after it.
    LaunchedEffect(vm.result, vm.previewInfo.loaded) {
        val r = vm.layerRange
        val f = vm.moveFraction
        val t = vm.viewType
        view.onGlThread { NativeEngine.previewSetViewType(t) }
        view.setPreviewView(r.first, r.last, f)
    }
    LaunchedEffect(toolpathDetail) {
        view.toolpathDetail = toolpathDetail
        view.requestRender()
    }
    LaunchedEffect(orthographic, turnSpeed, invertVertical) {
        camera.orthographic = orthographic
        camera.turnSpeed = turnSpeed
        camera.invertVertical = invertVertical
        view.publishCamera()
    }
    LaunchedEffect(vm.renderTick) { view.requestRender() }
    LaunchedEffect(renderScale) { view.renderScale = renderScale }

    AndroidView(factory = { view }, modifier = modifier)
}

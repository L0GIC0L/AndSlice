package io.github.l0gic0l.andslice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode
import io.github.l0gic0l.andslice.engine.NativeEngine
import io.github.l0gic0l.andslice.engine.PreviewInfo
import io.github.l0gic0l.andslice.engine.PreviewOption
import io.github.l0gic0l.andslice.ui.scene.SceneCallbacks
import io.github.l0gic0l.andslice.ui.scene.SceneView

/** The 3D plate / G-code preview, kept in sync with the view model. */
@Composable
fun Viewport(vm: SlicerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember {
        SceneView(context, object : SceneCallbacks {
            override fun selectedId() = vm.selectedId
            override fun onTap(objectId: Long, origin: FloatArray, dir: FloatArray) = vm.onTap(objectId, origin, dir)
            override fun onDragObject(id: Long, x: Double, y: Double, done: Boolean) = vm.dragObject(id, x, y, done)
            override fun objectOffset(id: Long) = vm.objects.firstOrNull { it.id == id }?.let { it.offset[0] to it.offset[1] }
            override fun onRendererError(message: String) {
                vm.message = "G-code preview is unavailable on this device: $message"
            }
            override fun onPreviewInfo(json: String) = vm.onPreviewInfo(PreviewInfo.parse(json))
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

    // Frame the bed once the printer is known.
    LaunchedEffect(vm.printer, vm.config["bed_shape"]) {
        val bed = vm.config["bed_shape"] ?: return@LaunchedEffect
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

    LaunchedEffect(vm.mode) {
        view.mode = if (vm.mode == ViewMode.Preview) 1 else 0
        view.requestRender()
    }
    LaunchedEffect(vm.layerRange) {
        val r = vm.layerRange
        view.onGlThread { NativeEngine.previewSetLayerRange(r.first, r.last) }
    }
    LaunchedEffect(vm.moveFraction) {
        val f = vm.moveFraction
        view.onGlThread { NativeEngine.previewSetMoveFraction(f) }
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
        view.onGlThread {
            NativeEngine.previewSetViewType(t)
            NativeEngine.previewSetLayerRange(r.first, r.last)
            NativeEngine.previewSetMoveFraction(f)
        }
    }
    LaunchedEffect(vm.renderTick) { view.requestRender() }

    AndroidView(factory = { view }, modifier = modifier)
}

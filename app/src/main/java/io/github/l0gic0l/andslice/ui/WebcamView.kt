package io.github.l0gic0l.andslice.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.octoprint.MjpegReader
import io.github.l0gic0l.andslice.octoprint.Webcam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live picture of a printer camera: the MJPEG stream when there is one, otherwise the
 * snapshot refreshed every second. Streaming stops when the view leaves the screen.
 */
@Composable
fun WebcamView(webcam: Webcam?, apiKey: String, modifier: Modifier = Modifier) {
    var image by remember(webcam) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(webcam) { mutableStateOf<String?>(null) }

    LaunchedEffect(webcam) {
        if (webcam == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    val stream = webcam.streamUrl
                    if (stream != null) streamFrames(stream, apiKey) { image = it; error = null }
                    else snapshotLoop(webcam.snapshotUrl!!, apiKey) { image = it; error = null }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    // Fall back to snapshots when the stream does not work.
                    val snapshot = webcam.snapshotUrl
                    if (snapshot != null && webcam.streamUrl != null) {
                        try {
                            snapshotLoop(snapshot, apiKey) { image = it; error = null }
                        } catch (e2: Exception) {
                            currentCoroutineContext().ensureActive()
                            error = "Camera unavailable (${e2.message ?: e2.javaClass.simpleName})"
                        }
                    } else {
                        error = "Camera unavailable (${e.message ?: e.javaClass.simpleName})"
                    }
                }
                delay(3_000) // retry after a dropped connection
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        val frame = image
        when {
            webcam == null -> NoCamera("No camera found. Set its address in the printer's settings.")
            frame != null -> Image(
                frame,
                contentDescription = "Printer camera",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = if (webcam.flipHorizontal) -1f else 1f
                    scaleY = if (webcam.flipVertical) -1f else 1f
                    rotationZ = webcam.rotation.toFloat()
                },
            )
            error != null -> NoCamera(error!!)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun NoCamera(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
        Icon(Icons.Default.VideocamOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun open(url: String, apiKey: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 10_000
        if (apiKey.isNotEmpty()) setRequestProperty("X-Api-Key", apiKey)
    }

private suspend fun streamFrames(url: String, apiKey: String, onFrame: (ImageBitmap) -> Unit) {
    val connection = open(url, apiKey)
    // Blocking reads do not notice cancellation; closing the connection ends them.
    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
    val handle = job?.invokeOnCompletion { connection.disconnect() }
    try {
        if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
        if (connection.contentType?.startsWith("image/") == true) {
            // A snapshot URL in the stream field: show it and keep refreshing.
            decode(connection.inputStream.readBytes())?.asImageBitmap()?.let(onFrame)
            connection.disconnect()
            snapshotLoop(url, apiKey, onFrame)
            return
        }
        val reader = MjpegReader(connection.inputStream)
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytes = reader.nextFrame() ?: throw IllegalStateException("stream ended")
            decode(bytes)?.asImageBitmap()?.let(onFrame)
        }
    } finally {
        handle?.dispose()
        connection.disconnect()
    }
}

private suspend fun snapshotLoop(url: String, apiKey: String, onFrame: (ImageBitmap) -> Unit) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val connection = open(url, apiKey)
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            decode(connection.inputStream.readBytes())?.asImageBitmap()?.let(onFrame)
        } finally {
            connection.disconnect()
        }
        delay(1_000)
    }
}

private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

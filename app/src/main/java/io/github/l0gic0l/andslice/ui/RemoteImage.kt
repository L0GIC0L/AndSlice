package io.github.l0gic0l.andslice.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Small images from the printer, such as G-code thumbnails, kept while the app runs. */
private val cache = LruCache<String, ImageBitmap>(64)

/** An image from [url] on the printer; [placeholder] shows until it loads, or if it does not. */
@Composable
fun RemoteImage(url: String?, apiKey: String, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    var image by remember(url) { mutableStateOf(url?.let { cache.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || image != null) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    if (apiKey.isNotEmpty()) setRequestProperty("X-Api-Key", apiKey)
                }
                try {
                    if (connection.responseCode !in 200..299) return@withContext null
                    val bytes = connection.inputStream.use { it.readBytes() }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(url, it) }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    val loaded = image
    if (loaded != null) Image(loaded, null, modifier, contentScale = ContentScale.Fit) else placeholder()
}

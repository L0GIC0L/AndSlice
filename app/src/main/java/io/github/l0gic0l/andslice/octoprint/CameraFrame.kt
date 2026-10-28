package io.github.l0gic0l.andslice.octoprint

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One JPEG from the printer's camera: the snapshot when there is one, else the first frame of
 * the MJPEG stream. Returns null when the camera does not answer. Blocks, so call it off the
 * main thread.
 */
fun grabCameraFrame(webcam: Webcam, apiKey: String): ByteArray? {
    for (url in listOfNotNull(webcam.snapshotUrl, webcam.streamUrl)) {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 10_000
                if (apiKey.isNotEmpty()) setRequestProperty("X-Api-Key", apiKey)
            }
        } catch (e: Exception) {
            continue
        }
        try {
            if (connection.responseCode !in 200..299) continue
            val frame = if (connection.contentType?.startsWith("image/") == true) {
                connection.inputStream.use { it.readBytes() }
            } else {
                MjpegReader(connection.inputStream).nextFrame()
            }
            if (frame != null && frame.isNotEmpty()) return frame
        } catch (e: IOException) {
            // Try the other address.
        } finally {
            connection.disconnect()
        }
    }
    return null
}

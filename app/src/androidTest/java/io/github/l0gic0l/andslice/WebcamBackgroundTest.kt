package io.github.l0gic0l.andslice

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.andslice.octoprint.Webcam
import io.github.l0gic0l.andslice.ui.WebcamView
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The printer camera streams only while the app is on screen. */
@RunWith(AndroidJUnit4::class)
class WebcamBackgroundTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** An endless MJPEG stream, as mjpg-streamer serves it, that counts its connections. */
    private class FakeCamera : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        /** Connections being streamed to now. */
        val open = AtomicInteger(0)
        /** Connections made so far. */
        val made = AtomicInteger(0)
        private val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                    made.incrementAndGet()
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            open.incrementAndGet()
            try {
                val request = client.getInputStream().bufferedReader()
                while (request.readLine()?.isNotEmpty() == true) Unit
                val out = client.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())
                while (true) {
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                // The app closed the connection.
            } finally {
                open.decrementAndGet()
                client.close()
            }
        }

        override fun close() = server.close()
    }

    // Polls with the activity in any state; the Compose test clock needs it on screen.
    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun streamClosesInTheBackgroundAndComesBack() {
        FakeCamera().use { camera ->
            val url = "http://127.0.0.1:${camera.server.localPort}/?action=stream"
            compose.setContent { WebcamView(Webcam(streamUrl = url, snapshotUrl = null), apiKey = "") }
            waitFor("the stream") { camera.open.get() == 1 }

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            waitFor("the stream to close") { camera.open.get() == 0 }
            // Nothing connects again while the app is in the background; it would retry within 3 s.
            val made = camera.made.get()
            Thread.sleep(4_000)
            assertEquals(made, camera.made.get())

            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("the stream to start again") { camera.open.get() == 1 }
        }
    }
}

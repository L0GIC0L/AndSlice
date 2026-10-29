package io.github.l0gic0l.andslice.octoprint

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Splits an MJPEG stream (multipart/x-mixed-replace, as served by mjpg-streamer,
 * crowsnest and OctoPrint's webcam) into JPEG frames. Frames are found by their
 * start (FF D8) and end (FF D9) markers, so the part headers do not matter.
 */
class MjpegReader(input: InputStream, private val maxFrameBytes: Int = 8 * 1024 * 1024) {
    private val input = BufferedInputStream(input, 64 * 1024)
    private val frame = ByteArrayOutputStream(256 * 1024)

    /** The next complete JPEG, or null at the end of the stream. */
    fun nextFrame(): ByteArray? {
        // Find the start of image.
        var previous = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (previous == 0xFF && b == 0xD8) break
            previous = b
        }
        frame.reset()
        frame.write(0xFF)
        frame.write(0xD8)
        previous = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            frame.write(b)
            if (previous == 0xFF && b == 0xD9) return frame.toByteArray()
            if (frame.size() > maxFrameBytes) {
                // Not a JPEG stream after all, or a broken frame: look for the next one.
                frame.reset()
                return nextFrame()
            }
            previous = b
        }
    }
}

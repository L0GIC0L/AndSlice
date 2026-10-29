package io.github.l0gic0l.andslice.octoprint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MjpegReaderTest {
    private fun jpeg(vararg body: Int) = (listOf(0xFF, 0xD8) + body.toList() + listOf(0xFF, 0xD9)).map { it.toByte() }.toByteArray()

    /** Two parts as mjpg-streamer sends them, with headers between the frames. */
    private fun stream(vararg frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in frames) {
            out.write("--boundarydonotcross\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
            out.write(f)
            out.write("\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun splitsFrames() {
        // 0xFF bytes inside a frame that are not followed by D9 must not end it.
        val a = jpeg(0x01, 0xFF, 0x00, 0x02)
        val b = jpeg(0x10, 0x20)
        val reader = MjpegReader(ByteArrayInputStream(stream(a, b)))
        assertArrayEquals(a, reader.nextFrame())
        assertArrayEquals(b, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test
    fun skipsOversizedFrames() {
        val big = jpeg(*IntArray(100) { 1 })
        val small = jpeg(0x05)
        val reader = MjpegReader(ByteArrayInputStream(stream(big, small)), maxFrameBytes = 50)
        assertArrayEquals(small, reader.nextFrame())
    }

    @Test
    fun endsOnTruncatedFrame() {
        val reader = MjpegReader(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
        assertNull(reader.nextFrame())
    }
}

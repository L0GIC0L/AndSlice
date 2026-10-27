package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/** Answers like OctoPrint. */
private class FakeOctoPrint {
    private val server = FakeHttpServer(::respond)
    val url get() = server.url
    val requests get() = server.requests
    var printerConnected = true

    fun close() = server.close()

    private fun respond(r: FakeHttpServer.Request): Pair<Int, String> {
        // Every request must carry the API key, as OctoPrint requires.
        if (r.headers["x-api-key"] != "secret") return 403 to ""
        return when {
            r.path == "/api/version" -> 200 to """{"api":"0.1","server":"1.10.2","text":"OctoPrint 1.10.2"}"""
            r.path == "/api/printer" && !printerConnected -> 409 to "Printer is not operational"
            r.path == "/api/printer" -> 200 to """{"temperature":{"tool0":{"actual":214.8,"target":215.0},
                "bed":{"actual":60.1,"target":60.0}},"state":{"text":"Printing","flags":{"printing":true,"paused":false}}}"""
            r.path == "/api/job" && r.method == "POST" -> 204 to ""
            r.path == "/api/job" -> 200 to """{"job":{"file":{"name":"benchy.gcode"}},
                "progress":{"completion":42.5,"printTime":600,"printTimeLeft":900},"state":"Printing"}"""
            r.path == "/api/files/local" && r.method == "POST" -> 201 to """{"done":true}"""
            else -> 404 to ""
        }
    }
}

class OctoPrintClientTest {
    private lateinit var server: FakeOctoPrint

    @Before
    fun setUp() {
        server = FakeOctoPrint()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun readsVersion() = runBlocking {
        assertEquals("OctoPrint 1.10.2", OctoPrintClient(server.url, "secret").version().text)
    }

    @Test
    fun explainsRejectedKey() = runBlocking {
        try {
            OctoPrintClient(server.url, "wrong").version()
            fail("expected an error")
        } catch (e: OctoPrintException) {
            assertEquals("OctoPrint rejected the API key.", e.message)
        }
    }

    @Test
    fun parsesStatus() = runBlocking {
        val s = OctoPrintClient(server.url, "secret").status()
        assertEquals("Printing", s.state)
        assertTrue(s.printing)
        assertEquals(214.8, s.hotend!!.actual, 1e-9)
        assertEquals(60.0, s.bed!!.target, 1e-9)
        assertEquals("benchy.gcode", s.fileName)
        assertEquals(42.5, s.completion!!, 1e-9)
        assertEquals(900L, s.printTimeLeftSeconds)
    }

    @Test
    fun statusWithoutConnectedPrinter() = runBlocking {
        server.printerConnected = false
        val s = OctoPrintClient(server.url, "secret").status()
        assertEquals("Printing", s.state) // falls back to /api/job
        assertNull(s.hotend)
    }

    @Test
    fun uploadsMultipartAndStartsPrint() = runBlocking {
        val gcode = File.createTempFile("part", ".gcode").apply { writeText("G28\nG1 X10\n") }
        var lastProgress = 0f
        OctoPrintClient(server.url, "secret").upload(gcode, print = true) { lastProgress = it }
        val r = server.requests.single { it.path == "/api/files/local" }
        assertTrue(r.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        assertTrue(r.body.contains("name=\"print\"\r\n\r\ntrue\r\n"))
        assertTrue(r.body.contains("name=\"select\"\r\n\r\ntrue\r\n"))
        assertTrue(r.body.contains("filename=\"${gcode.name}\""))
        assertTrue(r.body.contains("G28\nG1 X10\n"))
        assertEquals(1f, lastProgress)
        gcode.delete()
        Unit
    }

    @Test
    fun sendsJobCommands() = runBlocking {
        OctoPrintClient(server.url, "secret").cancel()
        assertEquals("""{"command":"cancel"}""", server.requests.single { it.method == "POST" }.body)
    }

    @Test
    fun reportsUnreachableServer() = runBlocking {
        val url = server.url
        server.close()
        try {
            OctoPrintClient(url, "secret").version()
            fail("expected an error")
        } catch (e: OctoPrintException) {
            assertTrue(e.message!!, e.message!!.startsWith("Cannot reach OctoPrint"))
        }
    }

    @Test
    fun normalizesAddresses() {
        assertEquals("http://octopi.local", OctoPrintClient.normalizeUrl(" octopi.local/ "))
        assertEquals("https://printer:5000", OctoPrintClient.normalizeUrl("https://printer:5000"))
    }
}

package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/** Answers like Moonraker on a Kobra 3 with Rinkhals: no API key, Klipper's printer objects. */
private class FakeMoonraker {
    private val server = FakeHttpServer(::respond)
    val url get() = server.url
    val requests get() = server.requests
    var state = "printing"
    var klippy = "ready"
    var webcams = "[]"
    var version = "v0.9.3-1"

    fun close() = server.close()

    private fun respond(r: FakeHttpServer.Request): Pair<Int, String> = when {
        r.path == "/server/info" ->
            200 to """{"result":{"klippy_connected":true,"klippy_state":"$klippy","moonraker_version":"$version"}}"""
        r.path.startsWith("/printer/objects/query") -> 200 to """{"result":{"eventtime":1.0,"status":{
            "print_stats":{"state":"$state","filename":"benchy.gcode","print_duration":600.0},
            "virtual_sdcard":{"progress":0.4},
            "extruder":{"temperature":219.6,"target":220.0},
            "heater_bed":{"temperature":59.9,"target":60.0}}}}"""
        r.path == "/server/files/upload" && r.method == "POST" -> 201 to """{"result":{"item":{"path":"cube.gcode"}}}"""
        r.path == "/printer/print/pause" || r.path == "/printer/print/resume" -> 200 to """{"result":"ok"}"""
        r.path == "/server/webcams/list" -> 200 to """{"result":{"webcams":$webcams}}"""
        r.path.startsWith("/printer/gcode/script") || r.path == "/printer/emergency_stop" -> 200 to """{"result":"ok"}"""
        r.path == "/printer/print/cancel" && state == "standby" ->
            400 to """{"error":{"code":400,"message":"Print is not active"}}"""
        r.path == "/printer/print/cancel" -> 200 to """{"result":"ok"}"""
        else -> 404 to ""
    }
}

class MoonrakerClientTest {
    private lateinit var server: FakeMoonraker

    @Before
    fun setUp() {
        server = FakeMoonraker()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun connectFindsMoonraker() = runBlocking {
        val host = PrinterHost.connect(server.url, "")
        assertTrue(host is MoonrakerClient)
        assertEquals("Moonraker v0.9.3-1", host.version().text)
        assertNull(server.requests.first().headers["x-api-key"])
    }

    @Test
    fun unknownVersionIsLeftOut() = runBlocking {
        server.version = "?"
        assertEquals("Moonraker", PrinterHost.connect(server.url, "").version().text)
    }

    @Test
    fun connectFallsBackToOctoPrint() = runBlocking {
        val octoPrint = FakeHttpServer { r ->
            if (r.path == "/api/version") 200 to """{"api":"0.1","server":"1.10.2","text":"OctoPrint 1.10.2"}""" else 404 to ""
        }
        try {
            val host = PrinterHost.connect(octoPrint.url, "secret")
            assertTrue(host is OctoPrintClient)
            assertEquals("OctoPrint 1.10.2", host.version().text)
        } finally {
            octoPrint.close()
        }
    }

    @Test
    fun readsStatus() = runBlocking {
        val s = MoonrakerClient(server.url, "").status()
        assertEquals("Printing", s.state)
        assertTrue(s.printing)
        assertFalse(s.ready)
        assertEquals(219.6, s.hotend!!.actual, 1e-9)
        assertEquals(60.0, s.bed!!.target, 1e-9)
        assertEquals("benchy.gcode", s.fileName)
        assertEquals(40.0, s.completion!!, 1e-9)
        assertEquals(600L, s.printTimeSeconds)
        assertEquals(900L, s.printTimeLeftSeconds) // 600 s for 40 %
    }

    @Test
    fun reportsKlipperNotReady() = runBlocking {
        server.klippy = "shutdown"
        val s = MoonrakerClient(server.url, "").status()
        assertEquals("Klipper: shutdown", s.state)
        assertFalse(s.ready)
        assertTrue(server.requests.none { it.path.startsWith("/printer/objects") })
    }

    @Test
    fun uploadsAndStartsPrint() = runBlocking {
        val gcode = File.createTempFile("cube", ".gcode").apply { writeText("G28\nG1 X10\n") }
        MoonrakerClient(server.url, "").upload(gcode, print = true)
        val r = server.requests.single { it.path == "/server/files/upload" }
        assertTrue(r.headers["content-type"]!!.startsWith("multipart/form-data"))
        assertTrue(r.body.contains("name=\"root\"\r\n\r\ngcodes"))
        assertTrue(r.body.contains("name=\"print\"\r\n\r\ntrue"))
        assertTrue(r.body.contains("G28\nG1 X10\n"))
    }

    @Test
    fun pausesWhilePrintingAndResumesWhilePaused() = runBlocking {
        MoonrakerClient(server.url, "").pauseOrResume()
        server.state = "paused"
        MoonrakerClient(server.url, "").pauseOrResume()
        val commands = server.requests.filter { it.method == "POST" }.map { it.path }
        assertEquals(listOf("/printer/print/pause", "/printer/print/resume"), commands)
    }

    @Test
    fun showsMoonrakerErrors() = runBlocking {
        server.state = "standby"
        try {
            MoonrakerClient(server.url, "").cancel()
            fail("expected an error")
        } catch (e: OctoPrintException) {
            assertEquals("Print is not active", e.message)
        }
    }

    @Test
    fun findsConfiguredWebcam() = runBlocking {
        server.webcams = """[{"name":"cam","enabled":true,"stream_url":"/webcam/?action=stream",
            "snapshot_url":"/webcam/?action=snapshot","flip_horizontal":true,"rotation":90}]"""
        val cam = MoonrakerClient(server.url, "").webcam()!!
        assertEquals("${server.url}/webcam/?action=stream", cam.streamUrl)
        assertEquals("${server.url}/webcam/?action=snapshot", cam.snapshotUrl)
        assertTrue(cam.flipHorizontal)
        assertEquals(90, cam.rotation)
    }

    @Test
    fun fallsBackToTheUsualWebcamPath() = runBlocking {
        val cam = MoonrakerClient(server.url, "").webcam()!!
        assertEquals("${server.url}/webcam/?action=stream", cam.streamUrl)
    }

    @Test
    fun sendsControlGcode() = runBlocking {
        val client = MoonrakerClient(server.url, "")
        client.setTemperature(Heater.Bed, 60)
        client.home()
        client.jog(x = 10.0)
        client.jog(z = -0.1)
        val scripts = server.requests.filter { it.path.startsWith("/printer/gcode/script") }
            .map { java.net.URLDecoder.decode(it.path.substringAfter("script="), "UTF-8") }
        assertEquals(listOf("M140 S60", "G28", "G91\nG1 X10 F6000\nG90", "G91\nG1 Z-0.1 F600\nG90"), scripts)
    }

    @Test
    fun emergencyStop() = runBlocking {
        MoonrakerClient(server.url, "").emergencyStop()
        assertTrue(server.requests.any { it.method == "POST" && it.path == "/printer/emergency_stop" })
    }
}

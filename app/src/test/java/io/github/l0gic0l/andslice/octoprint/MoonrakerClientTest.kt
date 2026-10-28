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
    var homed = "xyz"
    /** Body of a 400 answer to every G-code script, or null to accept them. */
    var scriptError: String? = null

    fun close() = server.close()

    private fun respond(r: FakeHttpServer.Request): Pair<Int, String> = when {
        r.path == "/server/info" ->
            200 to """{"result":{"klippy_connected":true,"klippy_state":"$klippy","moonraker_version":"$version"}}"""
        r.path.startsWith("/printer/objects/query") -> 200 to """{"result":{"eventtime":1.0,"status":{
            "print_stats":{"state":"$state","filename":"benchy.gcode","print_duration":600.0},
            "virtual_sdcard":{"progress":0.4},
            "extruder":{"temperature":219.6,"target":220.0},
            "heater_bed":{"temperature":59.9,"target":60.0},
            "toolhead":{"position":[100.0,100.0,10.0,0.0],"homed_axes":"$homed",
                "axis_minimum":[0.0,0.0,0.0,0.0],"axis_maximum":[250.0,250.0,250.0,0.0]},
            "gcode_move":{"gcode_position":[100.0,100.0,10.0,0.0]}}}}"""
        r.path == "/server/files/upload" && r.method == "POST" -> 201 to """{"result":{"item":{"path":"cube.gcode"}}}"""
        r.path == "/printer/print/pause" || r.path == "/printer/print/resume" -> 200 to """{"result":"ok"}"""
        r.path == "/server/webcams/list" -> 200 to """{"result":{"webcams":$webcams}}"""
        r.path.startsWith("/printer/gcode/script") && scriptError != null -> 400 to scriptError!!
        r.path.startsWith("/printer/gcode/script") || r.path == "/printer/emergency_stop" -> 200 to """{"result":"ok"}"""
        r.path == "/printer/print/cancel" && state == "standby" ->
            400 to """{"error":{"code":400,"message":"Print is not active"}}"""
        r.path == "/printer/print/cancel" -> 200 to """{"result":"ok"}"""
        r.path == "/server/files/list?root=gcodes" -> 200 to """{"result":[
            {"path":"old.gcode","modified":1000.5,"size":100,"permissions":"rw"},
            {"path":"parts/new part.gcode","modified":2000.0,"size":2000000,"permissions":"rw"},
            {"path":".thumbs/old-32x32.png","modified":1000.0,"size":10,"permissions":"rw"}]}"""
        r.path == "/server/files/metadata?filename=parts%2Fnew+part.gcode" -> 200 to """{"result":{"estimated_time":3725.4,
            "thumbnails":[{"width":32,"height":32,"relative_path":".thumbs/new part-32x32.png"},
            {"width":300,"height":300,"relative_path":".thumbs/new part-300x300.png"},
            {"width":400,"height":400,"relative_path":".thumbs/new part-400x400.png"}]}}"""
        r.path.startsWith("/server/files/metadata") -> 404 to """{"error":{"message":"Metadata not available"}}"""
        r.path.startsWith("/printer/print/start?") -> 200 to """{"result":"ok"}"""
        r.path == "/server/files/gcodes/parts/new%20part.gcode" && r.method == "DELETE" ->
            200 to """{"result":{"action":"delete_file"}}"""
        r.path == "/server/history/list?limit=30&order=desc" -> 200 to """{"result":{"count":2,"jobs":[
            {"filename":"parts/new part.gcode","status":"completed","start_time":1700000000.5,"print_duration":3600.2,"exists":true},
            {"filename":"gone.gcode","status":"cancelled","start_time":1690000000.0,"print_duration":60.0,"exists":false}]}}"""
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
    fun listsPrintsAndDeletesFiles() = runBlocking {
        val client = MoonrakerClient(server.url, "")
        val files = client.files()
        // Newest first, without Moonraker's hidden thumbnail folder.
        assertEquals(listOf("parts/new part.gcode", "old.gcode"), files.map { it.path })
        assertEquals("new part.gcode", files[0].name)
        assertEquals(2000L, files[0].modified)

        val detailed = client.fileDetails(files[0])
        assertTrue(detailed.detailed)
        assertEquals(3725L, detailed.estimatedSeconds)
        // The largest thumbnail up to 300 px, next to the file.
        assertEquals("${server.url}/server/files/gcodes/parts/.thumbs/new%20part-300x300.png", detailed.thumbnailUrl)
        val plain = client.fileDetails(files[1])
        assertTrue(plain.detailed)
        assertNull(plain.thumbnailUrl)

        client.printFile("parts/new part.gcode")
        assertTrue(server.requests.any { it.method == "POST" && it.path == "/printer/print/start?filename=parts%2Fnew+part.gcode" })
        client.deleteFile("parts/new part.gcode")
        assertTrue(server.requests.any { it.method == "DELETE" })
    }

    @Test
    fun readsHistory() = runBlocking {
        val history = MoonrakerClient(server.url, "").history()
        assertEquals(listOf("completed", "cancelled"), history.map { it.status })
        assertEquals("new part.gcode", history[0].name)
        assertEquals(1700000000L, history[0].started)
        assertEquals(3600L, history[0].printSeconds)
        assertFalse(history[1].exists)
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
        // Jogs are absolute moves from where the toolhead is.
        assertEquals(listOf("M140 S60", "G28", "G90", "G1 X110 F6000", "G90", "G1 Z9.9 F600"), scripts)
    }

    @Test
    fun jogsStayWithinTheAxisLimits() = runBlocking {
        MoonrakerClient(server.url, "").jog(x = 500.0, y = -500.0)
        val last = server.requests.last { it.path.startsWith("/printer/gcode/script") }
        assertEquals("G1 X250 Y0 F6000", java.net.URLDecoder.decode(last.path.substringAfter("script="), "UTF-8"))
    }

    @Test
    fun refusesToJogAxesThatAreNotHomed() = runBlocking {
        server.homed = "z"
        try {
            MoonrakerClient(server.url, "").jog(x = 10.0, y = 10.0)
            fail("jogged an axis that is not homed")
        } catch (e: OctoPrintException) {
            assertEquals("Home the printer first: X and Y are not homed.", e.message)
        }
        assertTrue(server.requests.none { it.path.startsWith("/printer/gcode/script") })
    }

    @Test
    fun showsWhatThePrinterSays() = runBlocking {
        val client = MoonrakerClient(server.url, "")
        server.scriptError = """{"error":{"code":400,"message":"Must home axis first: 110.000 100.000 10.000 [0.000]"}}"""
        try {
            client.sendGcode("G1 X110")
            fail("no error")
        } catch (e: OctoPrintException) {
            assertEquals("Must home axis first: 110.000 100.000 10.000 [0.000]", e.message)
        }
        server.scriptError = "Unknown command: G91"
        try {
            client.sendGcode("G91")
            fail("no error")
        } catch (e: OctoPrintException) {
            assertEquals("Unknown command: G91", e.message)
        }
    }

    @Test
    fun emergencyStop() = runBlocking {
        MoonrakerClient(server.url, "").emergencyStop()
        assertTrue(server.requests.any { it.method == "POST" && it.path == "/printer/emergency_stop" })
    }
}

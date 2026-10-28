package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder

/**
 * Client for Moonraker's own API (Klipper printers, including the Kobra 3 with Rinkhals).
 * Moonraker's OctoPrint emulation can upload, but has no job control, so the app talks to
 * Moonraker directly when it finds it.
 */
class MoonrakerClient(baseUrl: String, apiKey: String) : PrinterHost {

    private val http = HttpApi(baseUrl, apiKey, "Moonraker")
    override val baseUrl: String get() = http.baseUrl

    /** True when the server answers like Moonraker. Never throws. */
    suspend fun isMoonraker(): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = http.execute("GET", "/server/info")
            r.code == 200 && JSONObject(r.body).optJSONObject("result")?.has("klippy_state") == true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun version(): OctoPrintVersion = withContext(Dispatchers.IO) {
        val info = result(http.request("GET", "/server/info"))
        val version = info.optString("moonraker_version")
        OctoPrintVersion(server = version, api = "moonraker", text = "Moonraker $version".trim())
    }

    override suspend fun status(): PrinterStatus = withContext(Dispatchers.IO) {
        val info = result(http.request("GET", "/server/info"))
        val klippy = info.optString("klippy_state", "disconnected")
        if (klippy != "ready") {
            // Without a running Klipper there are no printer objects to ask about.
            return@withContext PrinterStatus("Klipper: $klippy", false, false, false, null, null, null, null, null, null)
        }
        val status = result(http.request("GET", "/printer/objects/query?print_stats&virtual_sdcard&extruder&heater_bed"))
            .optJSONObject("status") ?: JSONObject()
        val stats = status.optJSONObject("print_stats")
        val state = stats?.optString("state").orEmpty().ifEmpty { "standby" }
        val printing = state == "printing"
        val paused = state == "paused"
        val active = printing || paused
        val progress = status.optJSONObject("virtual_sdcard")?.optDouble("progress", 0.0) ?: 0.0
        val duration = stats?.optDouble("print_duration", 0.0) ?: 0.0
        PrinterStatus(
            state = state.replaceFirstChar { it.uppercase() },
            printing = printing,
            paused = paused,
            ready = !active,
            hotend = status.optJSONObject("extruder")?.heater(),
            bed = status.optJSONObject("heater_bed")?.heater(),
            fileName = stats?.optString("filename")?.takeIf { it.isNotEmpty() },
            completion = if (active || state == "complete") progress * 100.0 else null,
            printTimeSeconds = if (active) duration.toLong() else null,
            // Moonraker reports no estimate; extrapolate from the progress so far.
            printTimeLeftSeconds = if (active && progress > 0.01) (duration / progress - duration).toLong() else null,
        )
    }

    override suspend fun upload(file: File, print: Boolean, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val fields = listOf("root" to "gcodes", "print" to print.toString())
        val response = http.uploadMultipart("/server/files/upload", fields, file, onProgress)
        if (response.code !in 200..299) throw OctoPrintException(errorMessage(response) ?: "Moonraker answered HTTP ${response.code}.")
    }

    override suspend fun pauseOrResume() = withContext(Dispatchers.IO) {
        val path = if (status().paused) "/printer/print/resume" else "/printer/print/pause"
        command(path)
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) { command("/printer/print/cancel") }

    /**
     * Webcams configured in Moonraker (as Mainsail and Fluidd do), else the usual
     * mjpg-streamer path of Klipper web interfaces, which Rinkhals also serves.
     */
    override suspend fun webcam(): Webcam? = withContext(Dispatchers.IO) {
        // Camera paths are relative to the web interface, which is not on Moonraker's own port.
        val web = webRoot()
        val cams = try {
            result(http.request("GET", "/server/webcams/list")).optJSONArray("webcams")
        } catch (e: OctoPrintException) {
            null
        }
        val cam = cams?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("enabled", true) } }
        if (cam == null) {
            return@withContext Webcam(PrinterHost.resolve(web, "/webcam/?action=stream"), PrinterHost.resolve(web, "/webcam/?action=snapshot"))
        }
        Webcam(
            streamUrl = PrinterHost.resolve(web, cam.optString("stream_url")),
            snapshotUrl = PrinterHost.resolve(web, cam.optString("snapshot_url")),
            flipHorizontal = cam.optBoolean("flip_horizontal"),
            flipVertical = cam.optBoolean("flip_vertical"),
            rotation = cam.optInt("rotation"),
        )
    }

    override suspend fun setTemperature(heater: Heater, celsius: Int) =
        sendGcode(if (heater == Heater.Hotend) "M104 S$celsius" else "M140 S$celsius")

    override suspend fun home() = sendGcode("G28")

    override suspend fun jog(x: Double, y: Double, z: Double) = sendGcode(jogGcode(x, y, z))

    override suspend fun sendGcode(script: String) = withContext(Dispatchers.IO) {
        command("/printer/gcode/script?script=" + URLEncoder.encode(script, "UTF-8"), readTimeoutMs = 180_000)
    }

    override suspend fun emergencyStop() = withContext(Dispatchers.IO) { command("/printer/emergency_stop") }

    /** The web interface's address: the base URL without Moonraker's own port. */
    private fun webRoot(): String {
        val url = URL(baseUrl)
        return if (url.port == PrinterHost.MOONRAKER_PORT) "${url.protocol}://${url.host}/" else "$baseUrl/"
    }

    /** Moonraker answers G-code requests when the commands finished, which takes a while for homing. */
    private fun command(path: String, readTimeoutMs: Int = 10_000) {
        val response = http.execute("POST", path, readTimeoutMs = readTimeoutMs)
        if (response.code !in 200..299) throw OctoPrintException(errorMessage(response) ?: "Moonraker answered HTTP ${response.code}.")
    }

    private fun result(response: HttpApi.Response): JSONObject = JSONObject(response.body).optJSONObject("result") ?: JSONObject()

    /** Moonraker explains failures as {"error": {"message": ...}}. */
    private fun errorMessage(response: HttpApi.Response): String? = try {
        JSONObject(response.body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.heater() = Temperature(optDouble("temperature", 0.0), optDouble("target", 0.0))
}

/** Relative move: G91, one G1 (XY at 100 mm/s, Z at 10 mm/s), back to G90. */
internal fun jogGcode(x: Double, y: Double, z: Double): String {
    fun axis(name: String, v: Double) = if (v != 0.0) " $name${"%.3f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')}" else ""
    val feed = if (z != 0.0 && x == 0.0 && y == 0.0) 600 else 6000
    return "G91\nG1${axis("X", x)}${axis("Y", y)}${axis("Z", z)} F$feed\nG90"
}

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
        // Rinkhals reports "?" for its Moonraker version.
        val version = info.optString("moonraker_version").takeUnless { it == "?" }.orEmpty()
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
            message = stats?.optString("message")?.takeIf { it.isNotBlank() },
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

    /**
     * Moves to an absolute position worked out from where the toolhead is, rather than with a
     * relative G91 move, which the Kobra 3's firmware answers with HTTP 400. Axes that are not
     * homed cannot move, so those are refused with a clear message instead.
     */
    override suspend fun jog(x: Double, y: Double, z: Double) = withContext(Dispatchers.IO) {
        val status = result(
            http.request("GET", "/printer/objects/query?toolhead=position,homed_axes,axis_minimum,axis_maximum&gcode_move=gcode_position")
        ).optJSONObject("status")
        val toolhead = status?.optJSONObject("toolhead")
        val deltas = listOf("X" to x, "Y" to y, "Z" to z)
        if (toolhead != null && toolhead.has("homed_axes")) {
            val homed = toolhead.optString("homed_axes").uppercase()
            val missing = deltas.filter { (axis, d) -> d != 0.0 && axis !in homed }.map { it.first }
            if (missing.isNotEmpty()) {
                throw OctoPrintException("Home the printer first: ${missing.joinToString(" and ")} ${if (missing.size == 1) "is" else "are"} not homed.")
            }
        }
        val position = status?.optJSONObject("gcode_move")?.optJSONArray("gcode_position")
            ?: toolhead?.optJSONArray("position")
            ?: throw OctoPrintException("Moonraker did not report where the toolhead is.")
        val min = toolhead?.optJSONArray("axis_minimum")
        val max = toolhead?.optJSONArray("axis_maximum")
        val targets = deltas.mapIndexedNotNull { i, (axis, d) ->
            if (d == 0.0) return@mapIndexedNotNull null
            var t = position.getDouble(i) + d
            if (min != null && max != null && min.length() > i && max.length() > i) t = t.coerceIn(min.getDouble(i), max.getDouble(i))
            axis to t
        }
        if (targets.isEmpty()) return@withContext
        // One command per request, and absolute mode first, so a refused move leaves no relative mode behind.
        command(scriptPath("G90"), readTimeoutMs = 60_000)
        command(scriptPath(moveGcode(targets, feed = if (z != 0.0 && x == 0.0 && y == 0.0) 600 else 6000)), readTimeoutMs = 60_000)
    }

    override suspend fun sendGcode(script: String) = withContext(Dispatchers.IO) {
        command(scriptPath(script), readTimeoutMs = 180_000)
    }

    private fun scriptPath(script: String) = "/printer/gcode/script?script=" + URLEncoder.encode(script, "UTF-8")

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

    /**
     * Moonraker explains failures as {"error": {"message": ...}}; other servers in front of the
     * printer may answer with a plain string or text, which is shown as it is.
     */
    private fun errorMessage(response: HttpApi.Response): String? {
        val body = response.body.trim()
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
        val message = when {
            json == null -> body
            json.optJSONObject("error") != null -> json.getJSONObject("error").optString("message")
            json.has("error") -> json.optString("error")
            else -> json.optString("message")
        }
        return message.takeIf { it.isNotBlank() }?.take(200)
    }

    private fun JSONObject.heater() = Temperature(optDouble("temperature", 0.0), optDouble("target", 0.0))
}

/** "G1 X110 Z9.9 F6000" for the given absolute axis targets. */
internal fun moveGcode(targets: List<Pair<String, Double>>, feed: Int): String =
    "G1" + targets.joinToString("") { (axis, v) -> " $axis${"%.3f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')}" } + " F$feed"

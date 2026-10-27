package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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

    private fun command(path: String) {
        val response = http.execute("POST", path)
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

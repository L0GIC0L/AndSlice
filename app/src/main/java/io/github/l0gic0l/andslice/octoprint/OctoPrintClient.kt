package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection

/** Failure talking to the print server, with a message meant for the user. */
class OctoPrintException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class OctoPrintVersion(val server: String, val api: String, val text: String)

data class Temperature(val actual: Double, val target: Double)

data class PrinterStatus(
    /** OctoPrint's state text, e.g. "Operational", "Printing", "Offline". */
    val state: String,
    val printing: Boolean,
    val paused: Boolean,
    val ready: Boolean,
    val hotend: Temperature?,
    val bed: Temperature?,
    val fileName: String?,
    /** 0..100, or null when no job is loaded. */
    val completion: Double?,
    val printTimeSeconds: Long?,
    val printTimeLeftSeconds: Long?,
    /** What the server says about the job, such as why it failed. */
    val message: String? = null,
)

/**
 * Minimal client for OctoPrint's REST API: connection check, status, upload and job control.
 */
class OctoPrintClient(baseUrl: String, apiKey: String) : PrinterHost {

    private val http = HttpApi(baseUrl, apiKey, "OctoPrint")
    override val baseUrl: String get() = http.baseUrl

    override suspend fun version(): OctoPrintVersion = withContext(Dispatchers.IO) {
        val o = JSONObject(http.request("GET", "/api/version").body)
        OctoPrintVersion(o.optString("server"), o.optString("api"), o.optString("text", "OctoPrint"))
    }

    override suspend fun status(): PrinterStatus = withContext(Dispatchers.IO) {
        val job = JSONObject(http.request("GET", "/api/job").body)
        // /api/printer answers 409 while no printer is connected.
        val printer = http.execute("GET", "/api/printer")
        if (printer.code != HttpURLConnection.HTTP_CONFLICT) http.check(printer)
        val printerJson = if (printer.code == HttpURLConnection.HTTP_CONFLICT) null else JSONObject(printer.body)

        val flags = printerJson?.optJSONObject("state")?.optJSONObject("flags")
        val temps = printerJson?.optJSONObject("temperature")
        val progress = job.optJSONObject("progress")
        val file = job.optJSONObject("job")?.optJSONObject("file")
        PrinterStatus(
            state = printerJson?.optJSONObject("state")?.optString("text") ?: job.optString("state", "Offline"),
            printing = flags?.optBoolean("printing") ?: false,
            paused = flags?.optBoolean("paused") ?: false,
            ready = flags?.optBoolean("ready") ?: false,
            hotend = temps?.optJSONObject("tool0")?.toTemperature(),
            bed = temps?.optJSONObject("bed")?.toTemperature(),
            fileName = file?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" },
            completion = progress?.optNullableDouble("completion"),
            printTimeSeconds = progress?.optNullableDouble("printTime")?.toLong(),
            printTimeLeftSeconds = progress?.optNullableDouble("printTimeLeft")?.toLong(),
        )
    }

    /**
     * Uploads [file] to OctoPrint's local storage. With [print] the file is
     * selected and printing starts right away. [onProgress] receives 0..1.
     */
    override suspend fun upload(file: File, print: Boolean, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val fields = listOf("select" to print.toString(), "print" to print.toString())
        val response = http.uploadMultipart("/api/files/local", fields, file, onProgress)
        if (response.code == HttpURLConnection.HTTP_CONFLICT) {
            throw OctoPrintException("The printer is busy or not connected, so the print could not start.")
        }
        http.check(response)
    }

    override suspend fun files(): List<PrinterFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PrinterFile>()
        fun collect(entries: JSONArray?) {
            for (i in 0 until (entries?.length() ?: 0)) {
                val f = entries!!.getJSONObject(i)
                if (f.optString("type") == "folder") {
                    collect(f.optJSONArray("children"))
                } else if (f.optString("type") == "machinecode") {
                    out += PrinterFile(
                        path = f.optString("path"),
                        size = f.optLong("size"),
                        modified = f.optLong("date"),
                        estimatedSeconds = f.optJSONObject("gcodeAnalysis")?.optNullableDouble("estimatedPrintTime")?.toLong(),
                        // Set by the PrusaSlicer Thumbnails plugin.
                        thumbnailUrl = PrinterHost.resolve("$baseUrl/", f.optString("thumbnail").takeIf { it.isNotEmpty() && it != "null" }),
                        detailed = true,
                    )
                }
            }
        }
        collect(JSONObject(http.request("GET", "/api/files/local?recursive=true").body).optJSONArray("files"))
        out.sortedByDescending { it.modified }
    }

    override suspend fun printFile(path: String) {
        val response = withContext(Dispatchers.IO) {
            val bytes = JSONObject().put("command", "select").put("print", true).toString().toByteArray()
            http.execute("POST", "/api/files/local/" + encodePath(path), "application/json", bytes.size.toLong()) { it.write(bytes) }
        }
        if (response.code == HttpURLConnection.HTTP_CONFLICT) throw OctoPrintException("The printer is busy or not connected.")
        http.check(response)
    }

    override suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        http.check(http.execute("DELETE", "/api/files/local/" + encodePath(path)))
    }

    override suspend fun pauseOrResume() = jobCommand(JSONObject().put("command", "pause").put("action", "toggle"))

    override suspend fun cancel() = jobCommand(JSONObject().put("command", "cancel"))

    private suspend fun jobCommand(body: JSONObject) = post("/api/job", body)

    /** The classic webcam settings (OctoPrint's bundled MJPEG webcam). */
    override suspend fun webcam(): Webcam? = withContext(Dispatchers.IO) {
        val cam = JSONObject(http.request("GET", "/api/settings").body).optJSONObject("webcam") ?: return@withContext null
        if (!cam.optBoolean("webcamEnabled", true)) return@withContext null
        val base = "$baseUrl/"
        val stream = PrinterHost.resolve(base, cam.optString("streamUrl"))
        val snapshot = PrinterHost.resolve(base, cam.optString("snapshotUrl"))
        if (stream == null && snapshot == null) return@withContext null
        Webcam(stream, snapshot, cam.optBoolean("flipH"), cam.optBoolean("flipV"), if (cam.optBoolean("rotate90")) 270 else 0)
    }

    override suspend fun setTemperature(heater: Heater, celsius: Int) = when (heater) {
        Heater.Hotend -> post("/api/printer/tool", JSONObject().put("command", "target").put("targets", JSONObject().put("tool0", celsius)))
        Heater.Bed -> post("/api/printer/bed", JSONObject().put("command", "target").put("target", celsius))
    }

    override suspend fun home() =
        post("/api/printer/printhead", JSONObject().put("command", "home").put("axes", JSONArray(listOf("x", "y", "z"))))

    override suspend fun jog(x: Double, y: Double, z: Double) =
        post("/api/printer/printhead", JSONObject().put("command", "jog").put("x", x).put("y", y).put("z", z))

    override suspend fun sendGcode(script: String) =
        post("/api/printer/command", JSONObject().put("commands", JSONArray(script.lines().filter { it.isNotBlank() })))

    override suspend fun emergencyStop() = sendGcode("M112")

    private suspend fun post(path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        val bytes = body.toString().toByteArray()
        http.check(http.execute("POST", path, "application/json", bytes.size.toLong()) { it.write(bytes) })
    }

    companion object {
        /** "octopi.local" -> "http://octopi.local"; drops a trailing slash. */
        fun normalizeUrl(url: String): String = HttpApi.normalizeUrl(url)
    }
}

internal fun JSONObject.toTemperature() = Temperature(optDouble("actual", 0.0), optDouble("target", 0.0))

internal fun JSONObject.optNullableDouble(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }

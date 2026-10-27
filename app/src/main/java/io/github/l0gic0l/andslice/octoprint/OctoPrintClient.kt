package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun pauseOrResume() = jobCommand(JSONObject().put("command", "pause").put("action", "toggle"))

    override suspend fun cancel() = jobCommand(JSONObject().put("command", "cancel"))

    private suspend fun jobCommand(body: JSONObject) = withContext(Dispatchers.IO) {
        val bytes = body.toString().toByteArray()
        http.check(http.execute("POST", "/api/job", "application/json", bytes.size.toLong()) { it.write(bytes) })
    }

    companion object {
        /** "octopi.local" -> "http://octopi.local"; drops a trailing slash. */
        fun normalizeUrl(url: String): String = HttpApi.normalizeUrl(url)
    }
}

internal fun JSONObject.toTemperature() = Temperature(optDouble("actual", 0.0), optDouble("target", 0.0))

internal fun JSONObject.optNullableDouble(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }

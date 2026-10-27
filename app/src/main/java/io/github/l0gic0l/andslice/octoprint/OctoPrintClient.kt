package io.github.l0gic0l.andslice.octoprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

/** Failure talking to OctoPrint, with a message meant for the user. */
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
 * Minimal client for OctoPrint's REST API (also served by Moonraker's
 * OctoPrint compatibility layer): connection check, status, upload and job control.
 */
class OctoPrintClient(baseUrl: String, private val apiKey: String) {

    val baseUrl: String = normalizeUrl(baseUrl)

    suspend fun version(): OctoPrintVersion = withContext(Dispatchers.IO) {
        val o = JSONObject(request("GET", "/api/version").body)
        OctoPrintVersion(o.optString("server"), o.optString("api"), o.optString("text", "OctoPrint"))
    }

    suspend fun status(): PrinterStatus = withContext(Dispatchers.IO) {
        val job = JSONObject(request("GET", "/api/job").body)
        // /api/printer answers 409 while no printer is connected.
        val printer = request("GET", "/api/printer", allowConflict = true)
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
    suspend fun upload(file: File, print: Boolean, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val boundary = "AndSlice-" + UUID.randomUUID()
        val head = buildString {
            for ((name, value) in listOf("select" to print.toString(), "print" to print.toString())) {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                append("$value\r\n")
            }
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val total = head.size + file.length() + tail.size

        val response = execute("POST", "/api/files/local", contentType = "multipart/form-data; boundary=$boundary",
            contentLength = total, readTimeoutMs = 120_000) { out ->
            var sent = 0L
            out.write(head)
            sent += head.size
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    sent += n
                    onProgress(sent.toFloat() / total)
                }
            }
            out.write(tail)
            onProgress(1f)
        }
        if (response.code == HttpURLConnection.HTTP_CONFLICT) {
            throw OctoPrintException("The printer is busy or not connected, so the print could not start.")
        }
        check(response)
    }

    suspend fun pauseOrResume() = jobCommand(JSONObject().put("command", "pause").put("action", "toggle"))

    suspend fun cancel() = jobCommand(JSONObject().put("command", "cancel"))

    private suspend fun jobCommand(body: JSONObject) = withContext(Dispatchers.IO) {
        val bytes = body.toString().toByteArray()
        check(execute("POST", "/api/job", "application/json", bytes.size.toLong()) { it.write(bytes) })
    }

    // ------------------------------------------------------------------ HTTP

    private class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, allowConflict: Boolean = false): Response {
        val response = execute(method, path)
        if (allowConflict && response.code == HttpURLConnection.HTTP_CONFLICT) return response
        check(response)
        return response
    }

    private fun check(response: Response) {
        when (response.code) {
            in 200..299 -> Unit
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                throw OctoPrintException("OctoPrint rejected the API key.")
            HttpURLConnection.HTTP_NOT_FOUND ->
                throw OctoPrintException("No OctoPrint API at $baseUrl.")
            HttpURLConnection.HTTP_CONFLICT ->
                throw OctoPrintException("The printer is not ready.")
            else -> throw OctoPrintException("OctoPrint answered HTTP ${response.code}.")
        }
    }

    private fun execute(
        method: String,
        path: String,
        contentType: String? = null,
        contentLength: Long = 0,
        readTimeoutMs: Int = 10_000,
        writeBody: ((java.io.OutputStream) -> Unit)? = null,
    ): Response {
        val connection = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw OctoPrintException("\"$baseUrl\" is not a valid address.", e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("X-Api-Key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            if (writeBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(contentLength)
                connection.outputStream.use(writeBody)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, body)
        } catch (e: UnknownHostException) {
            throw OctoPrintException("Cannot find ${connection.url.host} on the network.", e)
        } catch (e: SocketTimeoutException) {
            throw OctoPrintException("OctoPrint at $baseUrl did not answer in time.", e)
        } catch (e: IOException) {
            throw OctoPrintException("Cannot reach OctoPrint at $baseUrl (${e.message}).", e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** "octopi.local" -> "http://octopi.local"; drops a trailing slash. */
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "http://$trimmed"
        }
    }
}

private fun JSONObject.toTemperature() = Temperature(optDouble("actual", 0.0), optDouble("target", 0.0))

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }

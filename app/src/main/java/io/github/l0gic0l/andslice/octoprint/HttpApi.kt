package io.github.l0gic0l.andslice.octoprint

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

/**
 * Blocking JSON-over-HTTP helper shared by the OctoPrint and Moonraker clients.
 * Errors become [OctoPrintException]s with messages for the user; [server] names
 * the kind of server in them.
 */
internal class HttpApi(baseUrl: String, private val apiKey: String, private val server: String) {

    val baseUrl: String = normalizeUrl(baseUrl)

    class Response(val code: Int, val body: String)

    /** Sends the request and fails unless the answer is 2xx. */
    fun request(method: String, path: String): Response = execute(method, path).also { check(it) }

    fun check(response: Response) {
        when (response.code) {
            in 200..299 -> Unit
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                throw OctoPrintException("$server rejected the API key.")
            HttpURLConnection.HTTP_NOT_FOUND ->
                throw OctoPrintException("No $server API at $baseUrl.")
            HttpURLConnection.HTTP_CONFLICT ->
                throw OctoPrintException("The printer is not ready.")
            else -> throw OctoPrintException("$server answered HTTP ${response.code}.")
        }
    }

    /** POSTs a multipart form with [fields] and [file] as "file", streaming the file. [onProgress] gets 0..1. */
    fun uploadMultipart(path: String, fields: List<Pair<String, String>>, file: File, onProgress: (Float) -> Unit): Response {
        val boundary = "AndSlice-" + UUID.randomUUID()
        val head = buildString {
            for ((name, value) in fields) {
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

        return execute("POST", path, contentType = "multipart/form-data; boundary=$boundary",
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
    }

    fun execute(
        method: String,
        path: String,
        contentType: String? = null,
        contentLength: Long = 0,
        readTimeoutMs: Int = 10_000,
        writeBody: ((OutputStream) -> Unit)? = null,
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
            if (apiKey.isNotEmpty()) connection.setRequestProperty("X-Api-Key", apiKey)
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
            throw OctoPrintException("$server at $baseUrl did not answer in time.", e)
        } catch (e: IOException) {
            throw OctoPrintException("Cannot reach $server at $baseUrl (${e.message}).", e)
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

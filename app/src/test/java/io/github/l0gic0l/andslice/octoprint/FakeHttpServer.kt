package io.github.l0gic0l.andslice.octoprint

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** Minimal HTTP/1.1 server for client tests, one request per connection. */
class FakeHttpServer(private val respond: (Request) -> Pair<Int, String>) {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests = mutableListOf<Request>()

    init {
        thread(isDaemon = true) {
            while (true) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                client.use { c ->
                    val request = read(c.getInputStream())
                    synchronized(requests) { requests.add(request) }
                    val (code, body) = respond(request)
                    val bytes = body.toByteArray()
                    val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    fun close() = socket.close()

    private fun read(input: InputStream): Request {
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString(Charsets.UTF_8.name())
        }
        val (method, path) = line().split(' ').let { it[0] to it[1] }
        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) read += input.read(body, read, length - read).also { if (it < 0) error("short body") }
        return Request(method, path, headers, body.decodeToString())
    }
}

package io.github.l0gic0l.andslice.octoprint

import java.net.URLEncoder

/** A G-code file stored on the printer. */
data class PrinterFile(
    /** Path on the printer, relative to its G-code folder. */
    val path: String,
    val size: Long,
    /** Last change, in seconds since 1970. */
    val modified: Long,
    /** Print time the slicer estimated, if the server knows it. */
    val estimatedSeconds: Long? = null,
    /** Absolute address of a preview image, if the file has one. */
    val thumbnailUrl: String? = null,
    /** Whether the estimate and thumbnail were looked up; Moonraker sends them per file. */
    val detailed: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** A print from the server's history. */
data class PrintRecord(
    val path: String,
    /** Moonraker's job status: "completed", "cancelled", "error", "in_progress", "klippy_shutdown"… */
    val status: String,
    /** Start, in seconds since 1970. */
    val started: Long,
    val printSeconds: Long,
    /** The file is still on the printer, so it can be printed again. */
    val exists: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** A path for a URL: each folder and file name percent-encoded, slashes kept. */
internal fun encodePath(path: String): String =
    path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

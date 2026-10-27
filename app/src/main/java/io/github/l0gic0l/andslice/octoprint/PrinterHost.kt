package io.github.l0gic0l.andslice.octoprint

import java.io.File
import java.net.URL

/** A print server the app sends G-code to: OctoPrint, or Moonraker (Klipper, such as Rinkhals on the Kobra 3). */
interface PrinterHost {
    val baseUrl: String

    suspend fun version(): OctoPrintVersion
    suspend fun status(): PrinterStatus

    /** Uploads [file]; with [print] the print starts right away. [onProgress] receives 0..1. */
    suspend fun upload(file: File, print: Boolean, onProgress: (Float) -> Unit = {})
    suspend fun pauseOrResume()
    suspend fun cancel()

    companion object {
        /**
         * Finds the server behind [url]: Moonraker if it answers there (or on its own port 7125
         * when the address has no port), otherwise OctoPrint.
         */
        suspend fun connect(url: String, apiKey: String): PrinterHost {
            val base = HttpApi.normalizeUrl(url)
            val candidates = listOf(base) + if (hasPort(base)) emptyList() else listOf("$base:$MOONRAKER_PORT")
            for (candidate in candidates) {
                val moonraker = MoonrakerClient(candidate, apiKey)
                if (moonraker.isMoonraker()) return moonraker
            }
            return OctoPrintClient(base, apiKey)
        }

        const val MOONRAKER_PORT = 7125

        private fun hasPort(url: String) = try {
            URL(url).port != -1
        } catch (e: Exception) {
            true
        }
    }
}

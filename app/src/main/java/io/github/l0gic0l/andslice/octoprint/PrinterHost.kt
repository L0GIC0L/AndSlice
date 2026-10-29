package io.github.l0gic0l.andslice.octoprint

import java.io.File
import java.net.URI
import java.net.URL

/** A camera of the printer: an MJPEG stream and/or a JPEG snapshot URL, both absolute. */
data class Webcam(
    val streamUrl: String?,
    val snapshotUrl: String?,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Clockwise, in degrees: 0, 90, 180 or 270. */
    val rotation: Int = 0,
)

enum class Heater { Hotend, Bed }

/** A print server the app sends G-code to: OctoPrint, or Moonraker (Klipper, such as Rinkhals on the Kobra 3). */
interface PrinterHost {
    val baseUrl: String

    suspend fun version(): OctoPrintVersion
    suspend fun status(): PrinterStatus

    /** Uploads [file]; with [print] the print starts right away. [onProgress] receives 0..1. */
    suspend fun upload(file: File, print: Boolean, onProgress: (Float) -> Unit = {})
    suspend fun pauseOrResume()
    suspend fun cancel()

    /** G-code files on the printer, newest first. */
    suspend fun files(): List<PrinterFile>
    /** [file] with its estimate and thumbnail filled in, where the server sends those per file. */
    suspend fun fileDetails(file: PrinterFile): PrinterFile = file.copy(detailed = true)
    /** Starts printing a file that is on the printer. */
    suspend fun printFile(path: String)
    suspend fun deleteFile(path: String)
    /** Recent prints, newest first; empty when the server keeps no history. */
    suspend fun history(): List<PrintRecord> = emptyList()

    /** What the printer's filament changer (the ACE Pro) holds, or null when the server does not say. */
    suspend fun filamentSlots(): List<FilamentSlot>? = null

    /** Address of the server's web interface, to show in a browser. */
    suspend fun webInterface(): String = "$baseUrl/"

    /** The printer's camera as the server knows it, or null. */
    suspend fun webcam(): Webcam?
    suspend fun setTemperature(heater: Heater, celsius: Int)
    suspend fun home()
    /** Relative move of the print head in mm. */
    suspend fun jog(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0)
    /** Sends G-code lines to the printer. */
    suspend fun sendGcode(script: String)
    suspend fun emergencyStop()

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

        /** Resolves [url] against [base]; relative camera paths belong to the web server. */
        fun resolve(base: String, url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                URI(base).resolve(url.trim()).toString()
            } catch (e: Exception) {
                null
            }
        }

        private fun hasPort(url: String) = try {
            URL(url).port != -1
        } catch (e: Exception) {
            true
        }
    }
}

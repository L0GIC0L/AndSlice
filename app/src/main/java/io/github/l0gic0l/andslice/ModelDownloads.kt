package io.github.l0gic0l.andslice

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipFile

/** A file a web page wants to download, as the WebView reports it. */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val userAgent: String,
    val referer: String?,
)

/** Model downloads from the browser: fetched with the page's cookies, kept in Downloads, then put on the plate. */
object ModelDownloads {
    /** Where downloaded models are kept, for opening them again later. */
    const val FOLDER = "AndSlice"

    /** A cache folder for one download. */
    fun newDir(context: Context): File = File(context.cacheDir, "models/${UUID.randomUUID()}").apply { mkdirs() }

    /**
     * Downloads [request] into [dir] with the browser's cookies, so sites that need a login work.
     * [onProgress] gets 0..1, or -1 when the size is unknown. Returns the file, named for what it holds.
     */
    fun download(request: DownloadRequest, dir: File, onProgress: (Float) -> Unit): File {
        var url = URL(request.url)
        var connection: HttpURLConnection
        var redirects = 0
        while (true) {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", request.userAgent)
                CookieManager.getInstance().getCookie(url.toString())?.let { setRequestProperty("Cookie", it) }
                request.referer?.let { setRequestProperty("Referer", it) }
            }
            val code = connection.responseCode
            // Redirects are followed by hand, since HttpURLConnection does not follow http to https.
            if (code in 300..399 && redirects++ < 8) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                url = URL(url, location)
                continue
            }
            break
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("The site answered HTTP ${connection.responseCode}.")
            val file = File(dir, safeName(request.fileName))
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            return named(file)
        } finally {
            connection.disconnect()
        }
    }

    /** Writes the bytes of a page's blob download into [dir]. */
    fun saveBytes(bytes: ByteArray, fileName: String, dir: File): File =
        named(File(dir, safeName(fileName)).apply { writeBytes(bytes) })

    /**
     * Gives a download without a model extension one from its content: a zip with a 3MF model
     * inside is a 3MF, another zip an archive, text starting with "solid" an ASCII STL.
     * Other files are refused.
     */
    fun named(file: File): File {
        if (ModelFiles.isModel(file.name) || ModelFiles.isArchive(file.name)) return file
        val head = file.inputStream().use { input -> ByteArray(5).also { input.read(it) } }
        val extension = when {
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                if (try { ZipFile(file).use { it.getEntry("3D/3dmodel.model") != null } } catch (e: IOException) { false }) "3mf" else "zip"
            String(head, Charsets.US_ASCII).equals("solid", ignoreCase = true) -> "stl"
            else -> throw IOException("${file.name} is not an STL, 3MF, OBJ or zip file.")
        }
        val renamed = File(file.parentFile, file.nameWithoutExtension + "." + extension)
        if (!file.renameTo(renamed)) throw IOException("Cannot rename ${file.name}.")
        return renamed
    }

    /**
     * Keeps a copy in Downloads/AndSlice, where file managers and the import button find it.
     * Returns where it went, or null when that failed.
     */
    fun keep(context: Context, file: File): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("No Downloads folder")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            "Downloads/$FOLDER"
        } else {
            // Android 9: the app's own Downloads folder needs no permission.
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("No storage")
            file.copyTo(File(dir, file.name), overwrite = true)
            dir.path
        }
    } catch (e: Exception) {
        null
    }

    /** A file name without folders or characters file systems refuse. */
    fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim().trimStart('.').ifEmpty { "model" }
}

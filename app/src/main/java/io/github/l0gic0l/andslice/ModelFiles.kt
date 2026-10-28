package io.github.l0gic0l.andslice

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Files the plate is loaded from: models, zip archives of models, and 3MF projects with their settings. */
object ModelFiles {
    val MODEL_EXTENSIONS = setOf("stl", "3mf", "obj", "amf")

    fun isModel(name: String) = name.substringAfterLast('.', "").lowercase() in MODEL_EXTENSIONS

    fun isArchive(name: String) = name.endsWith(".zip", ignoreCase = true)

    /** A 3MF with slicer settings, as PrusaSlicer and AndSlice save their projects. */
    fun isProject(file: File): Boolean = file.extension.equals("3mf", ignoreCase = true) && try {
        ZipFile(file).use { it.getEntry(PROJECT_CONFIG) != null }
    } catch (e: IOException) {
        false
    }

    /** A model unpacked from an archive, with its path inside the archive. */
    data class Unpacked(val path: String, val file: File)

    /**
     * Unpacks the models of a zip archive into [dir], in archive order. Hidden files, macOS
     * metadata and entries that would land outside [dir] are skipped.
     */
    fun extractModels(archive: File, dir: File): List<Unpacked> {
        val root = dir.canonicalFile
        val out = mutableListOf<Unpacked>()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val path = entry.name
                if (entry.isDirectory || !isModel(path)) continue
                if (path.split('/', '\\').any { it.startsWith(".") || it == "__MACOSX" }) continue
                val target = File(root, path).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) continue
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                out += Unpacked(path, target)
            }
        }
        return out
    }

    /**
     * The values of a PrusaSlicer string list option, such as `"Anycubic PLA @Kobra 3";Generic`:
     * strings with spaces or special characters are quoted with C-style escapes.
     */
    fun parseStrings(serialized: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val s = serialized
        while (i < s.length) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (i == s.length) break
            val value = StringBuilder()
            if (s[i] == '"') {
                i++
                while (i < s.length && s[i] != '"') {
                    var c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        i++
                        c = when (s[i]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> s[i]
                        }
                    }
                    value.append(c)
                    i++
                }
                i++ // the closing quote
                while (i < s.length && s[i] != ';') i++
            } else {
                while (i < s.length && s[i] != ';') value.append(s[i++])
            }
            out += value.toString()
            i++ // the separator
        }
        return out
    }

    /**
     * The settings of a [project] that differ from the [presets] it is opened with: as overrides,
     * they restore the project's settings. Only keys [editable] in the app count; preset names,
     * slot colors and print host details are left out.
     */
    fun projectOverrides(project: Map<String, String>, presets: Map<String, String>, editable: (String) -> Boolean): Map<String, String> =
        project.filter { (key, value) ->
            key !in NOT_OVERRIDES && editable(key) && presets[key] != null && presets[key] != value
        }

    const val PROJECT_CONFIG = "Metadata/Slic3r_PE.config"

    private val NOT_OVERRIDES = setOf(
        "inherits", "compatible_printers", "compatible_printers_condition", "compatible_prints",
        "compatible_prints_condition", "print_settings_id", "filament_settings_id", "printer_settings_id",
        "filament_colour", "extruder_colour", "print_host", "printhost_apikey", "printhost_cafile", "post_process",
    )
}

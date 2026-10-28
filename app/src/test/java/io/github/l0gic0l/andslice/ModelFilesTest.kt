package io.github.l0gic0l.andslice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(name: String, vararg entries: String): File = temp.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            for (entry in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write("solid $entry\nendsolid\n".toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun readsPrusaSlicerStringLists() {
        assertEquals(listOf("Anycubic PLA @Kobra 3", "Generic"), ModelFiles.parseStrings("\"Anycubic PLA @Kobra 3\";Generic"))
        assertEquals(listOf("#F2F2F2", "#2B2B2B"), ModelFiles.parseStrings("#F2F2F2;#2B2B2B"))
        assertEquals(listOf("say \"hi\"\nnow"), ModelFiles.parseStrings("\"say \\\"hi\\\"\\nnow\""))
        assertEquals(listOf(""), ModelFiles.parseStrings("\"\""))
        assertEquals(emptyList<String>(), ModelFiles.parseStrings(""))
    }

    @Test
    fun overridesAreTheProjectsChanges() {
        val presets = mapOf("fill_density" to "15%", "perimeters" to "2", "print_settings_id" to "A", "layer_height" to "0.2")
        val project = mapOf(
            "fill_density" to "40%", "perimeters" to "2", "print_settings_id" to "B",
            "layer_height" to "0.2", "unknown_key" to "1",
        )
        assertEquals(
            mapOf("fill_density" to "40%"),
            ModelFiles.projectOverrides(project, presets) { it != "unknown_key" },
        )
    }

    @Test
    fun unpacksOnlyModelsInsideTheFolder() {
        val archive = zip(
            "parts.zip",
            "files/body.stl", "files/lid.3MF", "README.txt", "__MACOSX/files/._body.stl", ".hidden.stl", "../escape.stl",
        )
        val dir = temp.newFolder("out")
        val models = ModelFiles.extractModels(archive, dir)
        assertEquals(listOf("files/body.stl", "files/lid.3MF"), models.map { it.path })
        assertTrue(models.all { it.file.isFile && it.file.canonicalPath.startsWith(dir.canonicalPath) })
        assertFalse(File(dir.parentFile, "escape.stl").exists())
    }

    @Test
    fun projectsHaveSettings() {
        assertTrue(ModelFiles.isProject(zip("project.3mf", "3D/3dmodel.model", ModelFiles.PROJECT_CONFIG)))
        assertFalse(ModelFiles.isProject(zip("model.3mf", "3D/3dmodel.model")))
        assertFalse(ModelFiles.isProject(temp.newFile("broken.3mf")))
        assertTrue(ModelFiles.isArchive("Thing.ZIP"))
        assertTrue(ModelFiles.isModel("a.Stl"))
        assertFalse(ModelFiles.isModel("a.gcode"))
    }
}

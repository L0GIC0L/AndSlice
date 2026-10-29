package io.github.l0gic0l.andslice

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloadsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zipWith(name: String, entry: String): File = temp.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(entry))
            out.write("x".toByteArray())
            out.closeEntry()
        }
    }

    @Test
    fun namesDownloadsByWhatTheyHold() {
        assertEquals("downloadfile.3mf", ModelDownloads.named(zipWith("downloadfile.bin", "3D/3dmodel.model")).name)
        assertEquals("files.zip", ModelDownloads.named(zipWith("files", "body.stl")).name)
        assertEquals("part.stl", ModelDownloads.named(temp.newFile("part").apply { writeText("solid part\nendsolid\n") }).name)
        // Model files keep their names.
        assertEquals("cube.STL", ModelDownloads.named(temp.newFile("cube.STL").apply { writeBytes(ByteArray(84)) }).name)
        try {
            ModelDownloads.named(temp.newFile("page.html").apply { writeText("<html></html>") })
            fail("a web page is not a model")
        } catch (e: IOException) {
            assertEquals("page.html is not an STL, 3MF, OBJ or zip file.", e.message)
        }
    }

    @Test
    fun keepsFileNamesSafe() {
        assertEquals("evil_stl", ModelDownloads.safeName("../../evil:stl"))
        assertEquals("hidden.stl", ModelDownloads.safeName(".hidden.stl"))
        assertEquals("model", ModelDownloads.safeName("   "))
        assertEquals("Benchy v2.3mf", ModelDownloads.safeName("Benchy v2.3mf"))
    }
}

package io.github.l0gic0l.andslice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.l0gic0l.andslice.engine.NativeEngine
import io.github.l0gic0l.andslice.engine.OptionDefs
import io.github.l0gic0l.andslice.engine.PlateObject
import io.github.l0gic0l.andslice.engine.PresetLists
import io.github.l0gic0l.andslice.engine.SliceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real native slicer on the device with the bundled Kobra 3 profile. */
@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "engine-test")
    private lateinit var presets: PresetLists

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        val bundle = File(dir, "bundle.ini")
        context.assets.open(SlicerViewModel.BUNDLE_ASSET).use { input -> bundle.outputStream().use { input.copyTo(it) } }
        NativeEngine.init(File(dir, "data").path, File(dir, "resources").path, bundle.path)
        presets = PresetLists.parse(NativeEngine.presetsJson(""))
        NativeEngine.setPrinter(presets.printer)
        NativeEngine.clearPlate()
    }

    @Test
    fun loadsKobra3Profiles() {
        assertEquals("Anycubic Kobra 3 0.4 nozzle", presets.printer)
        assertEquals("0.20mm Standard @Kobra 3 0.4 nozzle", presets.defaultPrint)
        assertTrue(presets.filaments.contains("Anycubic PETG @Kobra 3"))
        val defs = OptionDefs.parse(NativeEngine.optionDefsJson())
        assertTrue(defs.find("fill_density") != null)
    }

    @Test
    fun slicesCube() {
        val stl = File(dir, "cube.stl").apply { writeText(cubeStl(20.0)) }
        NativeEngine.addModel(stl.path, "cube")
        val objects = PlateObject.parseList(NativeEngine.objectsJson())
        assertEquals(1, objects.size)
        assertEquals(10.0, objects[0].offset[2], 1e-6) // resting on the bed

        val out = File(dir, "out").apply { mkdirs() }
        val progress = mutableListOf<Int>()
        val result = SliceResult.parse(
            NativeEngine.slice(
                presets.printer, presets.defaultPrint, arrayOf(presets.defaultFilament),
                arrayOf("fill_density"), arrayOf("20%"), out.path + "/",
            ) { percent, _ -> progress.add(percent) }
        )
        assertTrue(result.error ?: "", result.ok)
        assertEquals(100, result.layerZs.size) // 20 mm at 0.2 mm layers
        assertTrue(progress.isNotEmpty())

        val gcode = File(result.path!!).readText()
        assertTrue(gcode.contains("G9111 bedTemp=60 extruderTemp=220"))
        assertTrue(gcode.contains("; fill_density = 20%"))
        assertTrue(gcode.contains("; thumbnail begin 230x110"))
        // Single-extruder G-code has no tool changes.
        assertTrue(Regex("^T\\d", RegexOption.MULTILINE).find(gcode) == null)
    }

    @Test
    fun slicesTwoColorsOnTheCombo() {
        val combo = "Anycubic Kobra 3 Combo 0.4 nozzle"
        val lists = PresetLists.parse(NativeEngine.presetsJson(combo))
        assertEquals(4, lists.extruders)
        NativeEngine.setPrinter(combo)
        NativeEngine.setWipeTower(true, floatArrayOf(190f, 215f, 45f, 10f, 5f, 20f))

        val stl = File(dir, "cube.stl").apply { writeText(cubeStl(20.0)) }
        NativeEngine.addModel(stl.path, "a")
        NativeEngine.addModel(stl.path, "b")
        val objects = PlateObject.parseList(NativeEngine.objectsJson())
        NativeEngine.setExtruder(objects[1].id, -1, 2)
        assertEquals(setOf(2), PlateObject.parseList(NativeEngine.objectsJson())[1].extruders)

        val out = File(dir, "out").apply { mkdirs() }
        val result = SliceResult.parse(
            NativeEngine.slice(
                combo, lists.defaultPrint, arrayOf("Anycubic PLA @Kobra 3", "Anycubic PLA Matte @Kobra 3"),
                arrayOf("filament_colour"), arrayOf("#FFFFFF;#FF0000;#00FF00;#0000FF"), out.path + "/", null,
            )
        )
        assertTrue(result.error ?: "", result.ok)
        assertEquals(listOf(1, 2), result.extrudersUsed)
        assertTrue(result.wipeTowerDepth > 0)

        // The ACE Pro swaps filament on plain T commands; the wipe tower never resets pressure advance.
        val gcode = File(result.path!!).readText()
        assertTrue(gcode.contains(Regex("^T0$", RegexOption.MULTILINE)))
        assertTrue(gcode.contains(Regex("^T1$", RegexOption.MULTILINE)))
        assertTrue(!gcode.contains("SET_PRESSURE_ADVANCE"))
        assertTrue(!gcode.contains(Regex("^M10[49] .*T\\d", RegexOption.MULTILINE)))
    }

    private fun cubeStl(size: Double): String {
        val s = size
        val v = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(s, 0.0, 0.0), doubleArrayOf(s, s, 0.0), doubleArrayOf(0.0, s, 0.0),
            doubleArrayOf(0.0, 0.0, s), doubleArrayOf(s, 0.0, s), doubleArrayOf(s, s, s), doubleArrayOf(0.0, s, s),
        )
        val faces = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        return buildString {
            append("solid cube\n")
            for (f in faces) {
                append("facet normal 0 0 0\nouter loop\n")
                for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n")
                append("endloop\nendfacet\n")
            }
            append("endsolid cube\n")
        }
    }
}

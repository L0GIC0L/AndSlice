package io.github.l0gic0l.andslice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.l0gic0l.andslice.engine.NativeEngine
import io.github.l0gic0l.andslice.engine.OptionDefs
import io.github.l0gic0l.andslice.engine.PaintBrush
import io.github.l0gic0l.andslice.engine.PaintMode
import io.github.l0gic0l.andslice.engine.PaintState
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
        NativeEngine.init(File(dir, "data").path, File(dir, "resources").path, bundles(SlicerViewModel.BASE_BUNDLES))
        presets = PresetLists.parse(NativeEngine.presetsJson(""))
        NativeEngine.setPrinter(presets.printer)
        NativeEngine.clearPlate()
    }

    /** Copies bundles from the assets, as the app does. */
    private fun bundles(assets: List<String>) = assets.map { asset ->
        val file = File(dir, asset).apply { parentFile?.mkdirs() }
        context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        file.path
    }.toTypedArray()

    @Test
    fun loadsVendorProfilesOnDemand() {
        assertEquals("", NativeEngine.printerForModel("ENDER3V2NEO", "0.4"))
        NativeEngine.loadProfiles(bundles(SlicerViewModel.BASE_BUNDLES + "profiles/vendors/Creality.ini"))
        val preset = NativeEngine.printerForModel("ENDER3V2NEO", "0.4")
        assertTrue(preset, preset.startsWith("Creality Ender-3 V2 Neo"))
        val lists = PresetLists.parse(NativeEngine.presetsJson(preset))
        assertEquals(preset, lists.printer)
        assertTrue(lists.prints.isNotEmpty())
        assertTrue(lists.allFilaments.isNotEmpty())
        // The Kobra 3 keeps its own filaments only.
        assertTrue(PresetLists.parse(NativeEngine.presetsJson("Anycubic Kobra 3 0.4 nozzle")).templateFilaments.isEmpty())
    }

    @Test
    fun savesCustomPrinterAndUserProfiles() {
        val printer = NativeEngine.savePreset(
            "printer", "Test printer", SlicerViewModel.CUSTOM_PRINTER_BASE, SlicerViewModel.CUSTOM_PRINT,
            arrayOf(SlicerViewModel.CUSTOM_FILAMENT), arrayOf("bed_shape", "nozzle_diameter"), arrayOf("0x0,180x0,180x180,0x180", "0.6"),
        )
        var lists = PresetLists.parse(NativeEngine.presetsJson(printer))
        assertEquals(printer, lists.printer)
        assertTrue(lists.isUser(io.github.l0gic0l.andslice.engine.PresetType.Printer, printer))
        assertTrue(lists.templateFilaments.contains(SlicerViewModel.CUSTOM_FILAMENT))

        val print = NativeEngine.savePreset(
            "print", "My print", printer, SlicerViewModel.CUSTOM_PRINT, arrayOf(SlicerViewModel.CUSTOM_FILAMENT),
            arrayOf("perimeters", "fill_density"), arrayOf("4", "35%"),
        )
        val config = org.json.JSONObject(NativeEngine.configJson(printer, print, arrayOf(SlicerViewModel.CUSTOM_FILAMENT)))
        assertEquals("4", config.getString("perimeters"))
        assertEquals("35%", config.getString("fill_density"))
        assertEquals("0x0,180x0,180x180,0x180", config.getString("bed_shape"))
        lists = PresetLists.parse(NativeEngine.presetsJson(printer))
        assertTrue(lists.prints.contains(print))

        NativeEngine.deletePreset("print", print)
        assertTrue(!PresetLists.parse(NativeEngine.presetsJson(printer)).prints.contains(print))
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

    @Test
    fun paintsFilamentsAndUndoes() {
        val combo = "Anycubic Kobra 3 Combo 0.4 nozzle"
        val lists = PresetLists.parse(NativeEngine.presetsJson(combo))
        NativeEngine.setPrinter(combo)
        NativeEngine.setWipeTower(true, floatArrayOf(190f, 215f, 45f, 10f, 5f, 20f))
        val stl = File(dir, "cube.stl").apply { writeText(cubeStl(20.0)) }
        NativeEngine.addModel(stl.path, "cube")
        val cube = PlateObject.parseList(NativeEngine.objectsJson()).single()

        // A dab of filament 2 on top of the cube, as a finger from straight above would paint it.
        assertTrue(NativeEngine.paintBegin(cube.id, PaintMode.COLORS))
        val camera = floatArrayOf(cube.offset[0].toFloat(), cube.offset[1].toFloat(), 500f)
        val brush = PaintBrush(state = 2, radius = 5f, tool = PaintMode.TOOL_BRUSH)
        assertTrue(NativeEngine.paintAt(camera, floatArrayOf(0f, 0f, -1f), brush.params(), false))
        NativeEngine.paintCommit()
        assertTrue(PaintState.parse(NativeEngine.paintStateJson()).canUndo)
        assertTrue(PlateObject.parseList(NativeEngine.objectsJson()).single().painted)

        val out = File(dir, "out").apply { mkdirs() }
        val result = SliceResult.parse(
            NativeEngine.slice(
                combo, lists.defaultPrint, arrayOf("Anycubic PLA @Kobra 3", "Anycubic PLA Matte @Kobra 3"),
                emptyArray(), emptyArray(), out.path + "/", null,
            )
        )
        assertTrue(result.error ?: "", result.ok)
        assertTrue(File(result.path!!).readText().contains(Regex("^T1$", RegexOption.MULTILINE)))

        assertTrue(NativeEngine.paintUndo())
        NativeEngine.paintEnd()
        assertTrue(!PlateObject.parseList(NativeEngine.objectsJson()).single().painted)
    }

    @Test
    fun paintsSupportsOnOverhangsAndBands() {
        val stl = File(dir, "cube.stl").apply { writeText(cubeStl(20.0)) }
        NativeEngine.addModel(stl.path, "cube")
        val cube = PlateObject.parseList(NativeEngine.objectsJson()).single()
        assertTrue(NativeEngine.paintBegin(cube.id, PaintMode.SUPPORTS))
        // The cube's bottom faces straight down, so it is an overhang at any angle.
        NativeEngine.paintHighlightOverhangs(45f)
        assertTrue(NativeEngine.paintOverhangs(PaintMode.ENFORCE, 45f))
        assertTrue(PlateObject.parseList(NativeEngine.objectsJson()).single().supportPainted)
        assertTrue(NativeEngine.paintUndo())
        assertTrue(!PlateObject.parseList(NativeEngine.objectsJson()).single().supportPainted)

        // A 2 mm band of blockers around the height touched from above.
        val band = PaintBrush(state = PaintMode.BLOCK, radius = 1f, tool = PaintMode.TOOL_HEIGHT, heightRange = 2f)
        val camera = floatArrayOf(cube.offset[0].toFloat(), cube.offset[1].toFloat(), 500f)
        assertTrue(NativeEngine.paintAt(camera, floatArrayOf(0f, 0f, -1f), band.params(), false))
        NativeEngine.paintCommit()
        assertTrue(PlateObject.parseList(NativeEngine.objectsJson()).single().supportPainted)
        NativeEngine.paintEnd()
    }

    @Test
    fun paintsSeamsAndFuzzySkin() {
        val stl = File(dir, "cube.stl").apply { writeText(cubeStl(20.0)) }
        NativeEngine.addModel(stl.path, "cube")
        val cube = PlateObject.parseList(NativeEngine.objectsJson()).single()
        val camera = floatArrayOf(cube.offset[0].toFloat(), cube.offset[1].toFloat(), 500f)
        // A 4 mm band around the top of the cube's walls.
        val band = PaintBrush(state = PaintMode.ENFORCE, radius = 1f, tool = PaintMode.TOOL_HEIGHT, heightRange = 4f)
        for (mode in listOf(PaintMode.SEAM, PaintMode.FUZZY_SKIN)) {
            assertTrue(NativeEngine.paintBegin(cube.id, mode))
            assertTrue(NativeEngine.paintAt(camera, floatArrayOf(0f, 0f, -1f), band.params(), false))
            NativeEngine.paintCommit()
            NativeEngine.paintEnd()
        }
        val painted = PlateObject.parseList(NativeEngine.objectsJson()).single()
        assertTrue(painted.seamPainted)
        assertTrue(painted.fuzzyPainted)

        val out = File(dir, "out").apply { mkdirs() }
        val result = SliceResult.parse(
            NativeEngine.slice(presets.printer, presets.defaultPrint, arrayOf(presets.defaultFilament), emptyArray(), emptyArray(), out.path + "/", null)
        )
        assertTrue(result.error ?: "", result.ok)
    }

    @Test
    fun adaptiveLayersAndObjectSettings() {
        val stl = File(dir, "pyramid.stl").apply { writeText(pyramidStl()) }
        NativeEngine.addModel(stl.path, "pyramid")
        val pyramid = PlateObject.parseList(NativeEngine.objectsJson()).single()
        val out = File(dir, "out").apply { mkdirs() }
        fun layers(): Int {
            val result = SliceResult.parse(
                NativeEngine.slice(presets.printer, presets.defaultPrint, arrayOf(presets.defaultFilament), emptyArray(), emptyArray(), out.path + "/", null)
            )
            assertTrue(result.error ?: "", result.ok)
            return result.layerZs.size
        }
        val uniform = layers()

        // The finest adaptive layers: more of them on the sloped sides.
        NativeEngine.setLayerQuality(pyramid.id, 0f)
        val adaptive = PlateObject.parseList(NativeEngine.objectsJson()).single()
        assertEquals(0f, adaptive.layerQuality)
        assertTrue(adaptive.layerProfile)
        assertTrue(layers() > uniform)
        NativeEngine.setLayerQuality(pyramid.id, -1f)
        assertEquals(uniform, layers())

        // More perimeters for this object, and 0.1 mm layers with denser infill from 6 to 12 mm.
        NativeEngine.setObjectOption(pyramid.id, -1.0, "perimeters", "5")
        NativeEngine.editHeightRange(pyramid.id, -1.0, 6.0, 12.0)
        NativeEngine.setObjectOption(pyramid.id, 6.0, "layer_height", "0.1")
        NativeEngine.setObjectOption(pyramid.id, 6.0, "fill_density", "80%")
        val settings = io.github.l0gic0l.andslice.engine.ObjectSettings.parse(NativeEngine.objectSettingsJson(pyramid.id))
        assertEquals("5", settings.options["perimeters"])
        assertEquals(listOf(6.0 to 12.0), settings.ranges.map { it.min to it.max })
        assertEquals("80%", settings.ranges.single().options["fill_density"])
        assertTrue("fill_density" in settings.rangeKeys)
        assertTrue(PlateObject.parseList(NativeEngine.objectsJson()).single().ownSettings)
        assertTrue(layers() > uniform)
    }

    @Test
    fun savesAndOpensProjects() {
        val stl = File(dir, "cube.stl").apply { writeText(cubeStl(20.0)) }
        NativeEngine.addModel(stl.path, "cube")
        val cube = PlateObject.parseList(NativeEngine.objectsJson()).single()
        NativeEngine.setTransform(cube.id, cube.transform(x = 60.0, y = 70.0, rz = 30.0))
        assertTrue(NativeEngine.paintBegin(cube.id, PaintMode.SUPPORTS))
        assertTrue(NativeEngine.paintOverhangs(PaintMode.ENFORCE, 45f))
        NativeEngine.paintEnd()

        val project = File(dir, "plate.3mf")
        NativeEngine.saveProject(
            project.path, presets.printer, presets.defaultPrint, arrayOf(presets.defaultFilament),
            arrayOf("fill_density"), arrayOf("42%"),
        )
        assertTrue(ModelFiles.isProject(project))
        NativeEngine.clearPlate()

        val opened = org.json.JSONObject(NativeEngine.openProject(project.path))
        assertEquals(1, opened.getJSONArray("ids").length())
        val config = opened.getJSONObject("config")
        assertEquals("42%", config.getString("fill_density"))
        assertEquals(presets.printer, ModelFiles.parseStrings(config.getString("printer_settings_id")).single())
        // Where it was, as it was turned, with its painting.
        val restored = PlateObject.parseList(NativeEngine.objectsJson()).single()
        assertEquals(60.0, restored.offset[0], 1e-3)
        assertEquals(70.0, restored.offset[1], 1e-3)
        assertEquals(30.0, restored.rotation[2], 1e-3)
        assertTrue(restored.supportPainted)
    }

    @Test
    fun zAntiAliasingFollowsSlopedTops() {
        // A pyramid: every layer has a sloped top edge to follow.
        val stl = File(dir, "pyramid.stl").apply { writeText(pyramidStl()) }
        NativeEngine.addModel(stl.path, "pyramid")
        val out = File(dir, "out").apply { mkdirs() }
        val result = SliceResult.parse(
            NativeEngine.slice(
                presets.printer, presets.defaultPrint, arrayOf(presets.defaultFilament),
                arrayOf("zaa_enabled"), arrayOf("1"), out.path + "/", null,
            )
        )
        assertTrue(result.error ?: "", result.ok)
        // Extrusions that also move Z: the nozzle follows the slope within a layer.
        val contoured = Regex("^G1 X[-\\d.]+ Y[-\\d.]+ Z[-\\d.]+ E", RegexOption.MULTILINE)
        assertTrue(contoured.containsMatchIn(File(result.path!!).readText()))
    }

    /** A 30 mm wide, 12 mm high pyramid: every layer has sloped edges. */
    private fun pyramidStl(): String {
        val tri = listOf(
            listOf(0.0, 0.0, 0.0, 30.0, 0.0, 0.0, 15.0, 15.0, 12.0),
            listOf(30.0, 0.0, 0.0, 30.0, 30.0, 0.0, 15.0, 15.0, 12.0),
            listOf(30.0, 30.0, 0.0, 0.0, 30.0, 0.0, 15.0, 15.0, 12.0),
            listOf(0.0, 30.0, 0.0, 0.0, 0.0, 0.0, 15.0, 15.0, 12.0),
            listOf(0.0, 0.0, 0.0, 30.0, 30.0, 0.0, 30.0, 0.0, 0.0),
            listOf(0.0, 0.0, 0.0, 0.0, 30.0, 0.0, 30.0, 30.0, 0.0),
        )
        return buildString {
            append("solid pyramid\n")
            for (t in tri) {
                append("facet normal 0 0 0\nouter loop\n")
                for (i in 0 until 3) append("vertex ${t[3 * i]} ${t[3 * i + 1]} ${t[3 * i + 2]}\n")
                append("endloop\nendfacet\n")
            }
            append("endsolid pyramid\n")
        }
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

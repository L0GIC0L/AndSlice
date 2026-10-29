package io.github.l0gic0l.andslice.ui

import io.github.l0gic0l.andslice.engine.OptionDef
import io.github.l0gic0l.andslice.engine.PresetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsLayoutTest {
    // Unit tests run in the module directory.
    private val layout = SettingsLayout.parse(File("src/main/assets/settings_layout.json").readText())

    private fun def(key: String) = OptionDef(
        key = key, label = key, fullLabel = key, category = "", tooltip = "", sidetext = "", type = "float",
        vector = false, nullable = false, multiline = false, mode = 0, min = null, max = null,
        enumValues = emptyList(), enumOpen = false,
    )

    @Test
    fun placesOptionsOnPrusaSlicerPages() {
        val sections = layout.sections(PresetType.Print, listOf(def("fill_density"), def("layer_height"), def("perimeter_speed")))
        assertEquals(listOf("Layers and perimeters", "Infill", "Speed"), sections.map { it.page })
        assertEquals("Layer height", sections[0].title)
        assertEquals(listOf("fill_density"), sections[1].options.map { it.key })
    }

    @Test
    fun putsUnknownOptionsLast() {
        val sections = layout.sections(PresetType.Filament, listOf(def("not_in_any_tab"), def("temperature")))
        assertEquals("Filament", sections.first().page)
        assertEquals(SettingsLayout.OTHER, sections.last().page)
        assertEquals(listOf("not_in_any_tab"), sections.last().options.map { it.key })
    }

    @Test
    fun coversThePrinterTab() {
        val pages = layout.sections(PresetType.Printer, listOf(def("bed_shape"), def("retract_length"), def("cooling_tube_length")))
            .map { it.page }
        assertTrue(pages.containsAll(listOf("General", "Extruder", "Single extruder MM setup")))
    }
}

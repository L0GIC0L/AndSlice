package io.github.l0gic0l.andslice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationTest {
    @Test
    fun temperatureTowerSteps() {
        val temps = Calibration.temperatures(220, 5, 4)
        assertEquals(listOf(220, 215, 210, 205), temps)
        // Section 1 from the second layer, the others from their first layer.
        assertEquals(
            listOf(0.4 to "M104 S220", 8.2 to "M104 S215", 16.2 to "M104 S210", 24.2 to "M104 S205"),
            Calibration.temperatureCommands(temps, firstLayer = 0.2, layer = 0.2).map { (z, g) -> Math.round(z * 1000) / 1000.0 to g },
        )
    }

    @Test
    fun pressureAdvanceFollowsTheFirmware() {
        // The Kobra 3's start G-code sets it with M900 K, as Anycubic's firmware takes it.
        assertEquals("M900 K{value}", Calibration.pressureAdvanceCommand("G9111\\nM900 K0.051", "klipper"))
        assertEquals("SET_PRESSURE_ADVANCE ADVANCE={value}", Calibration.pressureAdvanceCommand("PRINT_START", "klipper"))
        assertEquals("M900 K{value}", Calibration.pressureAdvanceCommand("G28", "marlin2"))
        assertNull(Calibration.pressureAdvanceCommand("G28", "reprap"))

        val commands = Calibration.pressureAdvanceCommands("M900 K{value}", start = 0.0, perMm = 0.002, height = 3, firstLayer = 0.2)
        assertEquals(listOf(0.2 to "M900 K0", 1.0 to "M900 K0.002", 2.0 to "M900 K0.004"), commands)
        assertEquals(0.05, Calibration.pressureAdvanceAt(25.0, 0.0, 0.002), 1e-9)
    }

    @Test
    fun flowFromTheMeasuredWall() {
        // Two 0.45 mm lines at 0.2 mm layers: 0.45 + (0.45 - 0.2 * (1 - π/4)).
        val expected = Calibration.expectedWall(0.45, 0.2)
        assertEquals(0.857, expected, 1e-3)
        assertEquals(0.95 * expected / 0.9, Calibration.flowMultiplier(0.95, expected, 0.9), 1e-9)
    }

    @Test
    fun settingsForEverySlot() {
        assertEquals("215,215,215,215", Calibration.allSlots("215", 4))
        assertEquals(0.15, Calibration.firstLayerHeight("75%", 0.2), 1e-9)
        assertEquals(0.3, Calibration.firstLayerHeight("0.3", 0.2), 1e-9)
        assertEquals("; Filament gcode\nM900 K0.04\n", Calibration.withPressureAdvance("; Filament gcode\nM900 K0.02\n", "M900 K0.04"))
        assertEquals("M900 K0.04\n", Calibration.withPressureAdvance("", "M900 K0.04"))

        // Filament G-code is a list of strings, one per slot.
        val list = listOf("; Filament gcode\nM900 K0.04\n", "say \"hi\"")
        assertEquals(list, ModelFiles.parseStrings(ModelFiles.serializeStrings(list)))
    }
}

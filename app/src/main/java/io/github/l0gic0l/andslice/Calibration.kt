package io.github.l0gic0l.andslice

import java.util.Locale
import kotlin.math.PI

/**
 * The arithmetic of the calibration prints: what each test object prints with, and how a
 * measurement turns into a setting.
 */
object Calibration {
    /** Height of each temperature tower section, in mm. */
    const val SECTION_HEIGHT = 8.0
    /** Height of the flow cube, in mm. */
    const val CUBE_HEIGHT = 10.0

    /** Temperature of each section, from the bottom: [start], then [step] lower each time. */
    fun temperatures(start: Int, step: Int, sections: Int): List<Int> = List(sections) { start - it * step }

    /**
     * Commands of a temperature tower: each section's temperature from its first layer on. The
     * first layer prints at the filament's first layer temperature, so section 1 starts on layer 2.
     */
    fun temperatureCommands(temperatures: List<Int>, firstLayer: Double, layer: Double): List<Pair<Double, String>> =
        temperatures.mapIndexed { i, t -> (if (i == 0) firstLayer + layer else i * SECTION_HEIGHT + layer) to "M104 S$t" }

    /**
     * How the printer's firmware takes a pressure advance value ({value} stands for it), or null when
     * it has none: as the printer's start G-code already does (the Kobra 3 uses M900 K), else Klipper's
     * SET_PRESSURE_ADVANCE or Marlin's linear advance.
     */
    fun pressureAdvanceCommand(startGcode: String, flavor: String): String? = when {
        Regex("(^|\\n|\\\\n)\\s*M900\\b").containsMatchIn(startGcode) -> "M900 K{value}"
        flavor == "klipper" -> "SET_PRESSURE_ADVANCE ADVANCE={value}"
        flavor.startsWith("marlin") -> "M900 K{value}"
        else -> null
    }

    /** Pressure advance at [heightMm] of the tower. */
    fun pressureAdvanceAt(heightMm: Double, start: Double, perMm: Double): Double = start + heightMm * perMm

    /** One command per millimeter of the tower, from the first layer up. */
    fun pressureAdvanceCommands(template: String, start: Double, perMm: Double, height: Int, firstLayer: Double): List<Pair<Double, String>> =
        (0 until height).map { mm ->
            maxOf(mm.toDouble(), firstLayer) to template.replace("{value}", format(pressureAdvanceAt(mm.toDouble(), start, perMm), 4))
        }

    /**
     * Thickness of a wall of [perimeters] lines of [width] at [layerHeight], with PrusaSlicer's line
     * spacing: neighboring lines overlap by the rounded sides of the extrusion.
     */
    fun expectedWall(width: Double, layerHeight: Double, perimeters: Int = 2): Double {
        val spacing = width - layerHeight * (1 - PI / 4)
        return width + (perimeters - 1) * spacing
    }

    /** The extrusion multiplier that makes a wall measured at [measured] come out at [expected]. */
    fun flowMultiplier(current: Double, expected: Double, measured: Double): Double = current * expected / measured

    /** A value for every filament slot of a per-extruder option, such as "215,215". */
    fun allSlots(value: String, slots: Int): String = List(slots.coerceAtLeast(1)) { value }.joinToString(",")

    /** [gcode] with its pressure advance line replaced by [command], or with [command] added. */
    fun withPressureAdvance(gcode: String, command: String): String {
        val lines = gcode.lines().filterNot { line ->
            line.trimStart().let { it.startsWith("M900 ") || it.startsWith("SET_PRESSURE_ADVANCE") }
        }.dropLastWhile { it.isBlank() }
        return (lines + command).joinToString("\n") + "\n"
    }

    /** First layer height: absolute, or a percentage of the layer height. */
    fun firstLayerHeight(value: String?, layerHeight: Double): Double = when {
        value == null -> layerHeight
        value.endsWith("%") -> layerHeight * (value.removeSuffix("%").toDoubleOrNull() ?: 100.0) / 100.0
        else -> value.toDoubleOrNull() ?: layerHeight
    }

    fun format(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

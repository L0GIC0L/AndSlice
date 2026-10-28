package io.github.l0gic0l.andslice.octoprint

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What a slot of the printer's filament changer (the ACE Pro) holds, as the printer reports it. */
data class FilamentSlot(
    /** 0-based slot. */
    val index: Int,
    val loaded: Boolean,
    /** "PLA", "PETG", …; empty when unknown. */
    val material: String,
    /** The filament's name, such as "PLA Matte"; often just the material. */
    val name: String,
    /** "#RRGGBB", or null when unknown. */
    val color: String?,
)

object FilamentSlots {
    /**
     * Happy Hare's `mmu` object, which Rinkhals fills from the ACE: gate_status (1 loaded, -1 loaded
     * and in use, 0 empty), gate_material, gate_filament_name and gate_color as "RRGGBBAA".
     */
    fun fromMmu(mmu: JSONObject): List<FilamentSlot> {
        val status = mmu.optJSONArray("gate_status") ?: return emptyList()
        val materials = mmu.optJSONArray("gate_material")
        val names = mmu.optJSONArray("gate_filament_name")
        val colors = mmu.optJSONArray("gate_color")
        return (0 until status.length()).map { i ->
            val material = materials.text(i)
            FilamentSlot(
                index = i,
                loaded = status.optInt(i, 0) != 0,
                material = material,
                name = names.text(i).ifEmpty { material },
                color = hexColor(colors?.optString(i)),
            )
        }
    }

    /** The ACEPRO Klipper driver's `ace_instance_0`: slots with status "ready" or "empty", color [R, G, B] and material. */
    fun fromAcePro(ace: JSONObject): List<FilamentSlot> {
        val slots = ace.optJSONArray("slots") ?: return emptyList()
        return (0 until slots.length()).mapNotNull { i ->
            val slot = slots.optJSONObject(i) ?: return@mapNotNull null
            val material = slot.optString("material").trim().takeUnless { it.equals("unknown", true) }.orEmpty()
            val rgb = slot.optJSONArray("color")
            FilamentSlot(
                index = slot.optInt("index", i),
                loaded = slot.optString("status").let { it.isNotEmpty() && it != "empty" },
                material = material,
                name = material,
                color = rgb?.takeIf { it.length() >= 3 }?.let {
                    String.format(Locale.US, "#%02X%02X%02X", it.optInt(0).coerceIn(0, 255), it.optInt(1).coerceIn(0, 255), it.optInt(2).coerceIn(0, 255))
                },
            )
        }
    }

    /** "E01B24FF" or "#e01b24" -> "#E01B24"; anything else -> null. */
    fun hexColor(value: String?): String? {
        val hex = value?.trim()?.removePrefix("#")?.uppercase() ?: return null
        return if (hex.length >= 6 && hex.take(6).all { it in "0123456789ABCDEF" }) "#" + hex.take(6) else null
    }

    /**
     * The filament profile that fits a slot best: one named like the slot's filament ("PLA Matte"),
     * else one for its material ("PLA") with the fewest other words. Profiles come in the order to
     * prefer, the printer's own first. Null when none fits.
     */
    fun matchPreset(slot: FilamentSlot, presets: List<String>): String? {
        if (slot.material.isEmpty() && slot.name.isEmpty()) return null
        fun short(preset: String) = preset.substringBefore(" @").removePrefix("Anycubic ").removePrefix("Generic ").trim()
        val name = slot.name.trim()
        presets.firstOrNull { short(it).equals(name, ignoreCase = true) }?.let { return it }
        presets.firstOrNull { short(it).equals(slot.material, ignoreCase = true) }?.let { return it }
        val material = slot.material.ifEmpty { name }.lowercase()
        return presets
            .filter { p -> short(p).lowercase().split(' ', '-', '+').contains(material) }
            .minByOrNull { short(it).split(' ').size }
    }

    private fun JSONArray?.text(i: Int): String =
        this?.optString(i)?.trim()?.takeUnless { it.equals("unknown", true) || it == "null" }.orEmpty()
}

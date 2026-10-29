package io.github.l0gic0l.andslice.octoprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilamentSlotsTest {
    private val presets = listOf(
        "Anycubic PLA @Kobra 3", "Anycubic PLA Matte @Kobra 3", "Anycubic PLA High Speed @Kobra 3",
        "Anycubic PETG @Kobra 3", "Generic PLA+ @Template", "Generic ASA @Template",
    )

    private fun slot(material: String, name: String = material) = FilamentSlot(0, true, material, name, "#FFFFFF")

    @Test
    fun picksTheProfileOfTheFilament() {
        // By name first, then by material, then the shortest profile with the material in it.
        assertEquals("Anycubic PLA Matte @Kobra 3", FilamentSlots.matchPreset(slot("PLA", "PLA Matte"), presets))
        assertEquals("Anycubic PLA @Kobra 3", FilamentSlots.matchPreset(slot("PLA", "PLA Glow"), presets))
        assertEquals("Anycubic PETG @Kobra 3", FilamentSlots.matchPreset(slot("PETG"), presets))
        assertEquals("Generic ASA @Template", FilamentSlots.matchPreset(slot("asa"), presets))
        assertNull(FilamentSlots.matchPreset(slot("PA-CF"), presets))
        assertNull(FilamentSlots.matchPreset(slot(""), presets))
    }

    @Test
    fun readsColors() {
        assertEquals("#E01B24", FilamentSlots.hexColor("E01B24FF"))
        assertEquals("#E01B24", FilamentSlots.hexColor("#e01b24"))
        assertNull(FilamentSlots.hexColor("red"))
        assertNull(FilamentSlots.hexColor(null))
    }
}

package io.github.l0gic0l.andslice.ui

import io.github.l0gic0l.andslice.engine.OptionDef
import io.github.l0gic0l.andslice.engine.PresetType
import org.json.JSONObject

/** A titled group of options on a settings page, like an option group in PrusaSlicer's tabs. */
data class OptionSection(val page: String, val title: String, val options: List<OptionDef>)

/**
 * Pages and option groups of PrusaSlicer's Print, Filament and Printer tabs, read from
 * assets/settings_layout.json (generated from PrusaSlicer's Tab.cpp by scripts/gen-settings-layout.py).
 */
class SettingsLayout(private val tabs: Map<PresetType, List<Pair<String, List<Pair<String, List<String>>>>>>) {

    /** Splits [options] into sections in PrusaSlicer's page order; options the layout does not place go last. */
    fun sections(type: PresetType, options: List<OptionDef>): List<OptionSection> {
        val byKey = options.associateBy { it.key }
        val placed = HashSet<String>()
        val sections = ArrayList<OptionSection>()
        for ((page, groups) in tabs[type].orEmpty()) {
            for ((group, keys) in groups) {
                val defs = keys.mapNotNull { byKey[it] }.filter { placed.add(it.key) }
                if (defs.isNotEmpty()) sections += OptionSection(page, group.ifEmpty { page }, defs)
            }
        }
        val rest = options.filter { it.key !in placed }
        if (rest.isNotEmpty()) sections += OptionSection(OTHER, OTHER, rest)
        return sections
    }

    companion object {
        const val OTHER = "Other"

        fun parse(json: String): SettingsLayout {
            val o = JSONObject(json)
            val tabs = PresetType.entries.associateWith { type ->
                val pages = o.optJSONArray(type.name.lowercase()) ?: return@associateWith emptyList()
                (0 until pages.length()).map { i ->
                    val page = pages.getJSONObject(i)
                    val groups = page.getJSONArray("groups")
                    page.getString("title") to (0 until groups.length()).map { j ->
                        val group = groups.getJSONObject(j)
                        val keys = group.getJSONArray("keys")
                        group.getString("title") to (0 until keys.length()).map { keys.getString(it) }
                    }
                }
            }
            return SettingsLayout(tabs)
        }
    }
}

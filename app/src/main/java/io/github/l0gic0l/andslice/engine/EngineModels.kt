package io.github.l0gic0l.andslice.engine

import org.json.JSONArray
import org.json.JSONObject

/** Presets of the bundle; prints and filaments are those compatible with [printer]. */
data class PresetLists(
    val printer: String,
    val printers: List<String>,
    val prints: List<String>,
    val filaments: List<String>,
    val defaultPrint: String,
    val defaultFilament: String,
    /** Extruders of the printer; the Kobra 3 Combo has one per ACE slot. */
    val extruders: Int,
    /** PrusaSlicer's generic template filaments, listed after the printer's own [filaments]. */
    val templateFilaments: List<String> = emptyList(),
    /** Profiles the user saved, by type. */
    val user: Map<PresetType, List<String>> = emptyMap(),
    /** Vendor bundle, model and nozzle variant of the printer. */
    val vendor: String = "",
    val model: String = "",
    val variant: String = "",
    /** Nozzle variants of the printer's model: variant to printer preset. */
    val variants: List<Pair<String, String>> = emptyList(),
) {
    val allFilaments: List<String> get() = filaments + templateFilaments

    fun isUser(type: PresetType, name: String) = user[type]?.contains(name) == true

    companion object {
        fun parse(json: String): PresetLists {
            val o = JSONObject(json)
            return PresetLists(
                printer = o.getString("printer"),
                printers = o.getJSONArray("printers").strings(),
                prints = o.getJSONArray("prints").strings(),
                filaments = o.getJSONArray("filaments").strings(),
                defaultPrint = o.optString("default_print"),
                defaultFilament = o.optString("default_filament"),
                extruders = o.optInt("extruders", 1).coerceAtLeast(1),
                templateFilaments = o.optJSONArray("template_filaments")?.strings() ?: emptyList(),
                user = o.optJSONObject("user")?.let { u ->
                    PresetType.entries.associateWith { t -> u.optJSONArray(t.name.lowercase())?.strings() ?: emptyList() }
                } ?: emptyMap(),
                vendor = o.optString("vendor"),
                model = o.optString("model"),
                variant = o.optString("variant"),
                variants = o.optJSONArray("variants")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { v -> v.getString("variant") to v.getString("preset") } }
                } ?: emptyList(),
            )
        }
    }
}

/** A model part of an object and its 1-based extruder (filament slot). */
data class ObjectPart(val name: String, val extruder: Int)

data class PlateObject(
    val id: Long,
    val name: String,
    val offset: List<Double>,
    val rotation: List<Double>,
    val scale: List<Double>,
    val size: List<Double>,
    val triangles: Int,
    val outside: Boolean,
    /** 1-based extruder of the object; parts may use their own. */
    val extruder: Int = 1,
    val parts: List<ObjectPart> = emptyList(),
    /** Has multi-material painting, which picks its own filaments. */
    val painted: Boolean = false,
    /** Has painted support enforcers or blockers. */
    val supportPainted: Boolean = false,
) {
    /** Extruders that print this object. */
    val extruders: Set<Int> get() = parts.map { it.extruder }.toSet().ifEmpty { setOf(extruder) }

    /** Transform in the layout NativeEngine.setTransform expects. */
    fun transform(
        x: Double = offset[0], y: Double = offset[1],
        rx: Double = rotation[0], ry: Double = rotation[1], rz: Double = rotation[2],
        sx: Double = scale[0], sy: Double = scale[1], sz: Double = scale[2],
    ) = doubleArrayOf(x, y, rx, ry, rz, sx, sy, sz)

    companion object {
        fun parseList(json: String): List<PlateObject> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                PlateObject(
                    id = o.getLong("id"),
                    name = o.optString("name"),
                    offset = o.getJSONArray("offset").doubles(),
                    rotation = o.getJSONArray("rotation").doubles(),
                    scale = o.getJSONArray("scale").doubles(),
                    size = o.getJSONArray("size").doubles(),
                    triangles = o.optInt("triangles"),
                    outside = o.optBoolean("outside"),
                    extruder = o.optInt("extruder", 1),
                    parts = o.optJSONArray("parts")?.let { p ->
                        (0 until p.length()).map { j ->
                            val part = p.getJSONObject(j)
                            ObjectPart(part.optString("name"), part.optInt("extruder", 1))
                        }
                    } ?: emptyList(),
                    painted = o.optBoolean("painted"),
                    supportPainted = o.optBoolean("support_painted"),
                )
            }
        }
    }
}

data class SliceResult(
    val ok: Boolean,
    val canceled: Boolean,
    val error: String?,
    val path: String?,
    val printTime: String,
    val printTimeSeconds: Double,
    val filamentMm: Double,
    val filamentGrams: Double,
    val cost: Double,
    val layerZs: List<Double>,
    val warnings: List<String>,
    /** 1-based extruders (filament slots) the G-code uses. */
    val extrudersUsed: List<Int> = emptyList(),
    /** Depth of the printed wipe tower in mm, or 0 when there is none. */
    val wipeTowerDepth: Double = 0.0,
) {
    companion object {
        fun parse(json: String): SliceResult {
            val o = JSONObject(json)
            return SliceResult(
                ok = o.optBoolean("ok"),
                canceled = o.optBoolean("canceled"),
                error = if (o.has("error")) o.getString("error") else null,
                path = if (o.has("path")) o.getString("path") else null,
                printTime = o.optString("print_time"),
                printTimeSeconds = o.optDouble("print_time_s", 0.0),
                filamentMm = o.optDouble("filament_mm", 0.0),
                filamentGrams = o.optDouble("filament_g", 0.0),
                cost = o.optDouble("cost", 0.0),
                layerZs = o.optJSONArray("layers")?.doubles() ?: emptyList(),
                warnings = o.optJSONArray("warnings")?.strings() ?: emptyList(),
                extrudersUsed = o.optJSONArray("extruders_used")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                wipeTowerDepth = o.optJSONObject("wipe_tower")?.optDouble("depth", 0.0) ?: 0.0,
            )
        }
    }
}

/** Metadata of one PrusaSlicer configuration option (from PrintConfigDef). */
data class OptionDef(
    val key: String,
    val label: String,
    val fullLabel: String,
    val category: String,
    val tooltip: String,
    val sidetext: String,
    val type: String,
    val vector: Boolean,
    val nullable: Boolean,
    val multiline: Boolean,
    /** 0 = simple, 1 = advanced, 2 = expert, as in PrusaSlicer's mode switch. */
    val mode: Int,
    val min: Double?,
    val max: Double?,
    val enumValues: List<Pair<String, String>>,
    val enumOpen: Boolean,
)

enum class PresetType { Print, Filament, Printer }

data class OptionDefs(val byType: Map<PresetType, List<OptionDef>>) {
    fun typeOf(key: String): PresetType? = byType.entries.firstOrNull { (_, l) -> l.any { it.key == key } }?.key
    fun find(key: String): OptionDef? = byType.values.asSequence().flatten().firstOrNull { it.key == key }

    companion object {
        fun parse(json: String): OptionDefs {
            val o = JSONObject(json)
            fun list(name: String): List<OptionDef> {
                val a = o.getJSONArray(name)
                return (0 until a.length()).map { i ->
                    val d = a.getJSONObject(i)
                    val enumValues = d.optJSONArray("enum")?.let { e ->
                        (0 until e.length()).map { j ->
                            val v = e.getJSONObject(j)
                            v.getString("value") to v.optString("label", v.getString("value"))
                        }
                    } ?: emptyList()
                    OptionDef(
                        key = d.getString("key"),
                        label = d.optString("label"),
                        fullLabel = d.optString("full_label"),
                        category = d.optString("category"),
                        tooltip = d.optString("tooltip"),
                        sidetext = d.optString("sidetext"),
                        type = d.optString("type"),
                        vector = d.optBoolean("vector"),
                        nullable = d.optBoolean("nullable"),
                        multiline = d.optBoolean("multiline"),
                        mode = d.optInt("mode"),
                        min = if (d.has("min")) d.getDouble("min") else null,
                        max = if (d.has("max")) d.getDouble("max") else null,
                        enumValues = enumValues,
                        enumOpen = d.optBoolean("enum_open"),
                    )
                }
            }
            return OptionDefs(
                mapOf(
                    PresetType.Print to list("print"),
                    PresetType.Filament to list("filament"),
                    PresetType.Printer to list("printer"),
                )
            )
        }
    }
}

data class RoleInfo(val id: Int, val color: Long, val used: Boolean, val timeSeconds: Double)

data class PreviewInfo(
    val loaded: Boolean,
    val layers: Int,
    val timeSeconds: Double,
    val viewType: Int,
    val roles: List<RoleInfo>,
    /** Legend for gradient view types: value and color. */
    val range: List<Pair<Double, Long>>,
    /** 0-based tools (filament slots) of the print and their colors. */
    val tools: List<Pair<Int, Long>> = emptyList(),
) {
    companion object {
        val EMPTY = PreviewInfo(false, 0, 0.0, 0, emptyList(), emptyList())

        fun parse(json: String): PreviewInfo {
            val o = JSONObject(json)
            val roles = o.optJSONArray("roles")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    RoleInfo(r.getInt("id"), parseColor(r.getString("color")), r.optBoolean("used"), r.optDouble("time", 0.0))
                }
            } ?: emptyList()
            val range = o.optJSONArray("range")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    r.getDouble("value") to parseColor(r.getString("color"))
                }
            } ?: emptyList()
            val tools = o.optJSONArray("tools")?.let { a ->
                (0 until a.length()).map { i ->
                    val t = a.getJSONObject(i)
                    t.getInt("id") to parseColor(t.getString("color"))
                }
            } ?: emptyList()
            return PreviewInfo(
                tools = tools,
                loaded = o.optBoolean("loaded"),
                layers = o.optInt("layers"),
                timeSeconds = o.optDouble("time", 0.0),
                viewType = o.optInt("view_type"),
                roles = roles,
                range = range,
            )
        }

        private fun parseColor(hex: String): Long = 0xFF000000L or hex.removePrefix("#").toLong(16)
    }
}

/** Names of libvgcode's EGCodeExtrusionRole values, matching PrusaSlicer's legend. */
val EXTRUSION_ROLE_NAMES = listOf(
    "None", "Perimeter", "External perimeter", "Overhang perimeter", "Internal infill",
    "Solid infill", "Top solid infill", "Ironing", "Bridge infill", "Gap fill", "Skirt/Brim",
    "Support material", "Support material interface", "Wipe tower", "Custom",
)

/** libvgcode EViewType values offered in the preview, in PrusaSlicer's order. */
val PREVIEW_VIEW_TYPES = listOf(
    0 to "Feature type", 1 to "Height", 2 to "Width", 3 to "Speed", 4 to "Actual speed",
    5 to "Fan speed", 6 to "Temperature", 7 to "Volumetric flow rate", 9 to "Layer time",
    11 to "Filament",
)

/** libvgcode's EViewType for the per-tool (filament slot) view. */
const val VIEW_TYPE_TOOL = 11

/** libvgcode EOptionType ids used by the preview toggles. */
object PreviewOption {
    const val TRAVELS = 0
    const val RETRACTIONS = 2
    const val SEAMS = 4
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun JSONArray.doubles(): List<Double> = (0 until length()).map { getDouble(it) }

/** Paint modes, tools and support states of [NativeEngine.paintAt]; filament states are the 1-based extruder. */
object PaintMode {
    const val SUPPORTS = 0
    const val COLORS = 1

    const val TOOL_BRUSH = 0
    const val TOOL_FILL = 1

    const val ERASE = 0
    const val ENFORCE = 1
    const val BLOCK = 2
}

/** State of the paint session, from [NativeEngine.paintStateJson]. */
data class PaintState(val active: Boolean = false, val objectId: Long = -1, val mode: Int = 0, val canUndo: Boolean = false, val painted: Boolean = false) {
    companion object {
        fun parse(json: String): PaintState {
            val o = JSONObject(json)
            return PaintState(
                active = o.optBoolean("active"),
                objectId = o.optLong("object", -1),
                mode = o.optInt("mode"),
                canUndo = o.optBoolean("can_undo"),
                painted = o.optBoolean("painted"),
            )
        }
    }
}

/** What a touch on the object paints while painting: [state] with [tool], [radius] mm wide. */
data class PaintBrush(val state: Int, val radius: Float, val tool: Int)

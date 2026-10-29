package io.github.l0gic0l.andslice

import org.json.JSONArray
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the engine's check found for one object on the plate (Engine::check_plate). */
data class ObjectCheck(
    val id: Long,
    val name: String,
    /** Edges with a facet on one side only: holes in the surface. */
    val openEdges: Int,
    /** Flipped or broken facets fixed when the model was loaded. */
    val repairedErrors: Int,
    val overhangMm2: Double,
    /** The overhang area the supports leave out with the current settings and painting. */
    val unsupportedMm2: Double,
    /** Area lying on the bed. */
    val contactMm2: Double,
    val heightMm: Double,
    /** Not entirely inside the print volume. */
    val outside: Boolean,
    /** Larger than the print volume. */
    val tooBig: Boolean,
) {
    companion object {
        fun parseList(json: String): List<ObjectCheck> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ObjectCheck(
                    id = o.getLong("id"),
                    name = o.optString("name"),
                    openEdges = o.optInt("open_edges"),
                    repairedErrors = o.optInt("repaired_errors"),
                    overhangMm2 = o.optDouble("overhang_mm2", 0.0),
                    unsupportedMm2 = o.optDouble("unsupported_mm2", 0.0),
                    contactMm2 = o.optDouble("contact_mm2", 0.0),
                    heightMm = o.optJSONArray("size")?.optDouble(2, 0.0) ?: 0.0,
                    outside = o.optBoolean("outside"),
                    tooBig = o.optBoolean("too_big"),
                )
            }
        }
    }
}

/** A way to make the print printable, offered with an issue. */
enum class IssueFix(val label: String) {
    Repair("Repair mesh"),
    Orient("Optimize orientation"),
    Supports("Turn on supports"),
    PaintSupports("Paint supports"),
    Brim("Add a brim"),
    ScaleToFit("Scale to fit"),
    Arrange("Arrange"),
}

data class PrintIssue(
    /** The object it is about; null for the whole print. */
    val objectId: Long?,
    val objectName: String?,
    /** Info is worth knowing; a warning means the print will likely fail or look bad. */
    val warning: Boolean,
    val title: String,
    val detail: String,
    val fixes: List<IssueFix>,
)

/** Turns the engine's findings and the slicer's warnings into issues with fixes. */
object Printability {
    /** Unsupported overhang area worth a warning: about a 7 × 7 mm patch. */
    const val LARGE_OVERHANG_MM2 = 50.0
    /** Less than this on the bed is a point or an edge, which comes loose. */
    const val POINT_CONTACT_MM2 = 5.0

    /**
     * Issues of the objects on the plate, from the mesh check. [supports] tells whether automatic
     * supports are on and [brim] whether a brim is; fixes that would change nothing are left out.
     */
    fun plateIssues(checks: List<ObjectCheck>, supports: Boolean, brim: Boolean, overhangAngle: Int): List<PrintIssue> =
        checks.flatMap { c ->
            buildList {
                fun add(warning: Boolean, title: String, detail: String, vararg fixes: IssueFix) =
                    add(PrintIssue(c.id, c.name, warning, title, detail, fixes.toList()))

                when {
                    c.tooBig -> add(true, "Too big for the printer", "It does not fit in the print volume.", IssueFix.ScaleToFit)
                    c.outside -> add(true, "Off the bed", "Part of it is outside the print area.", IssueFix.Arrange)
                }
                if (c.openEdges > 0) {
                    add(
                        true, "Holes in the model",
                        "${c.openEdges} open edges: the surface is not closed, so the slicer may leave gaps or fill the wrong areas.",
                        IssueFix.Repair,
                    )
                } else if (c.repairedErrors > 0) {
                    add(
                        false, "Model errors fixed",
                        "${c.repairedErrors} broken or flipped facets were fixed when the model was loaded. Check the preview.",
                    )
                }
                if (c.unsupportedMm2 >= LARGE_OVERHANG_MM2) {
                    val fixes = listOfNotNull(
                        IssueFix.Supports.takeUnless { supports },
                        IssueFix.Orient,
                        IssueFix.PaintSupports,
                    )
                    add(
                        true, "Large overhangs",
                        "About ${area(c.unsupportedMm2)} faces down steeper than $overhangAngle° with nothing under it. " +
                            "It will droop or fail without supports.",
                        *fixes.toTypedArray(),
                    )
                }
                if (c.contactMm2 < POINT_CONTACT_MM2) {
                    add(
                        true, "Barely touches the bed",
                        "Only ${area(c.contactMm2)} of it lies on the bed, so it can come loose or tip over.",
                        *listOfNotNull(IssueFix.Orient, IssueFix.Brim.takeUnless { brim }, IssueFix.Supports.takeUnless { supports }).toTypedArray(),
                    )
                } else if (!brim && c.heightMm > 20.0 && c.heightMm > 3.0 * sqrt(c.contactMm2)) {
                    add(
                        false, "Small footprint for its height",
                        "It stands on ${area(c.contactMm2)} and is ${c.heightMm.roundToInt()} mm tall. A brim holds it down.",
                        IssueFix.Brim,
                    )
                }
            }
        }

    /** Issues from the slicer's warnings, such as PrusaSlicer's "Detected print stability issues". */
    fun sliceIssues(warnings: List<String>, supports: Boolean, brim: Boolean): List<PrintIssue> = warnings.map { w ->
        val stability = w.startsWith(STABILITY)
        val detail = if (stability) {
            w.removePrefix(STABILITY).lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("Consider enabling") && !it.startsWith("Also consider") }
                .joinToString("\n")
        } else {
            w.trim()
        }
        PrintIssue(
            objectId = null, objectName = null, warning = true,
            title = if (stability) "Print stability issues" else "Slicing warning",
            detail = detail,
            fixes = listOfNotNull(
                IssueFix.Supports.takeIf { !supports && w.contains("enabling supports") },
                IssueFix.Brim.takeIf { !brim && w.contains("enabling brim") },
            ),
        )
    }

    private const val STABILITY = "Detected print stability issues:"

    /** "320 mm²", or "12.5 cm²" from 1000 mm² on. */
    fun area(mm2: Double): String =
        if (mm2 >= 1000) String.format(Locale.US, "%.1f cm²", mm2 / 100) else "${mm2.roundToInt()} mm²"
}

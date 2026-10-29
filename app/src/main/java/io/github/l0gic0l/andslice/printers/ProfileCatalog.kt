package io.github.l0gic0l.andslice.printers

import org.json.JSONArray

/** One printer model of a vendor bundle and its nozzle variants. */
data class CatalogModel(val id: String, val name: String, val family: String, val variants: List<String>)

/** A vendor config bundle in assets/profiles. [file] is relative to assets/profiles. */
data class CatalogVendor(val id: String, val name: String, val file: String, val models: List<CatalogModel>) {
    /** Asset path of the bundle; empty for the bundles that are always loaded. */
    val bundle: String get() = if (file.startsWith("vendors/")) "profiles/$file" else ""
}

/**
 * The printers AndSlice has profiles for: its own Kobra 3 bundle and PrusaSlicer's vendor
 * bundles, read from assets/profiles/catalog.json (written by scripts/update-vendor-profiles.py).
 */
object ProfileCatalog {
    fun parse(json: String): List<CatalogVendor> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.getJSONArray("models").length() > 0 }
            .map { v ->
                val models = v.getJSONArray("models")
                CatalogVendor(
                    id = v.getString("id"),
                    // PrusaSlicer's bundles write "&" as "&&" for its GUI toolkit.
                    name = v.getString("name").replace("&&", "&"),
                    file = v.getString("file"),
                    models = (0 until models.length()).map { i ->
                        val m = models.getJSONObject(i)
                        val variants = m.getJSONArray("variants")
                        CatalogModel(
                            id = m.getString("id"),
                            name = m.getString("name").replace("&&", "&"),
                            family = m.optString("family"),
                            variants = (0 until variants.length()).map { variants.getString(it) },
                        )
                    },
                )
            }
    }
}

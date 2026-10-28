package io.github.l0gic0l.andslice.printers

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A printer the user owns, like PrusaSlicer's physical printers: the profile it is sliced
 * with, and how to reach it over the network.
 */
data class SavedPrinter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** PrusaSlicer printer preset used for slicing. */
    val preset: String,
    /** Asset path of the vendor bundle that defines [preset]; empty for the bundles that are always loaded. */
    val bundle: String = "",
    /** OctoPrint or Moonraker address; empty when the printer is not connected. */
    val url: String = "",
    val apiKey: String = "",
    /** Camera stream or snapshot address; empty to ask the server. */
    val webcamUrl: String = "",
    /** Web interface address (Mainsail, Fluidd, OctoPrint); empty to find it. */
    val webUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("preset", preset).put("bundle", bundle)
        .put("url", url).put("api_key", apiKey).put("webcam_url", webcamUrl).put("web_url", webUrl)

    companion object {
        fun fromJson(o: JSONObject) = SavedPrinter(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            preset = o.optString("preset"),
            bundle = o.optString("bundle"),
            url = o.optString("url"),
            apiKey = o.optString("api_key"),
            webcamUrl = o.optString("webcam_url"),
            webUrl = o.optString("web_url"),
        )
    }
}

/** The saved printers and which one is active, in shared preferences. */
object PrinterStore {
    private const val PREFS = "printers"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    const val DEFAULT_PRESET = "Anycubic Kobra 3 0.4 nozzle"

    data class State(val printers: List<SavedPrinter>, val activeId: String)

    // Both view models read the store at start-up; the first one migrates old settings.
    @Synchronized
    fun load(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getString(KEY_LIST, null)?.let { json ->
            val a = JSONArray(json)
            (0 until a.length()).map { SavedPrinter.fromJson(a.getJSONObject(it)) }
        }.orEmpty()
        if (list.isEmpty()) return migrate(context).also { save(context, it) }
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> list.any { it.id == id } } ?: list.first().id
        return State(list, active)
    }

    @Synchronized
    fun save(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LIST, JSONArray(state.printers.map { it.toJson() }).toString())
            putString(KEY_ACTIVE, state.activeId)
        }
    }

    /** Vendor bundles the saved printers need, in addition to the built-in ones. */
    fun bundles(context: Context): List<String> = load(context).printers.map { it.bundle }.filter { it.isNotEmpty() }.distinct()

    /** First start, or an update from a version with a single printer: one Kobra 3 with the old connection. */
    private fun migrate(context: Context): State {
        val slicer = context.getSharedPreferences("andslice", Context.MODE_PRIVATE)
        val octoprint = context.getSharedPreferences("octoprint", Context.MODE_PRIVATE)
        val preset = slicer.getString("printer", null)?.takeIf { it.startsWith("Anycubic Kobra 3") } ?: DEFAULT_PRESET
        val printer = SavedPrinter(
            name = if ("Combo" in preset) "Kobra 3 Combo" else "Kobra 3",
            preset = preset,
            url = octoprint.getString("url", "") ?: "",
            apiKey = octoprint.getString("api_key", "") ?: "",
        )
        return State(listOf(printer), printer.id)
    }
}

package io.github.l0gic0l.andslice

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class ThemeMode { System, Light, Dark }

/** What the slicer shows beside the 3D view on tablets and unfolded foldables. */
enum class PanelMode {
    /** Half of the window, with the slicing settings under the profile chips. */
    Half,
    /** A slim panel with only the profile chips, from 840 dp. */
    Narrow,
    /** The bottom bar, as on phones. */
    Off,
}

/** How the app looks and behaves; separate from the slicing settings. */
class PreferencesViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var theme by enum("theme", ThemeMode.System)
    /** Colors from the wallpaper on Android 12 and later. */
    var dynamicColor by bool("dynamic_color", true)

    var sidePanel by enum("side_panel", PanelMode.Half)
    /** On a half-open foldable, the 3D view above the hinge and the controls below it. */
    var splitWhenFolded by bool("split_when_folded", true)

    /** Multisample anti-aliasing of the 3D view: 0, 2 or 4 samples. */
    var antialiasing by int("antialiasing", 4)
    /** Fraction of the screen resolution the 3D view is drawn at. */
    var renderScale by float("render_scale", 1f)
    var showGrid by bool("show_grid", true)

    var openPreviewAfterSlicing by bool("open_preview_after_slicing", true)
    var keepScreenOn by bool("keep_screen_on", true)
    /** Settings mode the settings open in: 0 Simple, 1 Advanced, 2 Expert. */
    var settingsMode by int("settings_mode", 1)

    /** A notification follows prints started from the app or seen on the printer screen. */
    var printNotifications by bool(KEY_PRINT_NOTIFICATIONS, true)
    /** The print notification shows a camera snapshot. */
    var notificationSnapshots by bool(KEY_NOTIFICATION_SNAPSHOTS, true)
    /** The app asked for Android's notification permission once already. */
    var askedNotificationPermission by bool("asked_notification_permission", false)

    companion object {
        /** Shared preferences file, also read by the print monitor service. */
        const val PREFS = "app_preferences"
        const val KEY_PRINT_NOTIFICATIONS = "print_notifications"
        const val KEY_NOTIFICATION_SNAPSHOTS = "notification_snapshots"
    }

    private fun bool(key: String, default: Boolean) =
        Pref(prefs, key, prefs.getBoolean(key, default)) { k, v -> putBoolean(k, v) }

    private fun int(key: String, default: Int) =
        Pref(prefs, key, prefs.getInt(key, default)) { k, v -> putInt(k, v) }

    private fun float(key: String, default: Float) =
        Pref(prefs, key, prefs.getFloat(key, default)) { k, v -> putFloat(k, v) }

    private inline fun <reified E : Enum<E>> enum(key: String, default: E) =
        Pref(prefs, key, prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default) { k, v ->
            putString(k, v.name)
        }
}

/** A preference that is Compose state and is written through to shared preferences. */
private class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    initial: T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    private var state by mutableStateOf(initial)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (value == state) return
        state = value
        prefs.edit { write(key, value) }
    }
}

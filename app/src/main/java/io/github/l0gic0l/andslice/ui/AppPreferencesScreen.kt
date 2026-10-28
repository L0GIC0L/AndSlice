package io.github.l0gic0l.andslice.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.BuildConfig
import io.github.l0gic0l.andslice.PanelMode
import io.github.l0gic0l.andslice.PreferencesViewModel
import io.github.l0gic0l.andslice.ThemeMode

/** One row of a preferences group: a switch, or a choice between a few values. */
private sealed interface PrefRow {
    data class Toggle(
        val title: String,
        val summary: String?,
        val checked: Boolean,
        val enabled: Boolean = true,
        val onChange: (Boolean) -> Unit,
    ) : PrefRow

    data class Segments(
        val title: String,
        val summary: String?,
        val options: List<String>,
        val selected: Int,
        val onSelect: (Int) -> Unit,
    ) : PrefRow

    data class Action(val title: String, val summary: String?, val onClick: () -> Unit) : PrefRow
}

/** How the app looks and behaves, as opposed to the slicing settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPreferencesScreen(prefs: PreferencesViewModel, onBack: (() -> Unit)?) {
    var showAbout by remember { mutableStateOf(false) }
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scales = listOf(1f, 0.75f, 0.5f)
    val samples = listOf(0, 2, 4)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Preferences") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
                group(
                    "Appearance",
                    PrefRow.Segments(
                        "Theme", null, listOf("System", "Light", "Dark"), prefs.theme.ordinal,
                    ) { prefs.theme = ThemeMode.entries[it] },
                    PrefRow.Toggle(
                        "Wallpaper colors",
                        if (dynamicColorAvailable) "Material You colors from your wallpaper" else "Needs Android 12 or later",
                        prefs.dynamicColor && dynamicColorAvailable,
                        enabled = dynamicColorAvailable,
                    ) { prefs.dynamicColor = it },
                )
                group(
                    "Layout",
                    PrefRow.Segments(
                        "Side panel on large screens",
                        when (prefs.sidePanel) {
                            PanelMode.Half -> "Half of the screen, with the slicing settings under the profiles"
                            PanelMode.Narrow -> "A slim panel with the profiles, on screens 840 dp and wider"
                            PanelMode.Off -> "Profiles in a bar under the plate, as on phones"
                        },
                        listOf("Half", "Narrow", "Off"), prefs.sidePanel.ordinal,
                    ) { prefs.sidePanel = PanelMode.entries[it] },
                    PrefRow.Toggle(
                        "Split at the hinge",
                        "On a half-open foldable, the plate above the hinge and the controls below",
                        prefs.splitWhenFolded,
                    ) { prefs.splitWhenFolded = it },
                )
                group(
                    "3D view",
                    PrefRow.Segments(
                        "Anti-aliasing", "Smoother edges, for a little more GPU work",
                        listOf("Off", "2×", "4×"), samples.indexOf(prefs.antialiasing).coerceAtLeast(0),
                    ) { prefs.antialiasing = samples[it] },
                    PrefRow.Segments(
                        "Resolution", "Lower is faster on slow GPUs and saves battery",
                        listOf("100%", "75%", "50%"), scales.indexOf(prefs.renderScale).coerceAtLeast(0),
                    ) { prefs.renderScale = scales[it] },
                    PrefRow.Toggle("Bed grid", null, prefs.showGrid) { prefs.showGrid = it },
                )
                group(
                    "Behavior",
                    PrefRow.Toggle(
                        "Open the preview after slicing",
                        "Otherwise stay on the plate",
                        prefs.openPreviewAfterSlicing,
                    ) { prefs.openPreviewAfterSlicing = it },
                    PrefRow.Toggle(
                        "Keep the screen on",
                        "While the printer screen is open, e.g. to watch the camera",
                        prefs.keepScreenOn,
                    ) { prefs.keepScreenOn = it },
                    PrefRow.Segments(
                        "Settings open in", null, listOf("Simple", "Advanced", "Expert"), prefs.settingsMode,
                    ) { prefs.settingsMode = it },
                )
                group(
                    "About",
                    PrefRow.Action("AndSlice ${BuildConfig.VERSION_NAME}", "PrusaSlicer 2.9.6 engine · licenses") { showAbout = true },
                )
            }
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

private fun LazyListScope.group(title: String, vararg rows: PrefRow) {
    item(key = "header-$title") { SectionHeader(title) }
    rows.forEachIndexed { i, row ->
        item(key = "$title-$i") { PrefRowItem(row, groupShape(i, rows.size)) }
    }
}

@Composable
private fun PrefRowItem(row: PrefRow, shape: Shape) {
    val block = Modifier
        .padding(horizontal = 16.dp)
        .padding(bottom = 2.dp)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    when (row) {
        is PrefRow.Toggle -> ListItem(
            headlineContent = { Text(row.title) },
            supportingContent = row.summary?.let { { Text(it) } },
            trailingContent = { Switch(checked = row.checked, onCheckedChange = null, enabled = row.enabled) },
            colors = colors,
            modifier = block.toggleable(row.checked, enabled = row.enabled, role = Role.Switch, onValueChange = row.onChange),
        )
        is PrefRow.Segments -> ListItem(
            headlineContent = { Text(row.title) },
            supportingContent = {
                Column {
                    row.summary?.let { Text(it) }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        row.options.forEachIndexed { i, option ->
                            SegmentedButton(
                                selected = i == row.selected,
                                onClick = { if (i != row.selected) row.onSelect(i) },
                                shape = SegmentedButtonDefaults.itemShape(i, row.options.size),
                            ) { Text(option, maxLines = 1) }
                        }
                    }
                }
            },
            colors = colors,
            modifier = block,
        )
        is PrefRow.Action -> ListItem(
            headlineContent = { Text(row.title) },
            supportingContent = row.summary?.let { { Text(it) } },
            colors = colors,
            modifier = block.clickable(onClick = row.onClick),
        )
    }
}

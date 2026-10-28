package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PreferencesViewModel
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode
import java.util.Locale

/**
 * The slicer's side panel on tablets and foldables, like a desktop slicer's sidebar: the plate's
 * printer, profiles, filaments, quick settings and objects on one tab, every slicing setting on the
 * other, and the Slice button (or, in the preview, the print and save actions) docked at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidePanel(
    vm: SlicerViewModel,
    printer: PrinterViewModel,
    prefs: PreferencesViewModel,
    onAddPrinter: () -> Unit,
    onOpenPrinter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Plate") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") }, modifier = Modifier.testTag("panel-settings"))
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                PlateOverview(vm, printer, onAddPrinter)
            } else {
                SettingsScreen(
                    vm, onBack = null, embedded = true,
                    defaultMode = prefs.settingsMode, onPrinterPresetSaved = printer::setActivePreset,
                )
            }
        }
        HorizontalDivider()
        if (vm.mode == ViewMode.Preview) {
            PreviewBottomBar(vm, printer, onOpenPrinter, inPanel = true)
        } else {
            SliceBar(vm, prefs)
        }
    }
}

/** The Slice button, docked under the panel. */
@Composable
private fun SliceBar(vm: SlicerViewModel, prefs: PreferencesViewModel) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Button(
            onClick = { vm.slice(context, prefs.openPreviewAfterSlicing) },
            enabled = vm.objects.isNotEmpty() && !vm.slicing && vm.paintMode == null,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        ) {
            Icon(Icons.Default.Layers, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (vm.objects.isEmpty()) "Add a model to slice" else "Slice")
        }
    }
}

/** Printer, profiles, filaments, quick settings and the objects on the plate, as list rows. */
@Composable
private fun PlateOverview(vm: SlicerViewModel, printers: PrinterViewModel, onAddPrinter: () -> Unit) {
    val presets = vm.presets ?: return
    var sheet by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        section("Printer")
        item(key = "printer") {
            val nozzle = presets.variant.takeIf { presets.variants.size > 1 }?.let { "$it mm nozzle" }
            PanelRow(
                Icons.Default.Print, printers.active?.name ?: shortPresetName(vm.printer),
                nozzle ?: shortPresetName(vm.printer), description = "Printer profile",
            ) { sheet = PlateSheets.PRINTER }
        }
        item(key = "print") {
            PanelRow(Icons.Default.Layers, shortPresetName(vm.print), "Print profile") { sheet = PlateSheets.PRINT }
        }

        section(if (vm.multiMaterial) "Filaments" else "Filament")
        items(vm.filaments.indices.toList(), key = { "slot-$it" }) { slot ->
            val unused = vm.multiMaterial && vm.objects.isNotEmpty() && (slot + 1) !in vm.usedExtruders
            ListItem(
                headlineContent = { Text(shortFilamentName(vm.filaments[slot]), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (vm.multiMaterial) ({ Text("ACE slot ${slot + 1}" + if (unused) " · not used" else "") }) else null,
                leadingContent = {
                    SlotDot(vm.slotColors.getOrElse(slot) { "#808080" }, if (vm.multiMaterial) slot + 1 else null, 28.dp)
                },
                trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { sheet = PlateSheets.filament(slot) },
            )
        }

        section("Quick settings")
        item(key = "supports") {
            PanelRow(Icons.Default.Park, "Supports", supportsLabel(vm)) { sheet = PlateSheets.SUPPORTS }
        }
        item(key = "infill") {
            PanelRow(Icons.Default.GridOn, "Infill", vm.value("fill_density") ?: "-") { sheet = PlateSheets.INFILL }
        }
        item(key = "brim") {
            ListItem(
                headlineContent = { Text("Brim") },
                supportingContent = { Text("Extra lines around the first layer, for better adhesion") },
                leadingContent = { Icon(Icons.Default.BorderOuter, null) },
                trailingContent = { Switch(checked = hasBrim(vm), onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { toggleBrim(vm) },
            )
        }

        section("Objects")
        if (vm.objects.isEmpty()) {
            item(key = "no-objects") {
                Text(
                    "The plate is empty. Import a model with +.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        items(vm.objects, key = { "object-${it.id}" }) { obj ->
            val selected = obj.id == vm.selectedId
            ListItem(
                headlineContent = { Text(obj.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        String.format(Locale.US, "%.1f × %.1f × %.1f mm", obj.size[0], obj.size[1], obj.size[2]) +
                            if (obj.outside) " · outside the bed" else "",
                        color = if (obj.outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    if (vm.multiMaterial) {
                        val slot = obj.extruders.singleOrNull()
                        SlotDot(vm.slotColors.getOrElse((slot ?: 1) - 1) { "#808080" }, slot, 28.dp)
                    } else {
                        Icon(Icons.Default.ViewInAr, null)
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable { vm.select(if (selected) -1 else obj.id) },
            )
        }
    }
    PlateSheet(vm, printers, sheet, onDismiss = { sheet = null }, onAddPrinter = onAddPrinter)
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
}

/** A row that opens a choice: icon, the current value, and what it is. */
@Composable
private fun PanelRow(icon: ImageVector, value: String, label: String, description: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, description) },
        trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

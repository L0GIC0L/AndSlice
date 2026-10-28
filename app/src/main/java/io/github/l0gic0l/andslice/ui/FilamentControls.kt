package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.PlateObject

/** Common filament colors offered for the slots. */
private val FILAMENT_COLORS = listOf(
    "#F2F2F2", "#2B2B2B", "#9E9E9E", "#D32F2F", "#F57C00", "#FBC02D", "#7CB342", "#2E7D32",
    "#00897B", "#29B2B2", "#1E88E5", "#283593", "#8E24AA", "#EC407A", "#8D6E63", "#C9A66B",
)

fun parseHexColor(hex: String): Color =
    hex.removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000L or it) } ?: Color.Gray

/** "Anycubic PLA High Speed @Kobra 3" -> "PLA High Speed". */
fun shortFilamentName(name: String) = shortPresetName(name).removePrefix("Anycubic ").removePrefix("Generic ")

/** Round swatch in a filament color, optionally with the slot number on it. */
@Composable
fun SlotDot(color: String, number: Int? = null, size: Dp = 20.dp, selected: Boolean = false) {
    val fill = parseHexColor(color)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (fill.luminance() > 0.5f) Color.Black else Color.White
        when {
            selected -> Icon(Icons.Default.Check, null, tint = ink, modifier = Modifier.size(size * 0.6f))
            number != null -> Text("$number", color = ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Filament chips of the plate bar: one per extruder, which on the Kobra 3 Combo is one per ACE slot. */
@Composable
fun FilamentChips(vm: SlicerViewModel, onOpen: (slot: Int) -> Unit) {
    vm.filaments.forEachIndexed { slot, name ->
        val color = vm.slotColors.getOrElse(slot) { "#808080" }
        val unused = vm.multiMaterial && vm.objects.isNotEmpty() && (slot + 1) !in vm.usedExtruders
        AssistChip(
            onClick = { onOpen(slot) },
            label = {
                Text(
                    shortFilamentName(name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (unused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            },
            leadingIcon = { SlotDot(color, if (vm.multiMaterial) slot + 1 else null, 18.dp) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.semantics { contentDescription = "Filament ${slot + 1}" },
        )
    }
}

/** Filament preset and color of one slot. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilamentSheet(vm: SlicerViewModel, slot: Int, onDismiss: () -> Unit) {
    val presets = vm.presets ?: return
    val selected = vm.filaments.getOrNull(slot) ?: return
    val color = vm.slotColors.getOrElse(slot) { "#808080" }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                if (vm.multiMaterial) "Filament ${slot + 1}" else "Filament",
                style = MaterialTheme.typography.titleMedium,
            )
            if (vm.multiMaterial) {
                Text(
                    "ACE Pro slot ${slot + 1}. Pick the color that is loaded there; it is used on the plate and in the preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (FILAMENT_COLORS + color).distinct().forEach { c ->
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .selectable(selected = c.equals(color, true), role = Role.RadioButton) { vm.setSlotColor(slot, c) }
                            .semantics { contentDescription = "Color $c" },
                    ) {
                        SlotDot(c, size = 36.dp, selected = c.equals(color, true))
                    }
                }
            }
            Text("Material", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        }
        var query by remember { mutableStateOf("") }
        val all = presets.allFilaments
        if (all.size > 12) SearchField(query, { query = it }, "Search ${all.size} materials")
        val own = presets.filaments.filter { query.isBlank() || shortFilamentName(it).contains(query, true) }
        val templates = presets.templateFilaments.filter { query.isBlank() || shortFilamentName(it).contains(query, true) }
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(own, key = { it }) { name -> FilamentRow(name, name == selected) { onDismiss(); if (name != selected) vm.selectFilament(slot, name) } }
            if (templates.isNotEmpty()) {
                item(key = "templates") {
                    Text(
                        "Generic (PrusaSlicer templates)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(templates, key = { it }) { name -> FilamentRow(name, name == selected) { onDismiss(); if (name != selected) vm.selectFilament(slot, name) } }
            }
        }
    }
}

@Composable
private fun FilamentRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(shortFilamentName(name)) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

/** Which filament slot prints the object, or each of its parts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectFilamentSheet(vm: SlicerViewModel, obj: PlateObject, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Filament for ${obj.name}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val whole = obj.extruders.singleOrNull()
            SlotPicker(vm, "Whole object", whole) { vm.setObjectExtruder(obj.id, -1, it) }
            if (obj.parts.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Parts",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                obj.parts.forEachIndexed { i, part ->
                    SlotPicker(vm, part.name.ifEmpty { "Part ${i + 1}" }, part.extruder) { vm.setObjectExtruder(obj.id, i, it) }
                }
            }
            if (obj.painted) {
                Text(
                    "Painted areas keep their own filaments. Paint more with the brush in the toolbar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotPicker(vm: SlicerViewModel, label: String, selected: Int?, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in 1..vm.extruderCount) {
                FilterChip(
                    selected = slot == selected,
                    onClick = { if (slot != selected) onSelect(slot) },
                    label = { Text(shortFilamentName(vm.filaments.getOrElse(slot - 1) { "" }), maxLines = 1) },
                    leadingIcon = { SlotDot(vm.slotColors.getOrElse(slot - 1) { "#808080" }, slot, 18.dp) },
                )
            }
        }
    }
}

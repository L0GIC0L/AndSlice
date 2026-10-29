package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.HeightRange
import io.github.l0gic0l.andslice.engine.OptionDef
import io.github.l0gic0l.andslice.engine.OptionDefs
import io.github.l0gic0l.andslice.engine.PlateObject

/** Settings people most often change for one object, offered first. */
private val COMMON_OPTIONS = listOf(
    "layer_height", "perimeters", "top_solid_layers", "bottom_solid_layers", "fill_density", "fill_pattern",
    "support_material", "brim_width", "seam_position", "ironing", "fuzzy_skin", "zaa_enabled",
)

/**
 * The selected object's own settings, as PrusaSlicer's object list has them: variable layer height,
 * options over the print profile for this object only, and height ranges with options of their own.
 */
@Composable
fun ObjectSettingsPanel(vm: SlicerViewModel, obj: PlateObject, modifier: Modifier = Modifier) {
    val defs = vm.optionDefs ?: return
    val settings = vm.objectSettings
    /** Where a picked option goes: the object (-1) or the range starting there; null while not picking. */
    var picking by remember { mutableStateOf<Double?>(null) }
    var addingRange by remember { mutableStateOf(false) }
    var editingRange by remember { mutableStateOf<HeightRange?>(null) }

    ToolPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Object settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.openObjectSettings(false) }) { Text("Done") }
        }

        // Variable layer height
        val variable = obj.layerQuality >= 0f || obj.layerProfile
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Variable layer height", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Thin layers on curves and slopes, thick ones on straight walls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = variable, onCheckedChange = { vm.setLayerQuality(if (it) 0.5f else -1f) })
        }
        if (variable) {
            var quality by remember(obj.id, obj.layerQuality) { mutableFloatStateOf(obj.layerQuality.takeIf { it >= 0f } ?: 0.5f) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Finer", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    onValueChangeFinished = { vm.setLayerQuality(quality) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Layer detail" },
                )
                Text("Faster", style = MaterialTheme.typography.labelLarge)
            }
            val limits = listOfNotNull(firstValue(vm.value("min_layer_height")), firstValue(vm.value("max_layer_height"))?.takeIf { it > 0 })
            Text(
                (if (obj.layerQuality < 0f) "These layer heights came with the project; the slider works them out again. " else "") +
                    (if (limits.size == 2) "Layers stay between ${fmt(limits[0])} and ${fmt(limits[1])} mm, the nozzle's limits." else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        // Options for this object
        SectionTitle("This object", "Add setting") { picking = -1.0 }
        if (settings.options.isEmpty()) Note("Prints with the print profile's settings. Add one to change it for this object only.")
        settings.options.forEach { (key, value) ->
            SettingRow(defs, key, value, removable = true, onChange = { vm.setObjectOption(-1.0, key, it) }) {
                vm.setObjectOption(-1.0, key, "")
            }
        }
        HorizontalDivider()

        // Height ranges
        SectionTitle("Height ranges", "Add range") { addingRange = true }
        if (settings.ranges.isEmpty()) Note("Other settings between two heights, such as finer layers for a detailed top.")
        settings.ranges.forEach { range ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { editingRange = range }) { Text("${fmt(range.min)} – ${fmt(range.max)} mm") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { picking = range.min }) { Icon(Icons.Default.Add, "Add a setting to this range") }
                        IconButton(onClick = { vm.editHeightRange(range.min, 0.0, 0.0) }) { Icon(Icons.Default.Delete, "Remove this range") }
                    }
                    range.options.forEach { (key, value) ->
                        // PrusaSlicer needs every range's layer height.
                        SettingRow(defs, key, value, removable = key != "layer_height", onChange = { vm.setObjectOption(range.min, key, it) }) {
                            vm.setObjectOption(range.min, key, "")
                        }
                    }
                }
            }
        }
        if (variable && settings.ranges.isNotEmpty()) {
            Note("With variable layer height on, it decides the layer heights; the ranges' other settings still apply.")
        }
    }

    picking?.let { rangeMin ->
        val allowed = if (rangeMin < 0) settings.objectKeys else settings.rangeKeys
        val existing = if (rangeMin < 0) settings.options.keys else settings.ranges.firstOrNull { it.min == rangeMin }?.options?.keys.orEmpty()
        OptionPickerSheet(defs, allowed - existing, onDismiss = { picking = null }) { key ->
            picking = null
            // Starts from the value the object prints with now.
            vm.value(key)?.let { vm.setObjectOption(rangeMin, key, it) }
        }
    }
    if (addingRange) {
        val height = obj.size[2]
        RangeDialog("Add a height range", height / 2, height, onDismiss = { addingRange = false }) { min, max ->
            addingRange = false
            vm.editHeightRange(-1.0, min, max)
        }
    }
    editingRange?.let { range ->
        RangeDialog("Height range", range.min, range.max, onDismiss = { editingRange = null }) { min, max ->
            editingRange = null
            vm.editHeightRange(range.min, min, max)
        }
    }
}

/** The first number of a setting, which holds one value per extruder for some options. */
private fun firstValue(serialized: String?): Double? = serialized?.split(',')?.firstOrNull()?.trim()?.toDoubleOrNull()

@Composable
private fun SectionTitle(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(4.dp))
            Text(action)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingRow(defs: OptionDefs, key: String, value: String, removable: Boolean, onChange: (String) -> Unit, onRemove: () -> Unit) {
    val def = defs.find(key)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(def?.fullLabel ?: key, style = MaterialTheme.typography.bodyMedium)
            def?.sidetext?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.widthIn(min = 72.dp, max = 150.dp)) {
            if (def != null) OptionEditor(def, value, onChange) else Text(value)
        }
        IconButton(onClick = onRemove, enabled = removable) { Icon(Icons.Default.RemoveCircleOutline, "Use the profile's ${def?.label ?: key}") }
    }
}

/** Picks an option to override: the common ones first, then all the others, with search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionPickerSheet(defs: OptionDefs, allowed: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember(allowed) {
        allowed.mapNotNull { defs.find(it) }.sortedWith(compareBy<OptionDef> { it.key !in COMMON_OPTIONS }
            .thenBy { COMMON_OPTIONS.indexOf(it.key) }.thenBy { it.category }.thenBy { it.fullLabel })
    }
    val shown = all.filter { query.isBlank() || it.fullLabel.contains(query, true) || it.category.contains(query, true) || it.key.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Add setting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${all.size} settings") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(shown, key = { it.key }) { def ->
                    ListItem(
                        headlineContent = { Text(def.fullLabel) },
                        supportingContent = { Text(def.category) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(def.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeDialog(title: String, min: Double, max: Double, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var from by remember { mutableStateOf(fmt(min)) }
    var to by remember { mutableStateOf(fmt(max)) }
    val lo = from.replace(',', '.').toDoubleOrNull()
    val hi = to.replace(',', '.').toDoubleOrNull()
    val valid = lo != null && hi != null && lo >= 0 && hi > lo
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("From", "mm", from, { from = it }, Modifier.fillMaxWidth())
                NumberField("To", "mm", to, { to = it }, Modifier.fillMaxWidth())
                Text("Heights above the bed, measured on the object.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(lo!!, hi!!) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

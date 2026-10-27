package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.PlateObject
import java.util.Locale

/** "0.20mm Standard @Kobra 3 0.4 nozzle" -> "0.20mm Standard". */
fun shortPresetName(name: String) = name.substringBefore(" @").removePrefix("Anycubic Kobra 3 ")

@Composable
fun PlateBottomBar(vm: SlicerViewModel) {
    val presets = vm.presets ?: return
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetPicker("Nozzle", vm.printer, presets.printers, vm::selectPrinter, Modifier.weight(0.8f))
            PresetPicker("Print", vm.print, presets.prints, vm::selectPrint, Modifier.weight(1.2f))
            PresetPicker("Filament", vm.filament, presets.filaments, vm::selectFilament, Modifier.weight(1.2f))
        }
        QuickSettings(vm)
        Button(
            onClick = { vm.slice(context) },
            enabled = vm.objects.isNotEmpty() && !vm.slicing,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Slice")
        }
        if (vm.result != null) {
            TextButton(onClick = vm::showPreview, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (vm.resultStale) "Show previous preview (out of date)" else "Show preview")
            }
        }
    }
}

@Composable
private fun PresetPicker(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(shortPresetName(selected), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(shortPresetName(option)) },
                    onClick = {
                        open = false
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickSettings(vm: SlicerViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Supports: off / touching the build plate / everywhere.
        val supportOn = vm.value("support_material") == "1"
        val plateOnly = vm.value("support_material_buildplate_only") == "1"
        val supportIndex = when {
            !supportOn -> 0
            plateOnly -> 1
            else -> 2
        }
        Column(Modifier.weight(1.6f)) {
            Text("Supports", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Off", "Plate", "All").forEachIndexed { i, text ->
                    SegmentedButton(
                        selected = supportIndex == i,
                        onClick = {
                            vm.setOverride("support_material", if (i == 0) "0" else "1")
                            if (i > 0) vm.setOverride("support_material_buildplate_only", if (i == 1) "1" else "0")
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        icon = {},
                    ) { Text(text, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Column(Modifier.weight(0.8f)) {
            Text("Infill", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text(vm.value("fill_density") ?: "-", style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    listOf(0, 5, 10, 15, 20, 25, 30, 40, 50, 60, 80, 100).forEach { p ->
                        DropdownMenuItem(text = { Text("$p%") }, onClick = {
                            open = false
                            vm.setOverride("fill_density", "$p%")
                        })
                    }
                }
            }
        }
        Column(Modifier.weight(0.6f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Brim", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val brim = (vm.value("brim_width")?.toDoubleOrNull() ?: 0.0) > 0.0
            Switch(checked = brim, onCheckedChange = { vm.setOverride("brim_width", if (it) "5" else "0") })
        }
    }
}

@Composable
fun ObjectToolbar(vm: SlicerViewModel, obj: PlateObject, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(obj.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        String.format(Locale.US, "%.1f × %.1f × %.1f mm", obj.size[0], obj.size[1], obj.size[2]) +
                            if (obj.outside) "  · outside the bed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (obj.outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.select(-1) }) { Icon(Icons.Default.Close, "Deselect") }
            }
            Row {
                FilledIconToggleButton(checked = vm.layFaceMode, onCheckedChange = { vm.toggleLayFaceMode() }) {
                    Icon(Icons.Default.FlipToBack, "Lay on face")
                }
                IconButton(onClick = { vm.rotateSelected(2, 90.0) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate 90°") }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Move, rotate, scale") }
                IconButton(onClick = vm::centerSelected) { Icon(Icons.Default.CenterFocusStrong, "Center") }
                IconButton(onClick = vm::duplicateSelected) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                IconButton(onClick = vm::deleteSelected) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
    if (editing) TransformDialog(obj, onDismiss = { editing = false }, onApply = { t ->
        editing = false
        vm.setTransform(obj.id, t)
    })
}

@Composable
private fun TransformDialog(obj: PlateObject, onDismiss: () -> Unit, onApply: (DoubleArray) -> Unit) {
    fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    var x by remember { mutableStateOf(fmt(obj.offset[0])) }
    var y by remember { mutableStateOf(fmt(obj.offset[1])) }
    var rx by remember { mutableStateOf(fmt(obj.rotation[0])) }
    var ry by remember { mutableStateOf(fmt(obj.rotation[1])) }
    var rz by remember { mutableStateOf(fmt(obj.rotation[2])) }
    var scale by remember { mutableStateOf(fmt(obj.scale[0] * 100)) }
    val values = listOf(x, y, rx, ry, rz, scale).map { it.replace(',', '.').toDoubleOrNull() }
    val valid = values.all { it != null } && (values[5] ?: 0.0) > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transform") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("X (mm)", x, { x = it }, Modifier.weight(1f))
                    NumberField("Y (mm)", y, { y = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Rot X°", rx, { rx = it }, Modifier.weight(1f))
                    NumberField("Rot Y°", ry, { ry = it }, Modifier.weight(1f))
                    NumberField("Rot Z°", rz, { rz = it }, Modifier.weight(1f))
                }
                NumberField("Scale (%)", scale, { scale = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val v = values.map { it!! }
                val s = v[5] / 100.0
                onApply(doubleArrayOf(v[0], v[1], v[2], v[3], v[4], s, s, s))
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

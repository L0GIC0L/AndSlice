package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.PlateObject
import java.util.Locale
import kotlin.math.roundToInt

/** "0.20mm Standard @Kobra 3 0.4 nozzle" -> "0.20mm Standard"; "Anycubic Kobra 3 0.4 nozzle" -> "0.4 nozzle". */
fun shortPresetName(name: String) = name.substringBefore(" @").removePrefix("Anycubic Kobra 3 ")

/** One choice in a [ChoiceSheet]. */
data class Choice(val value: String, val label: String, val description: String? = null)

/** Material 3 bottom sheet with a single-choice list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(title: String, choices: List<Choice>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(choices, key = { it.value }) { choice ->
                ListItem(
                    headlineContent = { Text(choice.label) },
                    supportingContent = choice.description?.let { { Text(it) } },
                    leadingContent = { RadioButton(selected = choice.value == selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.selectable(selected = choice.value == selected, role = Role.RadioButton) {
                        onDismiss()
                        if (choice.value != selected) onSelect(choice.value)
                    },
                )
            }
        }
    }
}

@Composable
fun PlateBottomBar(vm: SlicerViewModel) {
    val presets = vm.presets ?: return
    var sheet by remember { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
            // Profiles
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(Icons.Default.RadioButtonChecked, shortPresetName(vm.printer), "Nozzle") { sheet = "printer" }
                PickerChip(Icons.Default.Layers, shortPresetName(vm.print), "Print profile") { sheet = "print" }
                PickerChip(Icons.Default.Opacity, shortPresetName(vm.filament), "Filament") { sheet = "filament" }
            }
            // Quick settings
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val supports = supportsChoice(vm)
                FilterChip(
                    selected = supports != "off",
                    onClick = { sheet = "supports" },
                    label = { Text(SUPPORT_CHOICES.first { it.value == supports }.label) },
                    leadingIcon = { Icon(Icons.Default.Park, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                )
                FilterChip(
                    selected = true,
                    onClick = { sheet = "infill" },
                    label = { Text("Infill ${vm.value("fill_density") ?: "-"}") },
                    leadingIcon = { Icon(Icons.Default.GridOn, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                )
                val brim = (vm.value("brim_width")?.toDoubleOrNull() ?: 0.0) > 0.0
                FilterChip(
                    selected = brim,
                    onClick = { vm.setOverride("brim_width", if (brim) "0" else "5") },
                    label = { Text("Brim") },
                    leadingIcon = {
                        Icon(if (brim) Icons.Default.Check else Icons.Default.BorderOuter, null, Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
        }
    }

    when (sheet) {
        "printer" -> ChoiceSheet("Nozzle", presets.printers.map { Choice(it, shortPresetName(it)) }, vm.printer,
            onDismiss = { sheet = null }, onSelect = vm::selectPrinter)
        "print" -> ChoiceSheet("Print profile", presets.prints.map { Choice(it, shortPresetName(it)) }, vm.print,
            onDismiss = { sheet = null }, onSelect = vm::selectPrint)
        "filament" -> ChoiceSheet("Filament", presets.filaments.map { Choice(it, shortPresetName(it)) }, vm.filament,
            onDismiss = { sheet = null }, onSelect = vm::selectFilament)
        "supports" -> ChoiceSheet("Supports", SUPPORT_CHOICES, supportsChoice(vm), onDismiss = { sheet = null }) { choice ->
            vm.setOverride("support_material", if (choice == "off") "0" else "1")
            if (choice != "off") vm.setOverride("support_material_buildplate_only", if (choice == "plate") "1" else "0")
        }
        "infill" -> InfillSheet(vm, onDismiss = { sheet = null })
    }
}

private val SUPPORT_CHOICES = listOf(
    Choice("off", "No supports"),
    Choice("plate", "Supports on plate", "Only where supports can stand on the bed"),
    Choice("all", "Supports everywhere", "Also on top of the model"),
)

private fun supportsChoice(vm: SlicerViewModel) = when {
    vm.value("support_material") != "1" -> "off"
    vm.value("support_material_buildplate_only") == "1" -> "plate"
    else -> "all"
}

@Composable
private fun PickerChip(icon: ImageVector, value: String, description: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, description, Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfillSheet(vm: SlicerViewModel, onDismiss: () -> Unit) {
    val current = vm.value("fill_density")?.removeSuffix("%")?.toFloatOrNull() ?: 15f
    var value by remember { mutableFloatStateOf(current) }
    ModalBottomSheet(onDismissRequest = {
        vm.setOverride("fill_density", "${value.roundToInt()}%")
        onDismiss()
    }) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text("Infill density", style = MaterialTheme.typography.titleMedium)
            Text("${value.roundToInt()}%", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f, steps = 19)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 15, 25, 50, 100).forEach { p ->
                    FilterChip(selected = value.roundToInt() == p, onClick = { value = p.toFloat() }, label = { Text("$p%") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "15% suits most prints; use more for parts that carry load.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Floating toolbar for the selected object. */
@Composable
fun ObjectToolbar(vm: SlicerViewModel, obj: PlateObject, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(obj.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        String.format(Locale.US, "%.1f × %.1f × %.1f mm", obj.size[0], obj.size[1], obj.size[2]) +
                            if (obj.outside) " · outside the bed" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (obj.outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                IconButton(onClick = { vm.select(-1) }) { Icon(Icons.Default.Close, "Deselect") }
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
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
        icon = { Icon(Icons.Default.Edit, null) },
        title = { Text("Position, rotation and scale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("X", "mm", x, { x = it }, Modifier.weight(1f))
                    NumberField("Y", "mm", y, { y = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("X", "°", rx, { rx = it }, Modifier.weight(1f))
                    NumberField("Y", "°", ry, { ry = it }, Modifier.weight(1f))
                    NumberField("Z", "°", rz, { rz = it }, Modifier.weight(1f))
                }
                NumberField("Scale", "%", scale, { scale = it }, Modifier.fillMaxWidth())
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
fun NumberField(label: String, suffix: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

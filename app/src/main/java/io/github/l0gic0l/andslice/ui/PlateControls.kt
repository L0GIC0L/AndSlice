package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Park
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.PaintMode
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

/**
 * Profile chips and quick settings under the plate. With [wide] (tablets, unfolded
 * foldables) the chips wrap in a side panel instead of scrolling in a bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlateBottomBar(vm: SlicerViewModel, printers: PrinterViewModel, onAddPrinter: () -> Unit, wide: Boolean = false) {
    val presets = vm.presets ?: return
    var sheet by remember { mutableStateOf<String?>(null) }

    @Composable
    fun ChipRow(content: @Composable () -> Unit) {
        if (wide) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.navigationBarsPadding()).padding(vertical = 8.dp)) {
            // Profiles
            ChipRow {
                val printerName = printers.active?.name ?: shortPresetName(vm.printer)
                val nozzle = presets.variant.takeIf { presets.variants.size > 1 }?.let { " · $it" }.orEmpty()
                PickerChip(Icons.Default.Print, printerName + nozzle, "Printer profile") { sheet = "printer" }
                PickerChip(Icons.Default.Layers, shortPresetName(vm.print), "Print profile") { sheet = "print" }
                FilamentChips(vm) { slot -> sheet = "filament:$slot" }
            }
            // Quick settings
            ChipRow {
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

    when (val s = sheet) {
        "printer" -> PrinterSheet(vm, printers, onDismiss = { sheet = null }, onAddPrinter = onAddPrinter)
        "print" -> ChoiceSheet("Print profile", presets.prints.map { Choice(it, shortPresetName(it)) }, vm.print,
            onDismiss = { sheet = null }, onSelect = vm::selectPrint)
        else -> if (s != null && s.startsWith("filament:")) {
            FilamentSheet(vm, s.substringAfter(':').toInt(), onDismiss = { sheet = null })
        }
    }
    when (sheet) {
        "supports" -> ChoiceSheet("Supports", SUPPORT_CHOICES, supportsChoice(vm), onDismiss = { sheet = null }) { choice ->
            vm.setOverride("support_material", if (choice == "off") "0" else "1")
            if (choice != "off") {
                // PrusaSlicer's "For support enforcers only": no automatic supports, only the painted ones.
                vm.setOverride("support_material_auto", if (choice == "painted") "0" else "1")
                vm.setOverride("support_material_buildplate_only", if (choice == "plate") "1" else "0")
            }
        }
        "infill" -> InfillSheet(vm, onDismiss = { sheet = null })
    }
}

/** The saved printers, and the nozzle variants of the active one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PrinterSheet(vm: SlicerViewModel, printers: PrinterViewModel, onDismiss: () -> Unit, onAddPrinter: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Printer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            printers.printers.forEach { p ->
                val selected = p.id == printers.activeId
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(shortPresetName(p.preset) + if (p.url.isNotBlank()) " · connected" else "") },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { printers.setActive(p.id) },
                )
            }
            val variants = vm.presets?.variants.orEmpty()
            if (variants.size > 1) {
                Text("Nozzle", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
                FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    variants.forEach { (variant, preset) ->
                        FilterChip(
                            selected = preset == vm.printer,
                            onClick = { printers.setActivePreset(preset) },
                            label = { Text("$variant mm") },
                        )
                    }
                }
            }
            TextButton(onClick = { onDismiss(); onAddPrinter() }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add printer")
            }
        }
    }
}

private val SUPPORT_CHOICES = listOf(
    Choice("off", "No supports"),
    Choice("plate", "Supports on plate", "Only where supports can stand on the bed"),
    Choice("all", "Supports everywhere", "Also on top of the model"),
    Choice("painted", "Only where painted", "Paint supports on a selected object with the brush"),
)

private fun supportsChoice(vm: SlicerViewModel) = when {
    vm.value("support_material") != "1" -> "off"
    vm.value("support_material_auto") == "0" -> "painted"
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
    var choosingFilament by remember { mutableStateOf(false) }
    var paintMenu by remember { mutableStateOf(false) }
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
                FilledIconToggleButton(checked = vm.transformOpen, onCheckedChange = { vm.transformOpen = it }) {
                    Icon(Icons.Default.OpenWith, "Move, rotate, scale")
                }
                FilledIconToggleButton(checked = vm.layFaceMode, onCheckedChange = { vm.toggleLayFaceMode() }) {
                    Icon(Icons.Default.FlipToBack, "Lay on face")
                }
                if (vm.multiMaterial) {
                    IconButton(onClick = { choosingFilament = true }, modifier = Modifier.semantics { contentDescription = "Filament" }) {
                        val slot = obj.extruders.singleOrNull()
                        SlotDot(vm.slotColors.getOrElse((slot ?: 1) - 1) { "#808080" }, slot, 24.dp)
                    }
                }
                Box {
                    IconButton(onClick = {
                        // Single-filament printers can only paint supports.
                        if (vm.multiMaterial) paintMenu = true else vm.startPainting(PaintMode.SUPPORTS)
                    }) { Icon(Icons.Default.Brush, "Paint") }
                    DropdownMenu(expanded = paintMenu, onDismissRequest = { paintMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Paint supports") },
                            leadingIcon = { Icon(Icons.Default.Park, null) },
                            onClick = { paintMenu = false; vm.startPainting(PaintMode.SUPPORTS) },
                        )
                        DropdownMenuItem(
                            text = { Text("Paint filaments") },
                            leadingIcon = { Icon(Icons.Default.Palette, null) },
                            onClick = { paintMenu = false; vm.startPainting(PaintMode.COLORS) },
                        )
                    }
                }
                IconButton(onClick = vm::duplicateSelected) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                IconButton(onClick = vm::deleteSelected) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
    if (choosingFilament) ObjectFilamentSheet(vm, obj, onDismiss = { choosingFilament = false })
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

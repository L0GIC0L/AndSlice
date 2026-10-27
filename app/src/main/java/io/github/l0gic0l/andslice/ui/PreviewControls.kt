package io.github.l0gic0l.andslice.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.EXTRUSION_ROLE_NAMES
import io.github.l0gic0l.andslice.engine.PREVIEW_VIEW_TYPES
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Rotates a horizontal component to run bottom-to-top. */
private fun Modifier.vertical() = this
    .graphicsLayer {
        rotationZ = 270f
        transformOrigin = TransformOrigin(0f, 0f)
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
            )
        )
        layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
    }

fun formatDuration(seconds: Double): String {
    val s = seconds.roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s % 60}s"
        else -> "${s}s"
    }
}

@Composable
fun PreviewOverlay(vm: SlicerViewModel, modifier: Modifier = Modifier) {
    val zs = vm.result?.layerZs ?: emptyList()
    val count = zs.size
    Box(modifier) {
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewTypePicker(vm)
            Legend(vm)
        }

        if (count > 1) {
            val range = vm.layerRange
            // Layer range along the right edge.
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 12.dp).width(64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LayerLabel(range.last, zs)
                Box(Modifier.weight(1f)) {
                    RangeSlider(
                        value = range.first.toFloat()..range.last.toFloat(),
                        onValueChange = { v -> vm.changeLayerRange(v.start.roundToInt()..v.endInclusive.roundToInt()) },
                        valueRange = 0f..(count - 1).toFloat(),
                        modifier = Modifier.vertical().fillMaxWidth(),
                    )
                }
                LayerLabel(range.first, zs)
            }
            // Moves within the top layer, along the bottom edge.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 76.dp, bottom = 12.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Moves", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(12.dp))
                    Slider(value = vm.moveFraction, onValueChange = vm::changeMoveFraction, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LayerLabel(layer: Int, zs: List<Double>) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${layer + 1}", style = MaterialTheme.typography.labelLarge)
            Text(String.format(Locale.US, "%.2f", zs.getOrElse(layer) { 0.0 }), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ViewTypePicker(vm: SlicerViewModel) {
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilterChip(
                selected = true,
                onClick = { open = true },
                label = { Text(PREVIEW_VIEW_TYPES.firstOrNull { it.first == vm.viewType }?.second ?: "Feature type") },
                leadingIcon = { Icon(Icons.Default.Visibility, null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PREVIEW_VIEW_TYPES.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = if (id == vm.viewType) ({ Icon(Icons.Default.Check, null) }) else null,
                        onClick = {
                            open = false
                            vm.changeViewType(id)
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = vm.showTravel,
            onClick = { vm.changeShowTravel(!vm.showTravel) },
            label = { Text("Travel") },
            leadingIcon = if (vm.showTravel) ({ Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        )
    }
}

@Composable
private fun Legend(vm: SlicerViewModel) {
    val info = vm.previewInfo
    if (!info.loaded) return
    ElevatedCard(modifier = Modifier.widthIn(max = 240.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (vm.viewType == 0) {
                info.roles.filter { it.used }.forEach { role ->
                    LegendRow(Color(role.color), EXTRUSION_ROLE_NAMES.getOrElse(role.id) { "?" }, formatDuration(role.timeSeconds))
                }
            } else {
                info.range.asReversed().forEach { (value, color) ->
                    LegendRow(Color(color), String.format(Locale.US, "%.2f", value), "")
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PreviewBottomBar(vm: SlicerViewModel, printer: PrinterViewModel, onOpenPrinter: () -> Unit) {
    val result = vm.result ?: return
    val context = LocalContext.current
    val file = result.path?.let { File(it) }
    var sending by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        if (uri != null && file != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                vm.message = "G-code saved."
            } catch (t: Throwable) {
                vm.message = "Saving failed: ${t.message}"
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(Icons.Default.Schedule, "Time", result.printTime.ifEmpty { formatDuration(result.printTimeSeconds) })
                Stat(Icons.Default.Straighten, "Filament", String.format(Locale.US, "%.2f m", result.filamentMm / 1000.0))
                Stat(Icons.Default.Scale, "Weight", String.format(Locale.US, "%.1f g", result.filamentGrams))
                Stat(Icons.Default.Payments, "Cost", String.format(Locale.US, "%.2f", result.cost))
            }
            if (vm.resultStale) {
                Text(
                    "The plate or settings changed since this slice.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            printer.uploadProgress?.let { p ->
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (printer.configured) sending = true else onOpenPrinter() },
                    enabled = file != null && printer.uploadProgress == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Print, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Print")
                }
                FilledTonalButton(onClick = { file?.let { saveLauncher.launch(it.name) } }, enabled = file != null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
                FilledTonalIconButton(onClick = { file?.let { shareGcode(context, it) } }, enabled = file != null) {
                    Icon(Icons.Default.Share, "Share")
                }
                if (vm.resultStale) {
                    FilledTonalIconButton(onClick = { vm.slice(context) }) { Icon(Icons.Default.Refresh, "Slice again") }
                }
            }
        }
    }

    if (sending && file != null) SendToPrinterSheet(printer, file, onDismiss = { sending = false })
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendToPrinterSheet(printer: PrinterViewModel, file: File, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Send to OctoPrint", style = MaterialTheme.typography.titleLarge)
            ListItem(
                headlineContent = { Text(file.name) },
                supportingContent = { Text(printer.url) },
                leadingContent = { Icon(Icons.Default.CloudUpload, null) },
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { printer.upload(file, print = true); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Print, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Upload and print")
            }
            OutlinedButton(
                onClick = { printer.upload(file, print = false); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Upload only")
            }
            Text(
                "Make sure the bed is clear before starting a print remotely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun shareGcode(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share G-code"))
}

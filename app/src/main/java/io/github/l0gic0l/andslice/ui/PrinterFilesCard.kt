package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.octoprint.PrintRecord
import io.github.l0gic0l.andslice.octoprint.PrinterFile
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val COLLAPSED_ROWS = 6

/** The G-code on the printer, to print again or delete, and the printer's recent prints. */
@Composable
fun PrinterFilesCard(printer: PrinterViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmPrint by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PrinterFile?>(null) }
    LaunchedEffect(printer.activeId) { printer.loadFiles() }
    // Nothing starts while a print runs, or before the printer has answered.
    val canPrint = printer.status?.let { !it.printing && !it.paused } ?: false
    val files = printer.files

    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (printer.loadingFiles) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = printer::loadFiles) { Icon(Icons.Default.Refresh, "Refresh files") }
                }
            }
            if (printer.history.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf("On the printer", "History").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = tab == i,
                            onClick = { tab = i; expanded = false },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
            val note: String? = when {
                printer.filesError != null -> printer.filesError
                files == null -> "Loading…"
                tab == 0 && files.isEmpty() -> "No G-code on the printer yet. Prints sent from the app show up here."
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else if (tab == 0 || printer.history.isEmpty()) {
                val shown = if (expanded) files.orEmpty() else files.orEmpty().take(COLLAPSED_ROWS)
                shown.forEach { file ->
                    FileRow(printer, file, canPrint, onPrint = { confirmPrint = file.path }, onDelete = { confirmDelete = file })
                }
                ShowAll(files.orEmpty().size, expanded) { expanded = !expanded }
            } else {
                val shown = if (expanded) printer.history else printer.history.take(COLLAPSED_ROWS)
                shown.forEach { record -> HistoryRow(record, canPrint && record.exists, onPrint = { confirmPrint = record.path }) }
                ShowAll(printer.history.size, expanded) { expanded = !expanded }
            }
        }
    }

    confirmPrint?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmPrint = null },
            icon = { Icon(Icons.Default.Print, null) },
            title = { Text("Print ${path.substringAfterLast('/')}?") },
            text = { Text("Make sure the bed is clear before starting a print remotely.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrint = null
                    printer.printFile(path)
                }) { Text("Print") }
            },
            dismissButton = { TextButton(onClick = { confirmPrint = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete ${file.name}?") },
            text = { Text("The file is removed from the printer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    printer.deleteFile(file)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ShowAll(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    if (count <= COLLAPSED_ROWS) return
    TextButton(onClick = onToggle, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(if (expanded) "Show fewer" else "Show all $count")
    }
}

@Composable
private fun FileRow(printer: PrinterViewModel, file: PrinterFile, canPrint: Boolean, onPrint: () -> Unit, onDelete: () -> Unit) {
    LaunchedEffect(file.path, file.detailed) { printer.loadDetails(file) }
    ListItem(
        headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    file.estimatedSeconds?.let { formatDuration(it.toDouble()) },
                    String.format(Locale.US, "%.1f MB", file.size / 1e6),
                    file.modified.takeIf { it > 0 }?.let(::formatDate),
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    RemoteImage(file.thumbnailUrl, printer.apiKey, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onPrint, enabled = canPrint) { Icon(Icons.Default.Print, "Print ${file.name}") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete ${file.name}") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun HistoryRow(record: PrintRecord, canPrint: Boolean, onPrint: () -> Unit) {
    val (icon, label, tint) = when (record.status) {
        "completed" -> Triple(Icons.Default.CheckCircle, "Finished", MaterialTheme.colorScheme.primary)
        "cancelled" -> Triple(Icons.Default.Cancel, "Canceled", MaterialTheme.colorScheme.onSurfaceVariant)
        "in_progress" -> Triple(Icons.Default.Schedule, "Printing", MaterialTheme.colorScheme.tertiary)
        else -> Triple(Icons.Default.ErrorOutline, "Failed", MaterialTheme.colorScheme.error)
    }
    ListItem(
        headlineContent = { Text(record.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    label,
                    record.started.takeIf { it > 0 }?.let(::formatDate),
                    record.printSeconds.takeIf { it > 0 }?.let { formatDuration(it.toDouble()) },
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(icon, label, tint = tint) },
        trailingContent = {
            IconButton(onClick = onPrint, enabled = canPrint) { Icon(Icons.Default.Replay, "Print ${record.name} again") }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun formatDate(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochSeconds * 1000))

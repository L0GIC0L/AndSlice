package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.ModelFiles
import io.github.l0gic0l.andslice.PendingImport
import io.github.l0gic0l.andslice.SlicerViewModel

/** Asks which models of an archive to add, or what to do with a project while the plate has objects. */
@Composable
fun ImportDialog(vm: SlicerViewModel) {
    when (val pending = vm.pendingImport) {
        is PendingImport.Archive -> ArchiveDialog(pending.models, onAdd = vm::addUnpacked, onDismiss = vm::dismissImport)
        is PendingImport.Project -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            icon = { Icon(Icons.Default.FolderOpen, null) },
            title = { Text("Open the project?") },
            text = {
                Text(
                    "${pending.file.name} is a project with its own settings. Open it in place of the objects on the plate, " +
                        "or add its objects to them with your current settings.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.openPendingProject(replacePlate = true) }) { Text("Open project") } },
            dismissButton = { TextButton(onClick = { vm.openPendingProject(replacePlate = false) }) { Text("Add objects") } },
        )
        null -> Unit
    }
}

@Composable
private fun ArchiveDialog(models: List<ModelFiles.Unpacked>, onAdd: (List<ModelFiles.Unpacked>) -> Unit, onDismiss: () -> Unit) {
    // Everything starts picked: archives usually hold the parts of one print.
    var picked by remember(models) { mutableStateOf(models.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FolderZip, null) },
        title = { Text("Add models") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(models, key = { it.path }) { model ->
                    val checked = model in picked
                    ListItem(
                        headlineContent = { Text(model.path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = model.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folder ->
                            { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.toggleable(checked, role = Role.Checkbox) {
                            picked = if (it) picked + model else picked - model
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(models.filter { it in picked }) }, enabled = picked.isNotEmpty()) {
                Text(if (picked.size == 1) "Add 1 model" else "Add ${picked.size} models")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

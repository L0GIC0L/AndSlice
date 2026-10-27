package io.github.l0gic0l.andslice.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndSliceApp(vm: SlicerViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val openModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importModels(context, uris)
    }

    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.message = null
    }

    if (vm.initError != null) {
        ErrorScreen(vm.initError!!)
        return
    }
    if (!vm.ready || vm.presets == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(vm, onBack = { showSettings = false })
        return
    }
    BackHandler(enabled = vm.mode == ViewMode.Preview) { vm.showPlate() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    if (vm.mode == ViewMode.Preview) {
                        IconButton(onClick = vm::showPlate) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to plate") }
                    }
                },
                title = {
                    Column {
                        Text(if (vm.mode == ViewMode.Preview) "Preview" else "AndSlice", style = MaterialTheme.typography.titleMedium)
                        Text(vm.printer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (vm.mode == ViewMode.Plate) {
                        IconButton(onClick = { openModels.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Import model") }
                        IconButton(onClick = vm::arrange, enabled = vm.objects.isNotEmpty()) { Icon(Icons.Default.Apps, "Arrange") }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Settings") }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Clear plate") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            enabled = vm.objects.isNotEmpty(),
                            onClick = { menu = false; vm.clearPlate() },
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menu = false; showAbout = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                if (vm.mode == ViewMode.Preview) PreviewBottomBar(vm) else PlateBottomBar(vm)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Viewport(vm, Modifier.fillMaxSize())

            if (vm.busyMessage != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            if (vm.mode == ViewMode.Plate) {
                if (vm.objects.isEmpty() && vm.busyMessage == null) {
                    EmptyPlateHint(Modifier.align(Alignment.Center), onImport = { openModels.launch(arrayOf("*/*")) })
                }
                vm.selectedObject?.let { ObjectToolbar(vm, it, Modifier.align(Alignment.TopCenter).padding(8.dp)) }
            } else {
                PreviewOverlay(vm, Modifier.fillMaxSize())
            }
        }
    }

    if (vm.slicing) SlicingDialog(vm)
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun EmptyPlateHint(modifier: Modifier, onImport: () -> Unit) {
    Surface(modifier.padding(32.dp), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The plate is empty", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Import an STL, 3MF or OBJ file, or open one with AndSlice from your file manager.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onImport) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Import model")
            }
        }
    }
}

@Composable
private fun SlicingDialog(vm: SlicerViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Slicing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { vm.sliceProgress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${vm.sliceProgress}%  ${vm.sliceStatus}", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::cancelSlice) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About AndSlice") },
        text = {
            Text(
                "AndSlice slices for the Anycubic Kobra 3 with the slicing engine of PrusaSlicer 2.9.6 " +
                    "(libslic3r) and PrusaSlicer's G-code viewer (libvgcode).\n\n" +
                    "PrusaSlicer is © Prusa Research and contributors, based on Slic3r by Alessandro Ranellucci. " +
                    "Kobra 3 settings follow Anycubic's profiles from OrcaSlicer.\n\n" +
                    "AndSlice is free software under the GNU Affero General Public License v3. " +
                    "Source: github.com/L0GIC0L/AndSlice",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ErrorScreen(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AndSlice could not start", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(message, textAlign = TextAlign.Center)
        }
    }
}

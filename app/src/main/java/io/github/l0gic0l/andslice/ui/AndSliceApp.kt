package io.github.l0gic0l.andslice.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.text.style.TextOverflow
import io.github.l0gic0l.andslice.engine.PresetType
import io.github.l0gic0l.andslice.printers.SavedPrinter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode
import io.github.l0gic0l.andslice.ui.scene.OrbitCamera

private enum class Screen { Slicer, Settings, Printer, AddPrinter }

@Composable
fun AndSliceApp(vm: SlicerViewModel, printer: PrinterViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Slicer) }
    val snackbar = remember { SnackbarHostState() }
    // Outlives the 3D view, which is recreated when returning from another screen.
    val camera = remember { OrbitCamera() }

    // Messages from both view models end up in the same snackbar.
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.message = null
    }
    LaunchedEffect(printer.message) {
        val m = printer.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        printer.message = null
    }
    // Slicing follows the active saved printer: its vendor profiles and its printer preset.
    val active = printer.active
    LaunchedEffect(active?.id, active?.preset, active?.bundle, vm.ready) {
        if (vm.ready && active != null) vm.useSavedPrinter(active)
    }

    fun removePrinter(p: SavedPrinter) {
        printer.removePrinter(p.id)
        // A custom printer's profile goes with it unless another saved printer uses it.
        if (vm.presets?.isUser(PresetType.Printer, p.preset) == true && printer.printers.none { it.preset == p.preset }) {
            vm.deleteUserPreset(PresetType.Printer, p.preset)
        }
    }

    when {
        vm.initError != null -> ErrorScreen(vm.initError!!)
        !vm.ready || vm.presets == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        screen == Screen.AddPrinter -> AddPrinterScreen(vm, printer, onClose = { screen = Screen.Printer })
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            // Tablets and unfolded foldables get a navigation rail, and from 840 dp a side panel.
            val rail = maxWidth >= 600.dp
            val wide = maxWidth >= 840.dp
            val tabletop = currentWindowAdaptiveInfo().windowPosture.isTabletop
            val back: (() -> Unit)? = if (rail) null else ({ screen = Screen.Slicer })
            Row(Modifier.fillMaxSize()) {
                if (rail) {
                    AppRail(screen, onSelect = { screen = it })
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (screen) {
                        Screen.Settings -> {
                            BackHandler { screen = Screen.Slicer }
                            SettingsScreen(vm, wide = wide, onBack = back, onPrinterPresetSaved = printer::setActivePreset)
                        }
                        Screen.Printer -> {
                            BackHandler { screen = Screen.Slicer }
                            PrinterScreen(
                                printer, snackbar, onBack = back,
                                onAddPrinter = { screen = Screen.AddPrinter },
                                onRemovePrinter = ::removePrinter,
                            )
                        }
                        else -> SlicerScreen(
                            vm, printer, snackbar, camera,
                            navigationInRail = rail, wide = wide, tabletop = tabletop,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenPrinter = { screen = Screen.Printer },
                            onAddPrinter = { screen = Screen.AddPrinter },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRail(screen: Screen, onSelect: (Screen) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple(Screen.Slicer, Icons.Default.ViewInAr, "Plate"),
            Triple(Screen.Printer, Icons.Default.Print, "Printer"),
            Triple(Screen.Settings, Icons.Default.Tune, "Settings"),
        ).forEach { (target, icon, label) ->
            NavigationRailItem(
                selected = screen == target,
                onClick = { onSelect(target) },
                icon = { Icon(icon, null) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlicerScreen(
    vm: SlicerViewModel,
    printer: PrinterViewModel,
    snackbar: SnackbarHostState,
    camera: OrbitCamera,
    navigationInRail: Boolean,
    wide: Boolean,
    tabletop: Boolean,
    onOpenSettings: () -> Unit,
    onOpenPrinter: () -> Unit,
    onAddPrinter: () -> Unit,
) {
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    val openModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importModels(context, uris)
    }
    val preview = vm.mode == ViewMode.Preview
    BackHandler(enabled = preview) { vm.showPlate() }
    // Wide windows and half-open foldables show the controls next to (or below) the 3D view.
    val split = wide || tabletop

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    if (preview) {
                        IconButton(onClick = vm::showPlate) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to plate") }
                    }
                },
                title = {
                    Column {
                        Text(if (preview) "Preview" else "AndSlice", style = MaterialTheme.typography.titleLarge)
                        Text(
                            (printer.active?.name ?: "Printer") + " · " + shortPresetName(vm.printer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (!preview) {
                        IconButton(onClick = { openModels.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Import model") }
                    }
                    if (!navigationInRail) {
                        IconButton(onClick = onOpenPrinter) { Icon(Icons.Default.Print, "Printer") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Tune, "Settings") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!preview) {
                                DropdownMenuItem(
                                    text = { Text("Arrange") },
                                    leadingIcon = { Icon(Icons.Default.Apps, null) },
                                    enabled = vm.objects.isNotEmpty(),
                                    onClick = { menu = false; vm.arrange() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear plate") },
                                    leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                    enabled = vm.objects.isNotEmpty(),
                                    onClick = { menu = false; vm.clearPlate() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("About") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { menu = false; showAbout = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !split && !preview && vm.objects.isNotEmpty() && !vm.slicing,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                SliceButton(vm)
            }
        },
        bottomBar = {
            if (!split) {
                if (preview) PreviewBottomBar(vm, printer, onOpenPrinter) else PlateBottomBar(vm, printer, onAddPrinter)
            }
        },
    ) { padding ->
        val scene: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                Viewport(vm, camera, Modifier.fillMaxSize())

                if (vm.busyMessage != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (!preview) {
                    if (vm.objects.isEmpty() && vm.busyMessage == null) {
                        EmptyPlate(Modifier.align(Alignment.Center), onImport = { openModels.launch(arrayOf("*/*")) })
                    }
                    vm.selectedObject?.let { ObjectToolbar(vm, it, Modifier.align(Alignment.TopCenter).padding(12.dp)) }
                    if (vm.result != null && vm.selectedObject == null) {
                        PreviewShortcut(vm, Modifier.align(Alignment.BottomStart).padding(16.dp))
                    }
                } else {
                    PreviewOverlay(vm, Modifier.fillMaxSize())
                }
            }
        }
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (preview) {
                        PreviewBottomBar(vm, printer, onOpenPrinter)
                    } else {
                        PlateBottomBar(vm, printer, onAddPrinter, wide = true)
                        if (vm.objects.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                                SliceButton(vm)
                            }
                        }
                    }
                }
            }
        }
        when {
            // Half-open foldable: the 3D view above the hinge, the controls below it.
            tabletop -> Column(Modifier.fillMaxSize().padding(padding)) {
                scene(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.weight(1f).fillMaxWidth())
            }
            wide -> Row(Modifier.fillMaxSize().padding(padding)) {
                scene(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(380.dp).fillMaxHeight())
            }
            else -> scene(Modifier.fillMaxSize().padding(padding))
        }
    }

    if (vm.slicing) SlicingDialog(vm)
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun SliceButton(vm: SlicerViewModel) {
    val context = LocalContext.current
    ExtendedFloatingActionButton(
        onClick = { vm.slice(context) },
        icon = { Icon(Icons.Default.Layers, null) },
        text = { Text("Slice") },
    )
}

@Composable
private fun EmptyPlate(modifier: Modifier, onImport: () -> Unit) {
    Surface(
        modifier.padding(32.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ViewInAr, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("The plate is empty", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import an STL, 3MF or OBJ file, or open one with AndSlice from your file manager.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onImport) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Import model")
            }
        }
    }
}

@Composable
private fun PreviewShortcut(vm: SlicerViewModel, modifier: Modifier) {
    androidx.compose.material3.SuggestionChip(
        onClick = vm::showPreview,
        label = { Text(if (vm.resultStale) "Previous preview (out of date)" else "Show preview") },
        icon = { Icon(Icons.Default.Layers, null, Modifier.size(18.dp)) },
        modifier = modifier,
    )
}

@Composable
private fun SlicingDialog(vm: SlicerViewModel) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Layers, null) },
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
        icon = { Icon(Icons.Default.Info, null) },
        title = { Text("About AndSlice") },
        text = {
            Text(
                "AndSlice slices for the Anycubic Kobra 3 and many other printers with the slicing engine of PrusaSlicer 2.9.6 " +
                    "(libslic3r) and PrusaSlicer's G-code viewer (libvgcode).\n\n" +
                    "PrusaSlicer is © Prusa Research and contributors, based on Slic3r by Alessandro Ranellucci. " +
                    "Kobra 3 settings follow Anycubic's profiles from OrcaSlicer; other printers use PrusaSlicer's vendor profiles.\n\n" +
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
            Text("AndSlice could not start", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

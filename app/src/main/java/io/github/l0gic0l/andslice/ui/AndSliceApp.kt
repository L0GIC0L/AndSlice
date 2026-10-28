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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.material.icons.filled.Settings
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
import io.github.l0gic0l.andslice.PanelMode
import io.github.l0gic0l.andslice.PreferencesViewModel
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode
import io.github.l0gic0l.andslice.ui.scene.OrbitCamera

private enum class Screen { Slicer, Settings, Printer, AddPrinter, Preferences }

/** How the slicer screen shares the window between the 3D view and its controls. */
private enum class SlicerLayout {
    /** Phones: the controls in a bar under the 3D view. */
    Single,
    /** Half of the window beside the 3D view, with the slicing settings. */
    Half,
    /** A slim panel with the profile chips beside the 3D view. */
    Narrow,
    /** Half-open foldable: the 3D view above the hinge, the controls below it. */
    Tabletop,
}

@Composable
fun AndSliceApp(vm: SlicerViewModel, printer: PrinterViewModel, prefs: PreferencesViewModel) {
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
            // Tablets and unfolded foldables get a navigation rail and a side panel.
            val rail = maxWidth >= 600.dp
            val wide = maxWidth >= 840.dp
            val tabletop = prefs.splitWhenFolded && currentWindowAdaptiveInfo().windowPosture.isTabletop
            val layout = when {
                tabletop -> SlicerLayout.Tabletop
                // A half panel needs some height for the settings list, so not on phones in landscape.
                prefs.sidePanel == PanelMode.Half && rail && maxHeight >= 480.dp -> SlicerLayout.Half
                prefs.sidePanel != PanelMode.Off && wide -> SlicerLayout.Narrow
                else -> SlicerLayout.Single
            }
            val back: (() -> Unit)? = if (rail) null else ({ screen = Screen.Slicer })
            Row(Modifier.fillMaxSize()) {
                if (rail) {
                    AppRail(screen, onSelect = { screen = it })
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (screen) {
                        Screen.Settings -> {
                            BackHandler { screen = Screen.Slicer }
                            SettingsScreen(
                                vm, onBack = back, wide = wide, defaultMode = prefs.settingsMode,
                                onPrinterPresetSaved = printer::setActivePreset,
                            )
                        }
                        Screen.Printer -> {
                            BackHandler { screen = Screen.Slicer }
                            PrinterScreen(
                                printer, snackbar, onBack = back,
                                keepScreenOn = prefs.keepScreenOn,
                                onAddPrinter = { screen = Screen.AddPrinter },
                                onRemovePrinter = ::removePrinter,
                            )
                        }
                        Screen.Preferences -> {
                            BackHandler { screen = Screen.Slicer }
                            AppPreferencesScreen(prefs, onBack = back)
                        }
                        else -> SlicerScreen(
                            vm, printer, prefs, snackbar, camera,
                            navigationInRail = rail, layout = layout,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenPreferences = { screen = Screen.Preferences },
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
        @Composable
        fun Item(target: Screen, icon: ImageVector, label: String) = NavigationRailItem(
            selected = screen == target,
            onClick = { onSelect(target) },
            icon = { Icon(icon, null) },
            label = { Text(label, maxLines = 1) },
        )
        Spacer(Modifier.height(8.dp))
        Item(Screen.Slicer, Icons.Default.ViewInAr, "Plate")
        Item(Screen.Printer, Icons.Default.Print, "Printer")
        Item(Screen.Settings, Icons.Default.Tune, "Settings")
        // App preferences at the bottom, away from the slicing screens.
        Spacer(Modifier.weight(1f))
        Item(Screen.Preferences, Icons.Default.Settings, "Preferences")
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlicerScreen(
    vm: SlicerViewModel,
    printer: PrinterViewModel,
    prefs: PreferencesViewModel,
    snackbar: SnackbarHostState,
    camera: OrbitCamera,
    navigationInRail: Boolean,
    layout: SlicerLayout,
    onOpenSettings: () -> Unit,
    onOpenPreferences: () -> Unit,
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
    BackHandler(enabled = vm.transformOpen) { vm.transformOpen = false }
    BackHandler(enabled = vm.paintMode != null) { vm.stopPainting() }

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
                            if (!navigationInRail) {
                                DropdownMenuItem(
                                    text = { Text("Preferences") },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { menu = false; onOpenPreferences() },
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
                visible = !preview && vm.objects.isNotEmpty() && !vm.slicing && vm.paintMode == null && !vm.transformOpen,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                SliceButton(vm, prefs.openPreviewAfterSlicing)
            }
        },
        bottomBar = {
            if (layout == SlicerLayout.Single) {
                if (preview) PreviewBottomBar(vm, printer, onOpenPrinter) else PlateBottomBar(vm, printer, onAddPrinter)
            }
        },
    ) { padding ->
        val scene: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                // Anti-aliasing is chosen when the GL surface is made, so a change makes a new view.
                key(prefs.antialiasing) {
                    Viewport(vm, camera, Modifier.fillMaxSize(), prefs.antialiasing, prefs.renderScale, prefs.showGrid)
                }

                if (vm.busyMessage != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (!preview) {
                    if (vm.objects.isEmpty() && vm.busyMessage == null) {
                        EmptyPlate(Modifier.align(Alignment.Center), onImport = { openModels.launch(arrayOf("*/*")) })
                    }
                    vm.selectedObject?.let { obj ->
                        val panel = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                        when {
                            vm.paintMode != null -> PaintPanel(vm, panel)
                            else -> {
                                ObjectToolbar(vm, obj, Modifier.align(Alignment.TopCenter).padding(12.dp))
                                if (vm.transformOpen) TransformPanel(vm, obj, panel)
                            }
                        }
                    }
                    if (vm.result != null && vm.selectedObject == null) {
                        PreviewShortcut(vm, Modifier.align(Alignment.BottomStart).padding(16.dp))
                    }
                } else {
                    PreviewOverlay(vm, Modifier.fillMaxSize())
                }
            }
        }
        val controls: @Composable () -> Unit = {
            if (preview) PreviewBottomBar(vm, printer, onOpenPrinter, inPanel = true) else PlateBottomBar(vm, printer, onAddPrinter, wide = true)
        }
        // Profile chips (or the slice summary) on top, and below them all slicing settings.
        val settingsPanel: @Composable (Modifier) -> Unit = { modifier ->
            // Above the keyboard while a setting is edited.
            Column(modifier.background(MaterialTheme.colorScheme.surface).imePadding()) {
                controls()
                HorizontalDivider()
                SettingsScreen(
                    vm, onBack = null, modifier = Modifier.weight(1f), embedded = true,
                    defaultMode = prefs.settingsMode, onPrinterPresetSaved = printer::setActivePreset,
                )
            }
        }
        val chipsPanel: @Composable (Modifier) -> Unit = { modifier ->
            Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) { controls() }
            }
        }
        when (layout) {
            SlicerLayout.Tabletop -> Column(Modifier.fillMaxSize().padding(padding)) {
                scene(Modifier.weight(1f).fillMaxWidth())
                chipsPanel(Modifier.weight(1f).fillMaxWidth())
            }
            SlicerLayout.Half -> Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                scene(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                settingsPanel(Modifier.weight(1f).fillMaxHeight())
            }
            SlicerLayout.Narrow -> Row(Modifier.fillMaxSize().padding(padding)) {
                scene(Modifier.weight(1f).fillMaxHeight())
                chipsPanel(Modifier.width(380.dp).fillMaxHeight())
            }
            SlicerLayout.Single -> scene(Modifier.fillMaxSize().padding(padding))
        }
    }

    if (vm.slicing) SlicingDialog(vm)
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun SliceButton(vm: SlicerViewModel, openPreview: Boolean) {
    val context = LocalContext.current
    ExtendedFloatingActionButton(
        onClick = { vm.slice(context, openPreview) },
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
fun AboutDialog(onDismiss: () -> Unit) {
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

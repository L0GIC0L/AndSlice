package io.github.l0gic0l.andslice.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PanelMode
import io.github.l0gic0l.andslice.PreferencesViewModel
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.ViewMode
import io.github.l0gic0l.andslice.engine.PresetType
import io.github.l0gic0l.andslice.printers.SavedPrinter
import io.github.l0gic0l.andslice.ui.scene.OrbitCamera

private enum class Screen { Slicer, Settings, Printer, AddPrinter, Preferences, Browser, PrinterWeb, Calibration }

/** Top-level destinations: a navigation bar on phones, a rail on tablets and foldables. */
private enum class Destination(val label: String, val icon: ImageVector) {
    Plate("Plate", Icons.Default.ViewInAr),
    Preview("Preview", Icons.Default.Layers),
    Printer("Printer", Icons.Default.Print),
    Settings("Settings", Icons.Default.Tune),
}

/** How the slicer screen shares the window between the 3D view and its controls. */
private enum class SlicerLayout {
    /** Phones: the controls in a bar under the 3D view. */
    Single,
    /** The side panel on half of the window. */
    Half,
    /** The side panel at a fixed width, from 840 dp. */
    Narrow,
    /** Half-open foldable: the 3D view above the hinge, the side panel below it. */
    Tabletop,
}

@Composable
fun AndSliceApp(vm: SlicerViewModel, printer: PrinterViewModel, prefs: PreferencesViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Slicer) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Outlives the 3D view, which is recreated when returning from another screen.
    val camera = remember { OrbitCamera() }
    // The model browser opens again on the page it was left on.
    val browser = remember { BrowserMemory() }
    val calibration = remember { CalibrationForm() }

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
    // Files opened from elsewhere land on the plate, wherever the app was.
    var seenPlateRequests by rememberSaveable { mutableIntStateOf(vm.plateRequests) }
    LaunchedEffect(vm.plateRequests) {
        if (vm.plateRequests != seenPlateRequests) {
            seenPlateRequests = vm.plateRequests
            screen = Screen.Slicer
            vm.showPlate()
        }
    }
    // The print notification opens the printer screen.
    var seenPrinterRequests by rememberSaveable { mutableIntStateOf(printer.showRequests) }
    LaunchedEffect(printer.showRequests) {
        if (printer.showRequests != seenPrinterRequests) {
            seenPrinterRequests = printer.showRequests
            screen = Screen.Printer
        }
    }
    // Prints are followed in a notification, which Android 13 and later ask the user about once.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) printer.onNotificationsAllowed()
    }
    LaunchedEffect(printer.wantsNotificationPermission) {
        if (!printer.wantsNotificationPermission) return@LaunchedEffect
        printer.wantsNotificationPermission = false
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotificationPermission) {
            prefs.askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    val current = when (screen) {
        Screen.Printer -> Destination.Printer
        Screen.Settings -> Destination.Settings
        Screen.Slicer -> if (vm.mode == ViewMode.Preview) Destination.Preview else Destination.Plate
        else -> null
    }
    fun navigate(destination: Destination) {
        when (destination) {
            Destination.Plate -> {
                screen = Screen.Slicer
                vm.showPlate()
            }
            Destination.Preview -> {
                screen = Screen.Slicer
                vm.openPreview(context)
            }
            Destination.Printer -> screen = Screen.Printer
            Destination.Settings -> screen = Screen.Settings
        }
    }

    when {
        vm.initError != null -> ErrorScreen(vm.initError!!)
        !vm.ready || vm.presets == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        screen == Screen.AddPrinter -> AddPrinterScreen(vm, printer, onClose = { screen = Screen.Printer })
        // Web pages get the whole window.
        screen == Screen.Browser -> ModelBrowserScreen(vm, browser, onClose = { screen = Screen.Slicer })
        screen == Screen.Calibration -> {
            BackHandler { screen = Screen.Slicer }
            CalibrationScreen(vm, calibration, snackbar, onBack = { screen = Screen.Slicer })
        }
        screen == Screen.PrinterWeb -> {
            LaunchedEffect(printer.activeId) { printer.findWebInterface() }
            PrinterWebScreen(printer.webUrl, printer.webError, onClose = { screen = Screen.Printer })
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            // Tablets and unfolded foldables get a navigation rail and a side panel, phones a navigation bar.
            val rail = maxWidth >= 600.dp
            val wide = maxWidth >= 840.dp
            val tabletop = prefs.splitWhenFolded && currentWindowAdaptiveInfo().windowPosture.isTabletop
            val layout = when {
                tabletop -> SlicerLayout.Tabletop
                // The side panel needs some height for its lists, so not on phones in landscape.
                prefs.sidePanel == PanelMode.Half && rail && maxHeight >= 480.dp -> SlicerLayout.Half
                prefs.sidePanel != PanelMode.Off && wide -> SlicerLayout.Narrow
                else -> SlicerLayout.Single
            }
            Row(Modifier.fillMaxSize()) {
                if (rail) {
                    AppRail(current, preferences = screen == Screen.Preferences, onSelect = ::navigate) {
                        screen = Screen.Preferences
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // The navigation bar below takes the system navigation bar's space.
                    Box(Modifier.weight(1f).then(if (rail) Modifier else Modifier.consumeWindowInsets(WindowInsets.navigationBars))) {
                        when (screen) {
                            Screen.Settings -> {
                                BackHandler { navigate(Destination.Plate) }
                                SettingsScreen(
                                    vm, onBack = null, wide = wide, defaultMode = prefs.settingsMode,
                                    onPrinterPresetSaved = printer::setActivePreset,
                                )
                            }
                            Screen.Printer -> {
                                BackHandler { navigate(Destination.Plate) }
                                PrinterScreen(
                                    printer, snackbar, onBack = null,
                                    keepScreenOn = prefs.keepScreenOn,
                                    onAddPrinter = { screen = Screen.AddPrinter },
                                    onRemovePrinter = ::removePrinter,
                                    onOpenWeb = { screen = Screen.PrinterWeb },
                                )
                            }
                            Screen.Preferences -> {
                                BackHandler { navigate(Destination.Plate) }
                                AppPreferencesScreen(prefs, onBack = if (rail) null else ({ navigate(Destination.Plate) }))
                            }
                            else -> SlicerScreen(
                                vm, printer, prefs, snackbar, camera,
                                navigationInRail = rail, layout = layout,
                                onOpenPreferences = { screen = Screen.Preferences },
                                onOpenPrinter = { navigate(Destination.Printer) },
                                onAddPrinter = { screen = Screen.AddPrinter },
                                onFindModels = { screen = Screen.Browser },
                                onCalibrate = { screen = Screen.Calibration },
                            )
                        }
                    }
                    if (!rail) AppNavigationBar(current, onSelect = ::navigate)
                }
            }
            ImportDialog(vm)
        }
    }
}

@Composable
private fun AppRail(current: Destination?, preferences: Boolean, onSelect: (Destination) -> Unit, onPreferences: () -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.height(8.dp))
        Destination.entries.forEach { d ->
            NavigationRailItem(
                selected = current == d,
                onClick = { onSelect(d) },
                icon = { Icon(d.icon, null) },
                label = { Text(d.label, maxLines = 1) },
            )
        }
        // App settings at the bottom, away from the slicing screens.
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = preferences,
            onClick = onPreferences,
            icon = { Icon(Icons.Default.Settings, null) },
            label = { Text("App", maxLines = 1) },
            // The rail hides the icon's description behind its label; say what "App" opens.
            modifier = Modifier.semantics { contentDescription = "App settings" },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AppNavigationBar(current: Destination?, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Destination.entries.forEach { d ->
            NavigationBarItem(
                selected = current == d,
                onClick = { onSelect(d) },
                icon = { Icon(d.icon, null) },
                label = { Text(d.label, maxLines = 1) },
            )
        }
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
    onOpenPreferences: () -> Unit,
    onOpenPrinter: () -> Unit,
    onAddPrinter: () -> Unit,
    onFindModels: () -> Unit,
    onCalibrate: () -> Unit,
) {
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    var showCheck by remember { mutableStateOf(false) }
    val openModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importModels(context, uris)
    }
    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.openProject(context, it) }
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        uri?.let { vm.saveProject(context, it) }
    }
    val preview = vm.mode == ViewMode.Preview
    BackHandler(enabled = preview) { vm.showPlate() }
    BackHandler(enabled = vm.transformOpen) { vm.transformOpen = false }
    BackHandler(enabled = vm.objectSettingsOpen) { vm.openObjectSettings(false) }
    BackHandler(enabled = vm.cutOpen) { vm.openCut(false) }
    BackHandler(enabled = vm.paintMode != null) { vm.stopPainting() }
    val split = layout != SlicerLayout.Single

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                title = {
                    Column {
                        Text(if (preview) "Preview" else "Plate", style = MaterialTheme.typography.titleLarge)
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
                    if (!preview && vm.paintMode == null && (vm.canUndo || vm.canRedo)) {
                        IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                        IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    }
                    if (!preview) {
                        IconButton(onClick = onFindModels) { Icon(Icons.Default.TravelExplore, "Find models online") }
                        IconButton(onClick = { openModels.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Import model") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!preview) {
                                DropdownMenuItem(
                                    text = { Text("Open project…") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                    onClick = { menu = false; openProject.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save project…") },
                                    leadingIcon = { Icon(Icons.Default.Save, null) },
                                    enabled = vm.objects.isNotEmpty(),
                                    onClick = { menu = false; saveProject.launch(vm.projectName) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Check print") },
                                    leadingIcon = { Icon(Icons.Default.TaskAlt, null) },
                                    enabled = vm.objects.isNotEmpty(),
                                    onClick = { menu = false; showCheck = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Calibrate…") },
                                    leadingIcon = { Icon(Icons.Default.Science, null) },
                                    onClick = { menu = false; onCalibrate() },
                                )
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
                                    text = { Text("App settings") },
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
            // The side panel has its own Slice button.
            AnimatedVisibility(
                visible = !split && !preview && vm.objects.isNotEmpty() && !vm.slicing && vm.paintMode == null && !vm.transformOpen &&
                    !vm.objectSettingsOpen && !vm.cutOpen,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                SliceButton(vm, prefs.openPreviewAfterSlicing)
            }
        },
        bottomBar = {
            if (!split) {
                if (preview) PreviewBottomBar(vm, printer, onOpenPrinter) else PlateBottomBar(vm, printer, onAddPrinter)
            }
        },
    ) { padding ->
        val scene: @Composable (Modifier) -> Unit = { modifier ->
            BoxWithConstraints(modifier) {
                // Tool panels leave at least part of the object in view.
                val toolMaxHeight = maxHeight * 0.6f
                // Anti-aliasing is chosen when the GL surface is made, so a change makes a new view.
                key(prefs.antialiasing) {
                    Viewport(vm, camera, Modifier.fillMaxSize(), prefs.antialiasing, prefs.renderScale, prefs.showGrid)
                }

                if (vm.busyMessage != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (!preview) {
                    if (vm.objects.isEmpty() && vm.busyMessage == null) {
                        EmptyPlate(Modifier.align(Alignment.Center), onImport = { openModels.launch(arrayOf("*/*")) }, onFind = onFindModels)
                    }
                    if (vm.paintMode == null && !vm.transformOpen && !vm.objectSettingsOpen && !vm.cutOpen) {
                        PrintIssuesChip(vm, onOpen = { showCheck = true }, Modifier.align(Alignment.BottomStart).padding(12.dp))
                    }
                    vm.selectedObject?.let { obj ->
                        val panel = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                            .heightIn(max = toolMaxHeight)
                        when {
                            vm.paintMode != null -> PaintPanel(vm, panel)
                            else -> {
                                ObjectToolbar(vm, obj, Modifier.align(Alignment.TopCenter).padding(12.dp))
                                if (vm.transformOpen) TransformPanel(vm, obj, panel)
                                if (vm.objectSettingsOpen) ObjectSettingsPanel(vm, obj, panel)
                                if (vm.cutOpen) CutPanel(vm, obj, panel)
                            }
                        }
                    }
                } else {
                    PreviewOverlay(vm, Modifier.fillMaxSize())
                }
            }
        }
        // Above the keyboard while a setting is edited.
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            SidePanel(vm, printer, prefs, onAddPrinter, onOpenPrinter, modifier.imePadding())
        }
        when (layout) {
            SlicerLayout.Tabletop -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                scene(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.weight(1f).fillMaxWidth())
            }
            SlicerLayout.Half -> Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                scene(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                panel(Modifier.weight(1f).fillMaxHeight())
            }
            SlicerLayout.Narrow -> Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                scene(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                panel(Modifier.width(400.dp).fillMaxHeight())
            }
            SlicerLayout.Single -> scene(Modifier.fillMaxSize().padding(padding))
        }
    }

    if (vm.slicing) SlicingDialog(vm)
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showCheck) PrintCheckSheet(vm, onDismiss = { showCheck = false })
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
private fun EmptyPlate(modifier: Modifier, onImport: () -> Unit, onFind: () -> Unit) {
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
            TextButton(onClick = onFind) {
                Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Find models online")
            }
        }
    }
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

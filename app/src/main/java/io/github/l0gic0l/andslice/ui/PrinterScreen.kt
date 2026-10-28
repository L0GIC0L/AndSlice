package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.octoprint.Heater
import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import io.github.l0gic0l.andslice.octoprint.Temperature
import io.github.l0gic0l.andslice.printers.SavedPrinter
import kotlinx.coroutines.launch
import java.util.Locale

/** The active saved printer: camera, status, job and machine control, and its connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(
    printer: PrinterViewModel,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)?,
    onAddPrinter: () -> Unit,
    onRemovePrinter: (SavedPrinter) -> Unit,
    keepScreenOn: Boolean = false,
) {
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val active = printer.active ?: return
    var editing by rememberSaveable(active.id) { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Live status while this screen is shown.
    LaunchedEffect(active.id, printer.configured, editing) {
        if (printer.configured && !editing) printer.pollStatus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { PrinterSwitcher(printer, onAddPrinter) },
                actions = {
                    if (printer.configured && !editing) {
                        IconButton(onClick = { scope.launch { printer.refreshStatus() } }) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(
                            onClick = { confirmStop = true },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Icon(Icons.Default.Report, "Emergency stop") }
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit printer") }
                },
            )
        },
    ) { padding ->
        when {
            editing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ConnectionForm(printer, active, onDone = { editing = false })
                }
            }
            !printer.configured -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NotConnectedCard(onConnect = { editing = true })
                    PrinterInfoCard(printer, active, onRemove = { onRemovePrinter(active) })
                }
            }
            // Cards flow into columns on tablets and unfolded foldables.
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(340.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalItemSpacing = 16.dp,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item(key = "camera") { WebcamCard(printer) }
                item(key = "status") { StatusCard(printer) }
                printer.status?.takeIf { it.fileName != null }?.let { status -> item(key = "job") { JobCard(printer, status) } }
                item(key = "files") { PrinterFilesCard(printer) }
                item(key = "motion") { MotionCard(printer) }
                item(key = "console") { ConsoleCard(printer) }
                item(key = "info") { PrinterInfoCard(printer, active, onRemove = { onRemovePrinter(active) }) }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            icon = { Icon(Icons.Default.Report, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Emergency stop?") },
            text = { Text("The printer halts at once and turns off its heaters and motors. It has to be restarted afterwards.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    printer.emergencyStop()
                }) { Text("Stop now") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

/** Title of the printer screen: the active printer, with a menu of the others. */
@Composable
private fun PrinterSwitcher(printer: PrinterViewModel, onAddPrinter: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = printer.active ?: return
    Box {
        Row(
            Modifier.clickable { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(active.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    shortPresetName(active.preset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ArrowDropDown, "Switch printer")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            printer.printers.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = { Icon(if (p.url.isNotBlank()) Icons.Default.Wifi else Icons.Default.Print, null) },
                    trailingIcon = if (p.id == active.id) ({ Icon(Icons.Default.Check, null) }) else null,
                    onClick = {
                        open = false
                        printer.setActive(p.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add printer") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = {
                    open = false
                    onAddPrinter()
                },
            )
        }
    }
}

@Composable
private fun NotConnectedCard(onConnect: () -> Unit) {
    ElevatedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
            Text("Connect your printer", style = MaterialTheme.typography.titleLarge)
            Text(
                "With OctoPrint or Moonraker the app sends prints over Wi-Fi, shows the camera and controls the " +
                    "printer. For a Kobra 3 running Rinkhals, its IP address is all it needs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

/** Name, address, API key and camera of the active printer. */
@Composable
private fun ConnectionForm(printer: PrinterViewModel, active: SavedPrinter, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(active.name) }
    var url by rememberSaveable { mutableStateOf(active.url) }
    var key by rememberSaveable { mutableStateOf(active.apiKey) }
    var webcam by rememberSaveable { mutableStateOf(active.webcamUrl) }
    var showKey by rememberSaveable { mutableStateOf(false) }

    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Network: OctoPrint or Moonraker. For a Kobra 3 running Rinkhals, enter the printer's IP address; " +
                    "no API key is needed. Leave the address empty for a printer you print to from USB or SD card.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Address") },
                placeholder = { Text("192.168.1.50 or http://octopi.local") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key (OctoPrint)") },
                supportingText = { Text("OctoPrint: Settings → Application Keys. Leave empty for Moonraker.") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide key" else "Show key")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = webcam,
                onValueChange = { webcam = it },
                label = { Text("Camera address (optional)") },
                supportingText = { Text("MJPEG stream or snapshot URL. Empty: ask the printer.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            printer.testResult?.let { result ->
                val ok = result.startsWith("Connected")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null,
                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(result, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { printer.discover(context) }, enabled = !printer.discovering) {
                    if (printer.discovering) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Find")
                }
                TextButton(onClick = { printer.testConnection(url, key) }, enabled = url.isNotBlank() && !printer.testing) {
                    Text(if (printer.testing) "Testing…" else "Test")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Cancel") }
                Button(
                    onClick = {
                        printer.updatePrinter(active.copy(name = name.trim().ifEmpty { active.name }))
                        printer.saveConnection(url, key, webcam)
                        onDone()
                    },
                ) { Text("Save") }
            }

            if (printer.discovered.isNotEmpty()) {
                HorizontalDivider()
                Text("Found on this network", style = MaterialTheme.typography.titleSmall)
                printer.discovered.forEach { server ->
                    ListItem(
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text(server.url) },
                        leadingContent = { Icon(Icons.Default.Print, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { url = server.url },
                    )
                }
            }
        }
    }
}

@Composable
private fun WebcamCard(printer: PrinterViewModel) {
    OutlinedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WebcamView(printer.webcam, printer.apiKey)
        }
    }
}

private data class Preheat(val label: String, val hotend: Int, val bed: Int)

private val PREHEATS = listOf(Preheat("PLA", 210, 60), Preheat("PETG", 240, 80), Preheat("ABS / ASA", 255, 100))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(printer: PrinterViewModel) {
    val status = printer.status
    val error = printer.connectionError
    var editing by remember { mutableStateOf<Heater?>(null) }
    val (icon, title, containerColor) = when {
        error != null -> Triple(Icons.Default.CloudOff, "Offline", MaterialTheme.colorScheme.errorContainer)
        status == null -> Triple(Icons.Default.Dns, "Connecting…", MaterialTheme.colorScheme.surfaceContainerHigh)
        status.printing -> Triple(Icons.Default.Print, status.state, MaterialTheme.colorScheme.primaryContainer)
        else -> Triple(Icons.Default.CheckCircle, status.state, MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(error ?: printer.version?.text ?: printer.url, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (status != null && (status.hotend != null || status.bed != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    status.hotend?.let { TemperatureView("Hotend", it) { editing = Heater.Hotend } }
                    status.bed?.let { TemperatureView("Bed", it) { editing = Heater.Bed } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PREHEATS.forEach { p ->
                        AssistChip(onClick = { printer.preheat(p.hotend, p.bed) }, label = { Text("${p.label} ${p.hotend}/${p.bed}") })
                    }
                    AssistChip(onClick = { printer.preheat(0, 0) }, label = { Text("Cool down") })
                }
            }
        }
    }
    editing?.let { heater ->
        val current = if (heater == Heater.Hotend) status?.hotend else status?.bed
        TemperatureDialog(heater, current?.target ?: 0.0, onDismiss = { editing = null }) { celsius ->
            editing = null
            printer.setTemperature(heater, celsius)
        }
    }
}

@Composable
private fun TemperatureView(label: String, t: Temperature, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Thermostat, null)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                String.format(Locale.US, "%.0f / %.0f °C", t.actual, t.target),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TemperatureDialog(heater: Heater, target: Double, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(target.toInt().toString()) }
    val value = text.toIntOrNull()
    val max = if (heater == Heater.Hotend) 300 else 120
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Thermostat, null) },
        title = { Text(if (heater == Heater.Hotend) "Hotend temperature" else "Bed temperature") },
        text = {
            NumberField("Target", "°C", text, { text = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = value != null && value in 0..max, onClick = { onSet(value!!) }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = { onSet(0) }) { Text("Turn off") }
        },
    )
}

@Composable
private fun JobCard(printer: PrinterViewModel, status: PrinterStatus) {
    val fileName = status.fileName ?: return
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current job", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(fileName, style = MaterialTheme.typography.titleMedium)
            val completion = status.completion
            if (completion != null) {
                LinearProgressIndicator(progress = { (completion / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                val parts = buildList {
                    add(String.format(Locale.US, "%.0f%%", completion))
                    status.printTimeSeconds?.let { add("${formatDuration(it.toDouble())} elapsed") }
                    status.printTimeLeftSeconds?.let { add("${formatDuration(it.toDouble())} left") }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            if (status.printing || status.paused) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = printer::pauseOrResume) {
                        Icon(if (status.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (status.paused) "Resume" else "Pause")
                    }
                    OutlinedButton(onClick = { confirmCancel = true }) {
                        Icon(Icons.Default.Cancel, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel")
                    }
                }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            icon = { Icon(Icons.Default.Cancel, null) },
            title = { Text("Cancel the print?") },
            text = { Text("The printer stops and the job cannot be resumed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    printer.cancelPrint()
                }) { Text("Cancel print") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep printing") } },
        )
    }
}

private val JOG_STEPS = listOf(0.1, 1.0, 10.0, 50.0)

/** Home, jog pad, Z, fan and motors; locked while a print runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotionCard(printer: PrinterViewModel) {
    var step by rememberSaveable { mutableDoubleStateOf(10.0) }
    val busy = printer.status?.let { it.printing || it.paused } ?: true
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Move", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (busy && printer.status != null) {
                Text("Moving is locked while a print runs.", style = MaterialTheme.typography.bodySmall)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                JOG_STEPS.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = step == s,
                        onClick = { step = s },
                        shape = SegmentedButtonDefaults.itemShape(i, JOG_STEPS.size),
                    ) { Text(if (s < 1) "0.1" else s.toInt().toString()) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // X/Y pad with home in the middle.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JogButton(Icons.Default.ArrowUpward, "Y+", !busy) { printer.jog(y = step) }
                    Row {
                        JogButton(Icons.AutoMirrored.Filled.ArrowForward, "X−", !busy, rotate = 180f) { printer.jog(x = -step) }
                        FilledTonalIconButton(onClick = printer::home, enabled = !busy, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Home, "Home all axes")
                        }
                        JogButton(Icons.AutoMirrored.Filled.ArrowForward, "X+", !busy) { printer.jog(x = step) }
                    }
                    JogButton(Icons.Default.ArrowDownward, "Y−", !busy) { printer.jog(y = -step) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JogButton(Icons.Default.ArrowUpward, "Z+", !busy) { printer.jog(z = step.coerceAtMost(10.0)) }
                    Text("Z", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 18.dp))
                    JogButton(Icons.Default.ArrowDownward, "Z−", !busy) { printer.jog(z = -step.coerceAtMost(10.0)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { printer.sendGcode("M106 S255") }, label = { Text("Fan on") }, enabled = printer.status != null)
                AssistChip(onClick = { printer.sendGcode("M107") }, label = { Text("Fan off") }, enabled = printer.status != null)
                AssistChip(onClick = { printer.sendGcode("M84") }, label = { Text("Motors off") }, enabled = !busy)
            }
        }
    }
}

@Composable
private fun JogButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    rotate: Float = 0f,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(icon, description, Modifier.rotate(rotate))
    }
}

@Composable
private fun ConsoleCard(printer: PrinterViewModel) {
    var command by rememberSaveable { mutableStateOf("") }
    fun send() {
        printer.sendGcode(command)
        command = ""
    }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("G-code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("G28, M106 S128, …") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    IconButton(onClick = ::send, enabled = command.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrinterInfoCard(printer: PrinterViewModel, active: SavedPrinter, onRemove: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    OutlinedCard {
        ListItem(
            headlineContent = { Text(active.name) },
            supportingContent = {
                Text(listOfNotNull(shortPresetName(active.preset), active.url.ifEmpty { null }, printer.version?.text).joinToString(" · "))
            },
            leadingContent = { Icon(Icons.Default.Print, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active.url.isNotBlank()) TextButton(onClick = { confirm = "disconnect" }) { Text("Disconnect") }
            if (printer.printers.size > 1) TextButton(onClick = { confirm = "remove" }) { Text("Remove printer") }
        }
    }
    when (confirm) {
        "disconnect" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Disconnect ${active.name}?") },
            text = { Text("The address and API key are removed from this device. The printer stays in your list.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    printer.forgetConnection()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
        "remove" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${active.name}?") },
            text = { Text("The printer and its connection are removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onRemove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

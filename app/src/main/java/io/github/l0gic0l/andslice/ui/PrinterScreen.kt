package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import io.github.l0gic0l.andslice.octoprint.Temperature
import kotlinx.coroutines.launch
import java.util.Locale

/** OctoPrint connection, printer status and job control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(printer: PrinterViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(!printer.configured) }
    val scope = rememberCoroutineScope()

    // Live status while this screen is shown.
    LaunchedEffect(printer.configured, editing) {
        if (printer.configured && !editing) printer.pollStatus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Printer") },
                actions = {
                    if (printer.configured && !editing) {
                        IconButton(onClick = { scope.launch { printer.refreshStatus() } }) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit connection") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (editing) {
                ConnectionForm(printer, onSaved = { editing = false }, onCancel = if (printer.configured) ({ editing = false }) else null)
            } else {
                StatusCard(printer)
                printer.status?.let { status -> JobCard(printer, status) }
                ConnectionSummary(printer, onEdit = { editing = true })
            }
        }
    }
}

@Composable
private fun ConnectionForm(printer: PrinterViewModel, onSaved: () -> Unit, onCancel: (() -> Unit)?) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(printer.url) }
    var key by rememberSaveable { mutableStateOf(printer.apiKey) }
    var showKey by rememberSaveable { mutableStateOf(false) }

    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Connect to OctoPrint", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Upload G-code and start prints over Wi-Fi. Works with OctoPrint and with Moonraker's OctoPrint " +
                    "compatibility (for example a Kobra 3 running Rinkhals).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Address") },
                placeholder = { Text("http://octopi.local") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key") },
                supportingText = { Text("OctoPrint: Settings → Application Keys (or the global API key)") },
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
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = {
                        printer.saveConnection(url, key)
                        onSaved()
                    },
                    enabled = url.isNotBlank() && key.isNotBlank(),
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
private fun StatusCard(printer: PrinterViewModel) {
    val status = printer.status
    val error = printer.connectionError
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
                    status.hotend?.let { TemperatureView("Hotend", it) }
                    status.bed?.let { TemperatureView("Bed", it) }
                }
            }
        }
    }
}

@Composable
private fun TemperatureView(label: String, t: Temperature) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun ConnectionSummary(printer: PrinterViewModel, onEdit: () -> Unit) {
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    OutlinedCard {
        ListItem(
            headlineContent = { Text("OctoPrint") },
            supportingContent = { Text(printer.url) },
            leadingContent = { Icon(Icons.Default.Dns, null) },
            trailingContent = { TextButton(onClick = { confirmForget = true }) { Text("Disconnect") } },
            modifier = Modifier.clickable(onClick = onEdit),
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Disconnect from OctoPrint?") },
            text = { Text("The address and API key are removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    printer.forgetConnection()
                    onEdit()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

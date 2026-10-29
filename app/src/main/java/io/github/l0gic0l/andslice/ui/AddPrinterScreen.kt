package io.github.l0gic0l.andslice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.PrinterViewModel
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.octoprint.OctoPrintClient
import io.github.l0gic0l.andslice.printers.CatalogModel
import io.github.l0gic0l.andslice.printers.CatalogVendor
import io.github.l0gic0l.andslice.printers.ProfileCatalog
import io.github.l0gic0l.andslice.printers.SavedPrinter
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Adds a printer, like PrusaSlicer's configuration wizard: a vendor model and nozzle from the
 * bundled profiles, or a custom printer described by its bed, nozzle and firmware.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPrinterScreen(vm: SlicerViewModel, printers: PrinterViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember {
        ProfileCatalog.parse(context.assets.open("profiles/catalog.json").bufferedReader().use { it.readText() })
    }
    var vendorId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelId by rememberSaveable { mutableStateOf<String?>(null) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val vendor = catalog.firstOrNull { it.id == vendorId }
    val model = vendor?.models?.firstOrNull { it.id == modelId }

    fun back() = when {
        model != null -> modelId = null
        vendor != null -> vendorId = null
        custom -> custom = false
        else -> onClose()
    }
    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Text(
                        when {
                            model != null -> model.name
                            vendor != null -> vendor.name
                            custom -> "Custom printer"
                            else -> "Add printer"
                        }
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp)) {
                when {
                    custom -> CustomPrinterForm(vm, printers, onClose)
                    model != null && vendor != null -> ModelDetails(vm, printers, vendor, model, onClose)
                    vendor != null -> ModelList(vendor.models) { modelId = it.id }
                    else -> VendorList(
                        catalog,
                        onCustom = { custom = true },
                        onVendor = { vendorId = it.id },
                        onModel = { v, m ->
                            vendorId = v.id
                            modelId = m.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VendorList(
    catalog: List<CatalogVendor>,
    onCustom: () -> Unit,
    onVendor: (CatalogVendor) -> Unit,
    onModel: (CatalogVendor, CatalogModel) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column {
        SearchField(query, { query = it }, "Search printers")
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (query.isBlank()) {
                item(key = "custom") {
                    ListItem(
                        headlineContent = { Text("Custom printer") },
                        supportingContent = { Text("Any other printer: enter its bed size, nozzle and firmware") },
                        leadingContent = { Icon(Icons.Default.Build, null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(onClick = onCustom),
                    )
                    HorizontalDivider()
                }
                items(catalog, key = { it.id }) { v ->
                    ListItem(
                        headlineContent = { Text(v.name) },
                        supportingContent = { Text(if (v.models.size == 1) "1 printer" else "${v.models.size} printers") },
                        leadingContent = { Icon(Icons.Default.Print, null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable { onVendor(v) },
                    )
                }
            } else {
                // Search goes straight to the models of every vendor.
                val hits = catalog.flatMap { v -> v.models.map { v to it } }
                    .filter { (v, m) -> m.name.contains(query, true) || v.name.contains(query, true) }
                items(hits, key = { (v, m) -> v.id + "/" + m.id }) { (v, m) ->
                    ListItem(
                        headlineContent = { Text(m.name) },
                        supportingContent = { Text(v.name) },
                        leadingContent = { Icon(Icons.Default.Print, null) },
                        modifier = Modifier.clickable { onModel(v, m) },
                    )
                }
                if (hits.isEmpty()) {
                    item(key = "none") {
                        ListItem(
                            headlineContent = { Text("Not listed? Add it as a custom printer.") },
                            leadingContent = { Icon(Icons.Default.Build, null) },
                            modifier = Modifier.clickable(onClick = onCustom),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelList(models: List<CatalogModel>, onModel: (CatalogModel) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(models, key = { it.id }) { m ->
            ListItem(
                headlineContent = { Text(m.name) },
                supportingContent = { Text("Nozzles: " + m.variants.joinToString(", ")) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { onModel(m) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelDetails(vm: SlicerViewModel, printers: PrinterViewModel, vendor: CatalogVendor, model: CatalogModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var variant by rememberSaveable { mutableStateOf(model.variants.firstOrNull { it == "0.4" } ?: model.variants.first()) }
    var name by rememberSaveable { mutableStateOf(model.name) }
    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Nozzle", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.variants.forEach { v ->
                FilterChip(selected = v == variant, onClick = { variant = v }, label = { Text("$v mm") })
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ConnectionFields(url, { url = it }, key, { key = it })
        Button(
            enabled = !adding && name.isNotBlank(),
            onClick = {
                adding = true
                scope.launch {
                    try {
                        val preset = vm.findPrinterPreset(vendor.bundle, model.id, variant)
                        if (preset.isEmpty()) {
                            vm.message = "No profile for ${model.name} with a $variant mm nozzle."
                        } else {
                            printers.addPrinter(
                                SavedPrinter(
                                    name = name.trim(), preset = preset, bundle = vendor.bundle,
                                    url = if (url.isBlank()) "" else OctoPrintClient.normalizeUrl(url), apiKey = key.trim(),
                                )
                            )
                            onClose()
                        }
                    } catch (e: Exception) {
                        vm.message = e.message ?: e.toString()
                    } finally {
                        adding = false
                    }
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            if (adding) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Text(if (adding) "Loading profiles…" else "Add printer")
        }
    }
}

private val FIRMWARES = listOf("marlin2" to "Marlin 2", "klipper" to "Klipper", "reprapfirmware" to "RepRapFirmware")
private val NOZZLES = listOf("0.2", "0.4", "0.6", "0.8")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPrinterForm(vm: SlicerViewModel, printers: PrinterViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("My printer") }
    var width by rememberSaveable { mutableStateOf("220") }
    var depth by rememberSaveable { mutableStateOf("220") }
    var height by rememberSaveable { mutableStateOf("250") }
    var nozzle by rememberSaveable { mutableStateOf("0.4") }
    var firmware by rememberSaveable { mutableStateOf("marlin2") }
    var bowden by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    val size = listOf(width, depth, height).map { it.replace(',', '.').toDoubleOrNull() }
    val valid = name.isNotBlank() && size.all { it != null && it > 10 } && nozzle.toDoubleOrNull() != null

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Describe the printer; AndSlice starts from generic PrusaSlicer settings that suit most printers. " +
                "The start and end G-code can be changed later in Settings → Printer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Print volume", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Width (X)", "mm", width, { width = it }, Modifier.weight(1f))
            NumberField("Depth (Y)", "mm", depth, { depth = it }, Modifier.weight(1f))
            NumberField("Height (Z)", "mm", height, { height = it }, Modifier.weight(1f))
        }
        Text("Nozzle", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NOZZLES.forEach { n -> FilterChip(selected = n == nozzle, onClick = { nozzle = n }, label = { Text("$n mm") }) }
        }
        Text("Firmware", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FIRMWARES.forEachIndexed { i, (id, label) ->
                SegmentedButton(
                    selected = firmware == id,
                    onClick = { firmware = id },
                    shape = SegmentedButtonDefaults.itemShape(i, FIRMWARES.size),
                ) { Text(label) }
            }
        }
        Text("Extruder", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !bowden, onClick = { bowden = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Direct drive") }
            SegmentedButton(selected = bowden, onClick = { bowden = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Bowden") }
        }
        ConnectionFields(url, { url = it }, key, { key = it })
        Button(
            enabled = valid && !adding,
            onClick = {
                adding = true
                val (w, d, h) = size.map { it!! }
                val n = nozzle.toDouble()
                fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
                val settings = mapOf(
                    "bed_shape" to "0x0,${fmt(w)}x0,${fmt(w)}x${fmt(d)},0x${fmt(d)}",
                    "max_print_height" to fmt(h),
                    "nozzle_diameter" to nozzle,
                    "min_layer_height" to fmt(n * 0.2),
                    "max_layer_height" to fmt(n * 0.75),
                    "gcode_flavor" to firmware,
                    "retract_length" to if (bowden) "5" else "0.8",
                    "retract_speed" to if (bowden) "45" else "35",
                )
                scope.launch {
                    try {
                        val preset = vm.createCustomPrinter(name.trim(), settings)
                        printers.addPrinter(
                            SavedPrinter(
                                name = name.trim(), preset = preset,
                                url = if (url.isBlank()) "" else OctoPrintClient.normalizeUrl(url), apiKey = key.trim(),
                            )
                        )
                        onClose()
                    } catch (e: Exception) {
                        vm.message = e.message ?: e.toString()
                    } finally {
                        adding = false
                    }
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) { Text("Add printer") }
    }
}

/** Optional network connection of a new printer. */
@Composable
private fun ConnectionFields(url: String, onUrl: (String) -> Unit, key: String, onKey: (String) -> Unit) {
    Text("Network (optional)", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        url, onUrl,
        label = { Text("OctoPrint or Moonraker address") },
        placeholder = { Text("192.168.1.50") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        key, onKey,
        label = { Text("API key (OctoPrint)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQuery,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear search") } },
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

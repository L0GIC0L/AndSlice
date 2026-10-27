package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.OptionDef
import io.github.l0gic0l.andslice.engine.PresetType

private val MODES = listOf("Simple", "Advanced", "Expert")

/** Every PrusaSlicer option of the selected presets, grouped like PrusaSlicer's tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SlicerViewModel, onBack: () -> Unit) {
    val defs = vm.optionDefs ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var query by rememberSaveable { mutableStateOf("") }
    var help by remember { mutableStateOf<OptionDef?>(null) }
    val type = PresetType.entries[tab]

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("Settings")
                        val preset = when (type) {
                            PresetType.Print -> vm.print
                            PresetType.Filament -> vm.filament
                            PresetType.Printer -> vm.printer
                        }
                        Text(preset, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (vm.overrides.isNotEmpty()) {
                        TextButton(onClick = vm::resetAllOverrides) { Text("Reset all") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                PresetType.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.name) })
                }
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                },
                placeholder = { Text("Search settings") },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("settings-search"),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                MODES.forEachIndexed { i, name ->
                    SegmentedButton(
                        selected = mode == i,
                        onClick = { mode = i },
                        shape = SegmentedButtonDefaults.itemShape(i, MODES.size),
                    ) { Text(name) }
                }
            }

            val options = defs.byType[type].orEmpty().filter { d ->
                d.mode <= mode && d.key !in HIDDEN_KEYS &&
                    (query.isBlank() || d.fullLabel.contains(query, true) || d.key.contains(query, true) || d.category.contains(query, true))
            }
            val grouped = options.groupBy { it.category.ifEmpty { "Other" } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                grouped.forEach { (category, list) ->
                    item(key = "header-$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.key }) { def -> OptionRow(vm, def, onHelp = { help = def }) }
                }
            }
        }
    }

    help?.let { def ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(def.fullLabel) },
            text = { Text(def.tooltip.ifEmpty { "No description." } + "\n\nKey: ${def.key}") },
            confirmButton = { TextButton(onClick = { help = null }) { Text("Close") } },
        )
    }
}

// Options that make no sense to edit on the phone, or that the app manages itself.
private val HIDDEN_KEYS = setOf(
    "compatible_printers", "compatible_printers_condition", "compatible_prints", "compatible_prints_condition",
    "inherits", "print_settings_id", "filament_settings_id", "printer_settings_id", "printer_model", "printer_variant",
    "printer_vendor", "post_process", "host_type", "print_host", "printhost_apikey", "printhost_cafile",
    "printhost_port", "printhost_authorization_type", "printhost_user", "printhost_password", "printhost_ssl_ignore_revoke",
    "physical_printer_settings_id", "printer_technology", "wipe_tower_x", "wipe_tower_y",
)

@Composable
private fun OptionRow(vm: SlicerViewModel, def: OptionDef, onHelp: () -> Unit) {
    val value = vm.value(def.key) ?: ""
    val modified = def.key in vm.overrides
    ListItem(
        headlineContent = {
            Text(def.fullLabel, color = if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = {
            val notes = listOfNotNull(def.sidetext.takeIf { it.isNotEmpty() }, "changed".takeIf { modified })
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.widthIn(min = 72.dp, max = 168.dp)) {
                    OptionEditor(def, value) { vm.setOverride(def.key, it) }
                }
                if (modified) {
                    IconButton(onClick = { vm.resetOverride(def.key) }) { Icon(Icons.Default.RestartAlt, "Reset to profile") }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onHelp),
    )
}

@Composable
private fun OptionEditor(def: OptionDef, value: String, onChange: (String) -> Unit) {
    val isText = def.type == "string"
    when {
        def.type == "bool" && !def.nullable ->
            Switch(checked = value == "1", onCheckedChange = { onChange(if (it) "1" else "0") })
        def.type == "enum" && def.enumValues.isNotEmpty() && !def.enumOpen ->
            EnumEditor(def, value, onChange)
        isText && (def.multiline || def.key.endsWith("gcode")) ->
            MultilineEditor(def, value, onChange)
        else -> TextEditor(def, value, onChange)
    }
}

@Composable
private fun EnumEditor(def: OptionDef, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(
                def.enumValues.firstOrNull { it.first == value }?.second ?: value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            def.enumValues.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onChange(v)
                })
            }
        }
    }
}

/** Single-line field that commits on Done or when it loses focus. */
@Composable
private fun TextEditor(def: OptionDef, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val numeric = def.type in setOf("float", "int", "percent") && !def.vector
    fun commit() {
        val t = text.trim()
        if (t != value) onChange(if (def.type == "percent" && t.isNotEmpty() && !t.endsWith("%")) "$t%" else t)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}

@Composable
private fun MultilineEditor(def: OptionDef, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { editing = true }) { Text("Edit…") }
    if (editing) {
        var text by remember { mutableStateOf(decodeConfigString(value, def.vector)) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(def.fullLabel) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onChange(encodeConfigString(text, def.vector))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/** Inverse of PrusaSlicer's escape_string_cstyle / escape_strings_cstyle for one value. */
fun decodeConfigString(serialized: String, vector: Boolean): String {
    var s = serialized
    if (vector && s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s = s.substring(1, s.length - 1)
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                else -> out.append(n)
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

fun encodeConfigString(text: String, vector: Boolean): String {
    val escaped = StringBuilder()
    for (c in text) {
        when (c) {
            '\\' -> escaped.append("\\\\")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '"' -> escaped.append(if (vector) "\\\"" else "\"")
            else -> escaped.append(c)
        }
    }
    return if (vector) "\"$escaped\"" else escaped.toString()
}

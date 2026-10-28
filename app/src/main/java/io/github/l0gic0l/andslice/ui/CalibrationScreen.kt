package io.github.l0gic0l.andslice.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.Calibration
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.PresetType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the calibration screen was filled in with, kept while the app runs: the result needs the test's numbers. */
class CalibrationForm {
    var tempStart by mutableStateOf<String?>(null)
    var tempStep by mutableStateOf("5")
    var tempSections by mutableStateOf("6")
    var tempBest by mutableStateOf(1)
    var measuredWall by mutableStateOf("")
    var paStart by mutableStateOf("0")
    var paPerMm by mutableStateOf("0.002")
    var paHeight by mutableStateOf("40")
    var paBestHeight by mutableStateOf("")
}

private fun String.number(): Double? = replace(',', '.').trim().toDoubleOrNull()

/**
 * Calibration prints: a temperature tower, a flow cube and a pressure advance tower. Each goes on
 * the plate in place of what is there; after printing, the result becomes the setting for the
 * filament, and can be saved in a filament profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(vm: SlicerViewModel, form: CalibrationForm, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Calibration") },
                actions = {
                    TextButton(onClick = { saving = true }) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save to filament")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        "Print a test, look at it or measure it, then use the result for ${shortFilamentName(vm.filament)}. " +
                            "A test takes the place of the objects on the plate; Undo brings them back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { TemperatureCard(vm, form) }
                item { FlowCard(vm, form) }
                item { PressureAdvanceCard(vm, form) }
            }
        }
    }
    if (saving) SaveFilamentDialog(vm, onDismiss = { saving = false })
}

@Composable
private fun TestCard(title: String, text: String, content: @Composable () -> Unit) {
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun PlaceButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.ViewInAr, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Put on the plate")
    }
}

@Composable
private fun UseButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemperatureCard(vm: SlicerViewModel, form: CalibrationForm) {
    // Starts 10 °C above the filament's temperature, on a multiple of 5.
    val current = vm.value("temperature")?.split(',')?.firstOrNull()?.toDoubleOrNull()?.roundToInt() ?: 210
    val start = (form.tempStart ?: ((current + 10) / 5 * 5).toString()).toIntOrNull()
    val step = form.tempStep.toIntOrNull()
    val sections = form.tempSections.toIntOrNull()?.takeIf { it in 2..12 }
    val temps = if (start != null && step != null && sections != null) Calibration.temperatures(start, step, sections) else emptyList()
    TestCard(
        "Temperature tower",
        "Sections printed from hotter at the bottom to cooler at the top, each with an overhang. The best section has a clean " +
            "overhang, little stringing and layers that do not split when you bend the tower.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Start", "°C", form.tempStart ?: start?.toString().orEmpty(), { form.tempStart = it }, Modifier.weight(1f))
            NumberField("Step down", "°C", form.tempStep, { form.tempStep = it }, Modifier.weight(1f))
            NumberField("Sections", "", form.tempSections, { form.tempSections = it }, Modifier.weight(1f))
        }
        PlaceButton(temps.isNotEmpty()) { vm.placeTemperatureTower(start!!, step!!, sections!!) }
        if (temps.isNotEmpty()) {
            HorizontalDivider()
            Text("Best section, counted from the bottom", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                temps.forEachIndexed { i, t ->
                    FilterChip(selected = form.tempBest == i + 1, onClick = { form.tempBest = i + 1 }, label = { Text("${i + 1} · $t °C") })
                }
            }
            val best = temps.getOrNull(form.tempBest - 1)
            UseButton("Use ${best ?: "-"} °C", best != null) { vm.useTemperature(best!!) }
        }
    }
}

@Composable
private fun FlowCard(vm: SlicerViewModel, form: CalibrationForm) {
    val layer = vm.value("layer_height")?.toDoubleOrNull() ?: 0.2
    val expected = Calibration.expectedWall(vm.flowWallWidth, layer)
    val currentMultiplier = vm.value("extrusion_multiplier")?.split(',')?.firstOrNull()?.toDoubleOrNull() ?: 1.0
    val measured = form.measuredWall.number()?.takeIf { it > 0.1 && it < 3 }
    val multiplier = measured?.let { Calibration.flowMultiplier(currentMultiplier, expected, it) }
    TestCard(
        "Flow",
        "A ${Calibration.CUBE_HEIGHT.roundToInt()} mm high cube with two walls and no top. Measure a wall with calipers on " +
            "each side, away from the corners, and enter the average. It should be ${Calibration.format(expected, 2)} mm.",
    ) {
        PlaceButton(true) { vm.placeFlowCube() }
        HorizontalDivider()
        NumberField("Measured wall", "mm", form.measuredWall, { form.measuredWall = it }, Modifier.fillMaxWidth())
        Text(
            "Extrusion multiplier now ${Calibration.format(currentMultiplier, 3)}" +
                (multiplier?.let { ", then ${Calibration.format(it, 3)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        UseButton("Use ${multiplier?.let { Calibration.format(it, 3) } ?: "-"}", multiplier != null) { vm.useFlow(multiplier!!) }
    }
}

@Composable
private fun PressureAdvanceCard(vm: SlicerViewModel, form: CalibrationForm) {
    val command = vm.pressureAdvanceCommand
    val start = form.paStart.number()
    val perMm = form.paPerMm.number()
    val height = form.paHeight.toIntOrNull()?.takeIf { it in 10..100 }
    TestCard(
        "Pressure advance",
        "A square tower whose pressure advance rises with every millimeter. Find the height where the corners are sharpest, " +
            "without bulges or gaps after them, measure it from the bed and enter it.",
    ) {
        if (command == null) {
            Text(
                "The app does not know how this printer's firmware (${vm.value("gcode_flavor")}) sets pressure advance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@TestCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Start", "", form.paStart, { form.paStart = it }, Modifier.weight(1f))
            NumberField("Per mm", "", form.paPerMm, { form.paPerMm = it }, Modifier.weight(1f))
            NumberField("Height", "mm", form.paHeight, { form.paHeight = it }, Modifier.weight(1f))
        }
        if (start != null && perMm != null && height != null) {
            Text(
                "From ${Calibration.format(start, 4)} at the bottom to ${Calibration.format(Calibration.pressureAdvanceAt(height.toDouble(), start, perMm), 4)} " +
                    "at the top, with ${command.substringBefore(' ')}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PlaceButton(start != null && perMm != null && height != null) { vm.placePressureAdvanceTower(start!!, perMm!!, height!!) }
        HorizontalDivider()
        NumberField("Best height", "mm", form.paBestHeight, { form.paBestHeight = it }, Modifier.fillMaxWidth())
        val advance = form.paBestHeight.number()?.let { h -> if (start != null && perMm != null) Calibration.pressureAdvanceAt(h, start, perMm) else null }
        UseButton("Use ${advance?.let { Calibration.format(it, 4) } ?: "-"}", advance != null) { vm.usePressureAdvance(advance!!) }
    }
}

/** Saves the calibrated values in a filament profile of their own, which slot 1 then uses. */
@Composable
private fun SaveFilamentDialog(vm: SlicerViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("${shortFilamentName(vm.filament)} calibrated") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to a filament profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The filament settings you changed, such as the calibrated ones, go into a profile of your own for slot 1.")
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDismiss()
                scope.launch {
                    try {
                        val saved = vm.saveUserPreset(PresetType.Filament, name.trim())
                        vm.message = "Saved \"$saved\"."
                    } catch (e: Exception) {
                        vm.message = e.message ?: "Saving failed."
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

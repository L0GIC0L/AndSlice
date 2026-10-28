package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.SlicerViewModel
import io.github.l0gic0l.andslice.engine.PaintMode
import io.github.l0gic0l.andslice.engine.PlateObject
import java.util.Locale
import kotlin.math.roundToInt

internal fun fmt(v: Double, decimals: Int = 2) =
    String.format(Locale.US, "%.${decimals}f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/** Floating panel shape shared by the object tools. */
@Composable
internal fun ToolPanel(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        // Scrolls when the 3D view is short, as on phones in landscape.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------- move, rotate, scale

private val MOVE_STEPS = listOf(0.1, 1.0, 5.0, 10.0)
private val ROTATE_STEPS = listOf(1.0, 5.0, 15.0, 90.0)
private val SIZE_STEPS = listOf(0.1, 1.0, 5.0, 10.0)
private val PERCENT_STEPS = listOf(1.0, 5.0, 10.0, 25.0)

/**
 * Exact placement of the selected object: step buttons with a choice of step size, and fields
 * for typed values. Every change applies at once, so the object can be watched while it moves.
 */
@Composable
fun TransformPanel(vm: SlicerViewModel, obj: PlateObject, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stepIndex by rememberSaveable(tab) { mutableIntStateOf(1) }
    var uniform by rememberSaveable { mutableStateOf(true) }
    fun apply(t: DoubleArray) = vm.setTransform(obj.id, t)

    ToolPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf("Move", "Rotate", "Scale").forEachIndexed { i, name ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(name, maxLines = 1) }
                }
            }
            IconButton(onClick = { vm.transformOpen = false }) { Icon(Icons.Default.Close, "Close") }
        }

        val steps = when (tab) {
            0 -> MOVE_STEPS
            1 -> ROTATE_STEPS
            else -> if (uniform) SIZE_STEPS else PERCENT_STEPS
        }
        val unit = when (tab) {
            1 -> "°"
            2 -> if (uniform) "mm" else "%"
            else -> "mm"
        }
        val step = steps[stepIndex.coerceIn(steps.indices)]

        when (tab) {
            0 -> {
                AxisRow("X", unit, obj.offset[0], step) { apply(obj.transform(x = it)) }
                AxisRow("Y", unit, obj.offset[1], step) { apply(obj.transform(y = it)) }
            }
            1 -> {
                AxisRow("X", unit, obj.rotation[0], step) { apply(obj.transform(rx = it)) }
                AxisRow("Y", unit, obj.rotation[1], step) { apply(obj.transform(ry = it)) }
                AxisRow("Z", unit, obj.rotation[2], step) { apply(obj.transform(rz = it)) }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (uniform) "Keep proportions · ${fmt(obj.scale[0] * 100, 1)}%" else "Keep proportions",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = uniform, onCheckedChange = { uniform = it })
                }
                if (uniform) {
                    // Sizes on the bed in mm; a uniform scale changes them all exactly.
                    listOf("X", "Y", "Z").forEachIndexed { axis, label ->
                        AxisRow(label, unit, obj.size[axis], step, min = 0.1) { target ->
                            val f = obj.scale[0] * target / obj.size[axis].coerceAtLeast(1e-6)
                            apply(obj.transform(sx = f, sy = f, sz = f))
                        }
                    }
                } else {
                    // Scale along the object's own axes.
                    AxisRow("X", unit, obj.scale[0] * 100, step, min = 0.1) { apply(obj.transform(sx = it / 100)) }
                    AxisRow("Y", unit, obj.scale[1] * 100, step, min = 0.1) { apply(obj.transform(sy = it / 100)) }
                    AxisRow("Z", unit, obj.scale[2] * 100, step, min = 0.1) { apply(obj.transform(sz = it / 100)) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, s ->
                    FilterChip(
                        selected = i == stepIndex,
                        onClick = { stepIndex = i },
                        label = { Text(fmt(s, 1) + if (unit == "°") "°" else "") },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> TextButton(onClick = vm::centerSelected) { Text("Center on bed") }
                1 -> {
                    TextButton(onClick = { apply(obj.transform(rx = 0.0, ry = 0.0, rz = 0.0)) }) { Text("Reset") }
                    TextButton(onClick = { vm.transformOpen = false; vm.toggleLayFaceMode() }) { Text("Lay on face") }
                }
                else -> TextButton(onClick = { apply(obj.transform(sx = 1.0, sy = 1.0, sz = 1.0)) }) { Text("Reset to 100%") }
            }
        }
    }
}

/** One axis: step down, the exact value (typed values apply on Done), step up. */
@Composable
private fun AxisRow(label: String, unit: String, value: Double, step: Double, min: Double? = null, onChange: (Double) -> Unit) {
    fun clamp(v: Double) = if (min != null) v.coerceAtLeast(min) else v
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(24.dp))
        FilledTonalIconButton(onClick = { onChange(clamp(value - step)) }) { Icon(Icons.Default.Remove, "$label −") }
        var text by remember(value) { mutableStateOf(fmt(value)) }
        fun commit() {
            val v = text.replace(',', '.').toDoubleOrNull() ?: return
            if (fmt(v) != fmt(value)) onChange(clamp(v))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            suffix = { Text(unit) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) commit() }.semantics { contentDescription = "$label value" },
        )
        FilledTonalIconButton(onClick = { onChange(clamp(value + step)) }) { Icon(Icons.Default.Add, "$label +") }
    }
}

// ---------------------------------------------------------------------- painting

/** Brush, fill and what to paint while the selected object is painted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaintPanel(vm: SlicerViewModel, modifier: Modifier = Modifier) {
    val mode = vm.paintMode ?: return
    val supports = mode == PaintMode.SUPPORTS
    // The overhang highlight follows the switches and the angle, once the slider rests.
    LaunchedEffect(mode, vm.showOverhangs, vm.overhangsOnly, vm.overhangAngle) {
        delay(150)
        vm.updateOverhangHighlight()
    }
    ToolPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (mode) {
                    PaintMode.SUPPORTS -> "Paint supports"
                    PaintMode.COLORS -> "Paint filaments"
                    PaintMode.SEAM -> "Paint seams"
                    else -> "Paint fuzzy skin"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::undoPaint, enabled = vm.paintCanUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::clearPaint) { Icon(Icons.Default.LayersClear, "Clear painting") }
            TextButton(onClick = vm::stopPainting) { Text("Done") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (supports) {
                StateChip(vm, PaintMode.ENFORCE, "Supports", ENFORCER_COLOR)
                StateChip(vm, PaintMode.BLOCK, "No supports", BLOCKER_COLOR)
            } else if (mode == PaintMode.SEAM) {
                StateChip(vm, PaintMode.ENFORCE, "Seam here", ENFORCER_COLOR)
                StateChip(vm, PaintMode.BLOCK, "No seam", BLOCKER_COLOR)
            } else if (mode == PaintMode.FUZZY_SKIN) {
                StateChip(vm, PaintMode.ENFORCE, "Fuzzy skin", FUZZY_COLOR)
            } else {
                for (slot in 1..vm.extruderCount) {
                    val color = vm.slotColors.getOrElse(slot - 1) { "#808080" }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .selectable(selected = vm.paintState == slot, role = Role.RadioButton) { vm.paintState = slot }
                            .semantics { contentDescription = "Filament $slot" },
                    ) {
                        SlotDot(color, if (vm.paintState == slot) null else slot, 36.dp, selected = vm.paintState == slot)
                    }
                }
            }
            StateChip(vm, PaintMode.ERASE, "Erase", null)
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(
                Triple(PaintMode.TOOL_BRUSH, "Brush", Icons.Default.Brush),
                Triple(PaintMode.TOOL_FILL, "Fill", Icons.Default.FormatColorFill),
                Triple(PaintMode.TOOL_HEIGHT, "Height", Icons.Default.Height),
            ).forEachIndexed { i, (tool, label, icon) ->
                SegmentedButton(
                    selected = vm.paintTool == tool,
                    onClick = { vm.paintTool = tool },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    icon = { Icon(icon, null, Modifier.size(18.dp)) },
                ) { Text(label, maxLines = 1) }
            }
        }
        when (vm.paintTool) {
            PaintMode.TOOL_BRUSH -> {
                SliderRow("Size", vm.brushRadius * 2, 1f..30f, "${fmt(vm.brushRadius * 2.0, 1)} mm", "Brush size") {
                    vm.brushRadius = (it * 2).roundToInt() / 4f
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !vm.brushSphere, onClick = { vm.brushSphere = false }, label = { Text("Circle") })
                    FilterChip(selected = vm.brushSphere, onClick = { vm.brushSphere = true }, label = { Text("Sphere") })
                }
            }
            PaintMode.TOOL_FILL ->
                SliderRow("Edge angle", vm.fillAngle, 0f..90f, "${vm.fillAngle.roundToInt()}°", "Fill edge angle") {
                    vm.fillAngle = it.roundToInt().toFloat()
                }
            else ->
                SliderRow("Band", vm.heightRange, 0.1f..10f, "${fmt(vm.heightRange.toDouble(), 1)} mm", "Band height") {
                    vm.heightRange = (it * 10).roundToInt() / 10f
                }
        }

        if (supports) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = vm.overhangsOnly,
                    onClick = { vm.overhangsOnly = !vm.overhangsOnly },
                    label = { Text("Overhangs only") },
                )
                FilterChip(
                    selected = vm.showOverhangs,
                    onClick = { vm.showOverhangs = !vm.showOverhangs },
                    label = { Text("Show overhangs") },
                    leadingIcon = { Surface(shape = CircleShape, color = OVERHANG_COLOR, modifier = Modifier.size(14.dp)) {} },
                )
            }
            SliderRow("Overhang", vm.overhangAngle, 5f..90f, "${vm.overhangAngle.roundToInt()}°", "Overhang angle") {
                vm.overhangAngle = it.roundToInt().toFloat()
            }
            TextButton(onClick = vm::paintAllOverhangs) {
                Text(if (vm.paintState == PaintMode.BLOCK) "Block all overhangs" else if (vm.paintState == PaintMode.ERASE) "Erase all overhangs" else "Support all overhangs")
            }
        }

        Text(
            when (vm.paintTool) {
                PaintMode.TOOL_BRUSH ->
                    "Paint with one finger on the object. Drag beside it to turn the view; two fingers move and zoom." +
                        if (vm.brushSphere) " The sphere also paints faces turned away, such as both sides of a thin wall." else ""
                PaintMode.TOOL_FILL -> "Tap an area to fill it up to edges sharper than the edge angle."
                else -> "Touch a height to paint a band around the object there, for stripes of color or support."
            } + when (mode) {
                PaintMode.SUPPORTS -> " Overhangs are faces pointing down at less than the overhang angle from the bed."
                PaintMode.SEAM -> " Seams start and end where you paint them, and avoid the areas you block."
                PaintMode.FUZZY_SKIN -> " Painted walls get a rough texture, with the thickness and point distance set under Fuzzy skin."
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A labeled slider with its value; [onChange] gets the raw slider value. */
@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, description: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
        Text(valueText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 48.dp))
    }
}

// The 3D view's overhang highlight.
private val OVERHANG_COLOR = Color(0xFFFF9E1A)

// PrusaSlicer's colors for enforcers and blockers of supports and seams, as the 3D view draws them.
private val ENFORCER_COLOR = Color(0xFF408CFF)
/** As the 3D view draws fuzzy skin painting. */
private val FUZZY_COLOR = Color(0xFFB86BFF)
private val BLOCKER_COLOR = Color(0xFFFF4D4D)

@Composable
private fun StateChip(vm: SlicerViewModel, state: Int, label: String, color: Color?) {
    FilterChip(
        selected = vm.paintState == state,
        onClick = { vm.paintState = state },
        label = { Text(label) },
        leadingIcon = color?.let { c ->
            { Surface(shape = CircleShape, color = c, modifier = Modifier.size(14.dp)) {} }
        },
    )
}

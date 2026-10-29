package io.github.l0gic0l.andslice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.andslice.IssueFix
import io.github.l0gic0l.andslice.PrintIssue
import io.github.l0gic0l.andslice.SlicerViewModel

/** A chip over the plate while something may keep the print from printing well. */
@Composable
fun PrintIssuesChip(vm: SlicerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val warnings = vm.printIssues.count { it.warning }
    if (warnings == 0) return
    AssistChip(
        onClick = onOpen,
        label = { Text(if (warnings == 1) "1 print issue" else "$warnings print issues") },
        leadingIcon = { Icon(Icons.Default.WarningAmber, null, Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        border = null,
        modifier = modifier.testTag("print-issues"),
    )
}

/** What may keep the print from printing well, each with ways to fix it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintCheckSheet(vm: SlicerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Check print", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val issues = vm.printIssues
            if (issues.isEmpty()) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (vm.objects.isEmpty()) "The plate is empty." else "No problems found.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            issues.forEachIndexed { i, issue ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                IssueRow(issue) { fix ->
                    vm.fixIssue(issue, fix)
                    // Painting happens on the plate, behind the sheet.
                    if (fix == IssueFix.PaintSupports) onDismiss()
                }
            }
            Text(
                "Mesh holes, overhangs and bed contact are checked on the plate. Slicing adds PrusaSlicer's own " +
                    "stability check, which also finds loose extrusions, long bridges and thin parts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueRow(issue: PrintIssue, onFix: (IssueFix) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Icon(
            if (issue.warning) Icons.Default.WarningAmber else Icons.Default.Info,
            null,
            tint = if (issue.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(start = 16.dp)) {
            issue.objectName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(issue.title, style = MaterialTheme.typography.titleSmall)
            Text(issue.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (issue.fixes.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    issue.fixes.forEachIndexed { i, fix ->
                        if (i == 0) FilledTonalButton(onClick = { onFix(fix) }) { Text(fix.label) }
                        else OutlinedButton(onClick = { onFix(fix) }) { Text(fix.label) }
                    }
                }
            }
        }
    }
}

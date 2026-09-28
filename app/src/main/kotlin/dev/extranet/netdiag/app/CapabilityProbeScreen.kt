package dev.extranet.netdiag.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.report.CapabilityFinding
import dev.extranet.netdiag.core.report.CapabilityReport
import dev.extranet.netdiag.core.report.SupportStatus

/**
 * B0's exit-criterion surface: what the platform actually answered, on this handset.
 *
 * Deliberately dense and unstyled rather than a designed dashboard. The B0 question is "which
 * APIs return UNAVAILABLE here", and a report that hides two thirds of the rows behind tabs
 * would answer it worse. The S6 waterfall, forecast and relative index from the plan come in
 * B11, on top of this.
 */
@Composable
public fun CapabilityProbeScreen(
    state: ProbeUiState,
    onRunSync: () -> Unit,
    onRunLive: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("B0 capability probe", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Which platform APIs this handset actually supports.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRunSync) { Text("Run") }
            OutlinedButton(onClick = onRunLive) { Text("Run + live 8 s") }
            OutlinedButton(
                onClick = onShare,
                enabled = state is ProbeUiState.Done,
            ) { Text("Share JSON") }
        }

        Spacer(Modifier.height(12.dp))

        when (state) {
            ProbeUiState.Idle -> Text(
                "No run yet. 'Run' performs the synchronous pass; 'Run + live 8 s' also " +
                    "listens for GNSS measurements, telephony callbacks and network transitions.",
                style = MaterialTheme.typography.bodySmall,
            )

            is ProbeUiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(state.note, style = MaterialTheme.typography.bodySmall)
            }

            is ProbeUiState.Failed -> Text(
                text = "Harness failed: ${state.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is ProbeUiState.Done -> ReportBody(state)
        }
    }
}

@Composable
private fun ReportBody(state: ProbeUiState.Done) {
    val report = state.report
    Spacer(Modifier.height(4.dp))
    Text(report.summaryLine(), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "status counts: ${statusCountsLine(report)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "report written to ${state.reportPath}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    if (state.asyncObserved == 0) {
        Text(
            "no asynchronous spec was observed; those rows are NOT_PROBED, not failures",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(report.findings, key = { it.id }) { finding ->
            FindingRow(finding)
        }
    }
}

@Composable
private fun FindingRow(finding: CapabilityFinding) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = finding.id,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = finding.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(finding.status),
            )
        }
        val detail = finding.observedValue ?: finding.detail
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusCountsLine(report: CapabilityReport): String =
    report.countByStatus()
        .entries
        .filter { it.value > 0 }
        .joinToString { "${it.key.name}=${it.value}" }

@Composable
private fun statusColor(status: SupportStatus): Color = when (status) {
    SupportStatus.SUPPORTED -> MaterialTheme.colorScheme.primary
    SupportStatus.THROWS -> MaterialTheme.colorScheme.error
    SupportStatus.PERMISSION_DENIED -> MaterialTheme.colorScheme.error
    SupportStatus.NOT_PROBED -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

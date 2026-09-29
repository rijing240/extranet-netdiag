package dev.extranet.netdiag.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.report.CapabilityFinding
import dev.extranet.netdiag.core.report.CapabilityReport
import dev.extranet.netdiag.core.report.SupportStatus

/**
 * B0's exit-criterion surface, in the editorial language.
 *
 * The header states the question; the findings live in one card that reads like the reference
 * site's craft rows: one hairline-separated line per capability, mono id, quiet status, the
 * observation or the explanation beneath. Density stays deliberately high - the report is the
 * product here - but the whole thing scrolls as cards rather than as a ruled page.
 */
@Composable
public fun CapabilityProbeScreen(
    state: ProbeUiState,
    onRunSync: () -> Unit,
    onRunLive: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "B0 - platform probe",
                title = "What can this handset do?",
                subtitle = "Seventy platform capabilities, asked and answered on the device.",
            )
        }

        item {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Run", onClick = onRunSync, modifier = Modifier.weight(1f))
                    LineButton("Live 8 s", onClick = onRunLive, modifier = Modifier.weight(1f))
                    LineButton(
                        "Share",
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        enabled = state is ProbeUiState.Done,
                    )
                }
            }
        }

        when (state) {
            ProbeUiState.Idle -> item { SectionCard { IdleNote() } }
            is ProbeUiState.Running -> item { SectionCard { RunningNote(state.note) } }
            is ProbeUiState.Failed -> item { SectionCard { FailedNote(state.message) } }
            is ProbeUiState.Done -> reportItems(state)
        }
    }
}

@Composable
private fun IdleNote() {
    Text(
        "No run yet. RUN performs the synchronous pass; LIVE 8 S also listens for GNSS " +
            "measurements, telephony callbacks and network transitions.",
        style = MaterialTheme.typography.bodySmall,
        color = Editorial.InkSoft,
    )
}

@Composable
private fun RunningNote(note: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.width(16.dp).height(16.dp),
            color = Editorial.Ink,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(note, style = MaterialTheme.typography.labelMedium, color = Editorial.InkSoft)
    }
}

@Composable
private fun FailedNote(message: String) {
    Column {
        Text(
            "HARNESS FAILED",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
    }
}

/** The completed report, one card for the headline and one for the findings themselves. */
private fun LazyListScope.reportItems(state: ProbeUiState.Done) {
    val report = state.report

    item {
        SectionCard {
            // The headline number, set like the reference pricing: big figure in green.
            Text(
                "${report.supportedCount()}/70 supported",
                style = MaterialTheme.typography.displaySmall,
                color = Editorial.Green,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                statusCountsLine(report),
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Muted,
            )
            if (state.asyncObserved == 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No asynchronous spec was observed; those rows are NOT_PROBED, not failures.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Editorial.InkSoft,
                )
            }
        }
    }

    item {
        SectionCard(eyebrow = "capability findings") {
            for (finding in report.findings) {
                FindingRow(finding)
            }
        }
    }

    item {
        SectionCard(eyebrow = "report files") {
            Text(
                state.reportPath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Editorial.InkSoft,
            )
            state.externalReportPath?.let { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Editorial.Muted,
                )
            }
        }
    }
}

@Composable
private fun FindingRow(finding: CapabilityFinding) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = finding.id,
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Ink,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusTag(finding.status)
        }
        val detail = finding.observedValue ?: finding.detail
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (finding.observedValue != null) Editorial.Green else Editorial.InkSoft,
            )
        }
        Spacer(Modifier.height(8.dp))
        Hairline()
    }
}

private fun statusCountsLine(report: CapabilityReport): String =
    report.countByStatus()
        .entries
        .filter { it.value > 0 }
        .joinToString(separator = "   ") { "${it.key.name} ${it.value}" }

private fun CapabilityReport.supportedCount(): Int =
    findings.count { it.status == SupportStatus.SUPPORTED }

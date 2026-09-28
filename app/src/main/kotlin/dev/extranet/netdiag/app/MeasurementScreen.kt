package dev.extranet.netdiag.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.LayerStats
import dev.extranet.netdiag.measure.ProbeRun

/**
 * B1's exit-criterion surface: where the time goes, layer by layer, on this network.
 *
 * The numbers are the deliverable; the drawing of them as a flame graph is S6's job in a later
 * batch. What this screen has to make impossible is misreading the run: a p50 next to a p95, the
 * failure rate next to the ceiling it is judged against, and the platform's own verdict next to
 * ours so the two can be compared rather than confused.
 */
@Composable
public fun MeasurementScreen(
    state: MeasurementUiState,
    onRun: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("B1 latency waterfall", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Name lookup, connect, handshake, first byte - split apart so a fault can be attributed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun) { Text("Run 100 sets") }
            OutlinedButton(onClick = onShare, enabled = state is MeasurementUiState.Done) {
                Text("Share JSON")
            }
        }

        Spacer(Modifier.height(12.dp))

        when (state) {
            MeasurementUiState.Idle -> Text(
                "No run yet. A run asks the platform for its own connectivity verdict, then " +
                    "measures 100 probe sets against two well-known hosts.",
                style = MaterialTheme.typography.bodySmall,
            )

            is MeasurementUiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(state.note, style = MaterialTheme.typography.bodySmall)
            }

            is MeasurementUiState.Failed -> Text(
                text = "Harness failed: ${state.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is MeasurementUiState.Done -> WaterfallBody(state)
        }
    }
}

// Declared on ColumnScope so the waterfall can take the remaining height with weight(1f).
@Composable
private fun ColumnScope.WaterfallBody(state: MeasurementUiState.Done) {
    val run = state.run

    Text(run.summaryLine(), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = verdictLine(run),
        style = MaterialTheme.typography.bodySmall,
        color = if (run.meetsExitCriterion) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "layer      p50    p95    min    max   ok  fail  skip",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    LazyColumn(Modifier.weight(1f)) {
        items(run.waterfall) { row -> WaterfallRow(row) }
        item {
            Spacer(Modifier.height(12.dp))
            FailureModeSection(state)
        }
        item {
            Spacer(Modifier.height(12.dp))
            PlatformSection(state)
        }
    }
}

@Composable
private fun WaterfallRow(row: LayerStats) {
    Column {
        Text(
            text = layerLine(row),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (row.stats == null) {
            Text(
                text = "  nothing measured for ${row.layer.wireName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun layerLine(row: LayerStats): String {
    val stats = row.stats
    val name = row.layer.wireName.padEnd(8)
    val numbers = if (stats == null) {
        "   -      -      -      -"
    } else {
        "${stats.p50Millis.toString().padStart(5)}  " +
            "${stats.p95Millis.toString().padStart(5)}  " +
            "${stats.minMillis.toString().padStart(5)}  " +
            "${stats.maxMillis.toString().padStart(5)}"
    }
    return "$name$numbers  ${row.observed.toString().padStart(2)}  " +
        "${row.failures.toString().padStart(4)}  ${row.skipped.toString().padStart(4)}"
}

/** The verdict, with the arithmetic that produced it rather than only the conclusion. */
private fun verdictLine(run: ProbeRun): String {
    val percent = Math.round(run.failureRate * 10_000.0) / 100.0
    val head = "${run.setsAttempted}/${run.setsRequested} sets, ${run.failedSets} failed ($percent%), " +
        "ceiling 5%, ${run.measuredWallClockMillis} ms wall clock"
    return if (run.meetsExitCriterion) {
        "$head - exit criterion met"
    } else if (run.truncated) {
        "$head - NOT met: the wall clock cap stopped the run before it finished"
    } else {
        "$head - NOT met"
    }
}

@Composable
private fun FailureModeSection(state: MeasurementUiState.Done) {
    val run = state.run
    Text("failure modes", style = MaterialTheme.typography.titleSmall)
    if (run.failureModes.isEmpty()) {
        Text(
            "none: every attempted stage succeeded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    for (mode in run.failureModes) {
        Text(
            "${mode.count} x ${mode.layer.wireName}: ${mode.detail}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The platform's own answer next to ours.
 *
 * Kept in the same view deliberately: if the OS says the network is validated and our sets are
 * failing, the reader needs both facts in one place to draw the right conclusion.
 */
@Composable
private fun PlatformSection(state: MeasurementUiState.Done) {
    val run = state.run
    Text("platform connectivity diagnostics", style = MaterialTheme.typography.titleSmall)

    val diagnostics = run.osDiagnostics
    if (diagnostics == null) {
        Text(
            "no platform report: ${run.osDiagnosticsUnavailableReason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val lines = listOfNotNull(
        "interface ${diagnostics.interfaceName ?: "unknown"} mtu ${diagnostics.mtu ?: "?"}",
        "transports ${diagnostics.transports.joinToString(", ").ifEmpty { "none reported" }}",
        "dns ${diagnostics.dnsServers.joinToString(", ").ifEmpty { "none reported" }}",
        "private dns: " + if (diagnostics.privateDnsActive == true) {
            "active (${diagnostics.privateDnsServerName ?: "unnamed"})"
        } else {
            "inactive"
        },
        "validation result ${diagnostics.validationResult ?: "not reported"}, " +
            "probes attempted ${diagnostics.probesAttemptedBitmask ?: "?"}, " +
            "succeeded ${diagnostics.probesSucceededBitmask ?: "?"}",
        "resolver used by this run: ${run.resolver ?: "none offered"}",
        diagnostics.dataStall?.let { stall ->
            "data stall suspected at ${stall.timestampMillis} (method ${stall.detectionMethod ?: "?"})"
        } ?: "no data stall suspected during the run",
    )
    for (line in lines) {
        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    Text(
        "report written to ${state.reportPath}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    state.externalReportPath?.let { path ->
        Text(
            "and to $path",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "raw coordinates and cell identities are never included in this report",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

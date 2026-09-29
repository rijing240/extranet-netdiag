package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.LayerStats
import dev.extranet.netdiag.measure.OsDiagnostics
import dev.extranet.netdiag.measure.ProbeRun

/**
 * B1's exit-criterion surface, in the editorial language.
 *
 * The hero carries the verdict so it reads before anything else; the waterfall table keeps the
 * reference site's monospace discipline - numbers are the product, so they are set in the
 * measuring face, not a display face. The platform's own verdict is shown beside ours, and the
 * privacy line closes the page like the reference footer.
 */
@Composable
public fun MeasurementScreen(
    state: MeasurementUiState,
    onRun: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HeroBand(
            eyebrow = "B1 - latency waterfall",
            title = "Where does the time go?",
            subtitle = "Name lookup, connect, handshake, first byte - split apart so a fault can be attributed.",
        )

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Run 100 sets", onClick = onRun, modifier = Modifier.weight(1f))
                LineButton(
                    "Share",
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    enabled = state is MeasurementUiState.Done,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        when (state) {
            MeasurementUiState.Idle -> IdleNote()
            is MeasurementUiState.Running -> RunningNote(state.note)
            is MeasurementUiState.Failed -> FailedNote(state.message)
            is MeasurementUiState.Done -> WaterfallBody(state)
        }
    }
}

@Composable
private fun IdleNote() {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Hairline()
        Spacer(Modifier.height(12.dp))
        Text(
            "No run yet. A run asks the platform for its own connectivity verdict, then " +
                "measures 100 probe sets against two well-known hosts.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
    }
}

@Composable
private fun RunningNote(note: String) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    Column(Modifier.padding(horizontal = 20.dp)) {
        Hairline()
        Spacer(Modifier.height(12.dp))
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

// Declared on ColumnScope so the table can take the remaining height with weight(1f).
@Composable
private fun ColumnScope.WaterfallBody(state: MeasurementUiState.Done) {
    val run = state.run

    Column(Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(4.dp))
        // The verdict, set like a price: big, mono, green when the criterion is met.
        Text(
            if (run.meetsExitCriterion) "EXIT CRITERION MET" else "EXIT CRITERION NOT MET",
            style = MaterialTheme.typography.labelMedium,
            color = if (run.meetsExitCriterion) Editorial.Green else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            verdictFigure(run),
            style = MaterialTheme.typography.displaySmall,
            color = if (run.meetsExitCriterion) Editorial.Green else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            verdictLine(run),
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
        Spacer(Modifier.height(14.dp))

        // The table head, in the reference's mono label voice.
        Text(
            "layer      p50    p95    min    max   ok  fail  skip",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Editorial.Muted,
        )
        Spacer(Modifier.height(4.dp))
    }

    LazyColumn(Modifier.weight(1f)) {
        items(run.waterfall) { row -> WaterfallRow(row) }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(14.dp))
                FailureModeSection(run)
                Spacer(Modifier.height(14.dp))
                PlatformSection(run, state)
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun WaterfallRow(row: LayerStats) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = layerLine(row),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = Editorial.Ink,
        )
        if (row.stats == null) {
            Text(
                text = "nothing measured for ${row.layer.wireName}",
                style = MaterialTheme.typography.bodySmall,
                color = Editorial.Muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Hairline()
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

/** The big number under the verdict: the failure rate against its ceiling. */
private fun verdictFigure(run: ProbeRun): String {
    val percent = Math.round(run.failureRate * 10_000.0) / 100.0
    return "$percent% failures"
}

/** The arithmetic that produced the verdict, rather than only the conclusion. */
private fun verdictLine(run: ProbeRun): String =
    "${run.setsAttempted}/${run.setsRequested} sets, ${run.failedSets} failed, ceiling 5%, " +
        "${run.measuredWallClockMillis} ms wall clock" +
        (if (run.truncated) " - stopped by the wall-clock cap" else "") +
        (run.dominantLayer?.let { " - dominant stage: ${it.wireName}" } ?: "")

@Composable
private fun FailureModeSection(run: ProbeRun) {
    Eyebrow("failure modes")
    Spacer(Modifier.height(6.dp))
    val modes = run.failureModes
    if (modes.isEmpty()) {
        Text(
            "None: every attempted stage succeeded.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.Green,
        )
    } else {
        for (mode in modes) {
            Text(
                "${mode.count} x ${mode.layer.wireName}: ${mode.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = Editorial.InkSoft,
            )
        }
    }
}

/**
 * The platform's own answer next to ours.
 *
 * Kept in the same view deliberately: if the OS says the network is validated and our sets are
 * failing, the reader needs both facts in one place to draw the right conclusion.
 */
@Composable
private fun PlatformSection(run: ProbeRun, state: MeasurementUiState.Done) {
    Eyebrow("platform connectivity diagnostics")
    Spacer(Modifier.height(6.dp))

    val diagnostics = run.osDiagnostics
    if (diagnostics == null) {
        Text(
            "No platform report: ${run.osDiagnosticsUnavailableReason}.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
    } else {
        PlatformFacts(run, diagnostics, state)
    }

    Spacer(Modifier.height(10.dp))
    Hairline()
    Spacer(Modifier.height(8.dp))
    Text(
        "Raw coordinates and cell identities are never included in this report.",
        style = MaterialTheme.typography.labelSmall,
        color = Editorial.Muted,
    )
}

/** The platform's numbers, one per line, in mono. */
@Composable
private fun PlatformFacts(
    run: ProbeRun,
    diagnostics: OsDiagnostics,
    state: MeasurementUiState.Done,
) {
    val lines = listOfNotNull(
        "interface ${diagnostics.interfaceName ?: "unknown"}  mtu ${diagnostics.mtu ?: "?"}",
        "transports ${diagnostics.transports.joinToString(", ").ifEmpty { "none reported" }}",
        "dns ${diagnostics.dnsServers.joinToString(", ").ifEmpty { "none reported" }}",
        "private dns: " + if (diagnostics.privateDnsActive == true) {
            "active (${diagnostics.privateDnsServerName ?: "unnamed"})"
        } else {
            "inactive"
        },
        "validation ${diagnostics.validationResult ?: "not reported"}, " +
            "probes attempted ${diagnostics.probesAttemptedBitmask ?: "?"}, " +
            "succeeded ${diagnostics.probesSucceededBitmask ?: "?"}",
        "resolver used by this run: ${run.resolver ?: "none offered"}",
        diagnostics.dataStall?.let { stall ->
            "data stall suspected at ${stall.timestampMillis} (method ${stall.detectionMethod ?: "?"})"
        } ?: "no data stall suspected during the run",
    )
    for (line in lines) {
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Editorial.InkSoft,
        )
    }
    Spacer(Modifier.height(8.dp))
    MonoMeta(
        "report: ${state.reportPath.substringAfterLast('/')}",
        color = Editorial.Green,
    )
    state.externalReportPath?.let {
        MonoMeta(
            "shared copy: ${it.substringAfterLast('/')}",
            color = Editorial.Muted,
        )
    }
}

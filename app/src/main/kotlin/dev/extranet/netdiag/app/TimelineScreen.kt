package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.MiniThroughputProbe
import dev.extranet.netdiag.measure.RadioSample
import dev.extranet.netdiag.measure.SignalCompass
import dev.extranet.netdiag.measure.TwoHopProbe

/**
 * The TIMELINE tab: B2's live radio dashboard, signal compass, session log and reality check.
 *
 * The design language is the same as the other tabs; the new element is the gauge, drawn with
 * a Canvas rather than a progress ring so the needle reads like an instrument, not a spinner.
 */
@Composable
public fun TimelineScreen(
    state: TimelineUiState,
    onRun: (Long) -> Unit,
    onShareCsv: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HeroBand(
            eyebrow = "B2 - radio timeline",
            title = "The truth gauges.",
            subtitle = "One-second samples of what the antenna actually says - signal, noise, tower distance, direction.",
        )

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Sample 60 s", onClick = { onRun(60_000L) }, modifier = Modifier.weight(1f))
                LineButton(
                    "Share CSV",
                    onClick = { (state as? TimelineUiState.Done)?.let { onShareCsv(it.csv) } },
                    modifier = Modifier.weight(1f),
                    enabled = state is TimelineUiState.Done,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        when (state) {
            TimelineUiState.Idle -> IdleNote()
            is TimelineUiState.Sampling -> SamplingNote(state.secondsElapsed)
            is TimelineUiState.Failed -> FailedNote(state.message)
            is TimelineUiState.Done -> SessionBody(state)
        }
    }
}

@Composable
private fun IdleNote() {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Hairline()
        Spacer(Modifier.height(12.dp))
        Text(
            "No session yet. A session samples the radio once a second, watches for network " +
                "transitions, then offers the log as CSV and reads the compass over what it saw.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
    }
}

@Composable
private fun SamplingNote(secondsElapsed: Int) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            Modifier.size(16.dp),
            color = Editorial.Ink,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "SAMPLING - $secondsElapsed s",
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.InkSoft,
        )
    }
}

@Composable
private fun FailedNote(message: String) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Hairline()
        Spacer(Modifier.height(12.dp))
        Text(
            "SESSION FAILED",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(message, style = MaterialTheme.typography.bodySmall, color = Editorial.InkSoft)
    }
}

// Declared on ColumnScope so the log can take the remaining height with weight(1f).
@Composable
private fun ColumnScope.SessionBody(state: TimelineUiState.Done) {
    val summary = state.summary
    LazyColumn(Modifier.weight(1f)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${summary.samples} samples logged",
                    style = MaterialTheme.typography.displaySmall,
                    color = Editorial.Green,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    fieldCoverageLine(summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.Muted,
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Eyebrow("latest reading")
                Spacer(Modifier.height(8.dp))
                state.timeline.snapshot().lastOrNull()?.let { GaugeRow(it) }
                Spacer(Modifier.height(12.dp))
                Hairline()
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                Eyebrow("signal compass")
                Spacer(Modifier.height(6.dp))
                Text(
                    state.compass.statement(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.compass.bestSector != null) Editorial.Green else Editorial.InkSoft,
                )
                Spacer(Modifier.height(10.dp))
                Hairline()
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                Eyebrow("who is to blame")
                Spacer(Modifier.height(6.dp))
                BlameSection(state.blame)
                Spacer(Modifier.height(10.dp))
                Hairline()
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                Eyebrow("reality check - can data move")
                Spacer(Modifier.height(6.dp))
                ThroughputSection(state.throughput)
                Spacer(Modifier.height(10.dp))
                Hairline()
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                Eyebrow("network transitions")
                Spacer(Modifier.height(6.dp))
                TransitionSection(state.timeline)
                Spacer(Modifier.height(10.dp))
                Hairline()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun fieldCoverageLine(summary: RadioTimeline.FieldSummary): String =
    "rsrp ${summary.withRsrp}   rsrq ${summary.withRsrq}   ta ${summary.withTimingAdvance}   " +
        "heading ${summary.withHeading}   events ${summary.withEvents}"

/** The latest sample, drawn as instrument dials in the editorial palette. */
@Composable
private fun GaugeRow(sample: RadioSample) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Gauge(
            label = "RSRP dBm",
            value = sample.rsrpDbm?.toString() ?: "-",
            fraction = sample.rsrpDbm?.let { Normalize.rsrp(it) } ?: 0f,
            modifier = Modifier.weight(1f),
        )
        Gauge(
            label = "RSSNR dB",
            value = sample.rssnrDb?.toString() ?: "-",
            fraction = sample.rssnrDb?.let { Normalize.rssnr(it) } ?: 0f,
            modifier = Modifier.weight(1f),
        )
        Gauge(
            label = "TA m",
            value = sample.taDistanceMeters?.let { "${it.toInt()}" } ?: "-",
            fraction = sample.taDistanceMeters?.let { Normalize.taDistance(it) } ?: 0f,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "network ${sample.networkType ?: "none"}   level ${sample.level?.toString() ?: "-"}",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = Editorial.InkSoft,
    )
}

/** Maps raw values onto 0..1 for the dial, clamped; the gauge is honest about nulls elsewhere. */
private object Normalize {
    fun rsrp(rsrpDbm: Int): Float = ((rsrpDbm + 120f) / 60f).coerceIn(0f, 1f)
    fun rssnr(rssnrDb: Int): Float = ((rssnrDb + 10f) / 30f).coerceIn(0f, 1f)
    fun taDistance(meters: Double): Float = (1f - (meters.toFloat() / 2000f)).coerceIn(0f, 1f)
}

/** One dial: an arc from 7 o'clock to 5 o'clock, mono value, eyebrow label. */
@Composable
private fun Gauge(label: String, value: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2
                val arcSize = size.width - stroke
                drawArc(
                    color = Editorial.Hairline,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Butt),
                )
                drawArc(
                    color = Editorial.Green,
                    startAngle = 140f,
                    sweepAngle = 260f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Butt),
                )
            }
            Text(value, style = MaterialTheme.typography.labelLarge, color = Editorial.Ink)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Editorial.Muted)
    }
}

@Composable
private fun BlameSection(verdict: TwoHopProbe.Verdict?) {
    when (verdict) {
        null -> Text(
            "Probing the first hop...",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
        else -> {
            Text(
                verdict.statement(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (verdict.gateway.reachable && verdict.internet.reachable) Editorial.Green else Editorial.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "gateway ${verdict.gateway.address}: ${verdict.gatewayMedianMillis ?: "no answer"} ms   " +
                    "internet ${verdict.internet.address}: ${verdict.internetMedianMillis ?: "no answer"} ms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Editorial.InkSoft,
            )
        }
    }
}

@Composable
private fun ThroughputSection(result: MiniThroughputProbe.Result?) {
    when (result) {
        null -> Text(
            "Fetching 1 MB...",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
        else -> {
            Text(
                result.statement(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.verdict == "GOOD") Editorial.Green else Editorial.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "moved ${result.bytesMoved} B in ${result.transferMillis} ms   ping ${result.pingMedianMillis ?: "-"} ms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Editorial.InkSoft,
            )
            result.detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Editorial.Muted)
            }
        }
    }
}

@Composable
private fun TransitionSection(timeline: RadioTimeline) {
    val events = timeline.snapshot().mapNotNull { it.networkEvent }.distinct()
    if (events.isEmpty()) {
        Text(
            "No transitions during the session.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
    } else {
        for (event in events) {
            Text(
                event,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = Editorial.Ink,
            )
        }
    }
}

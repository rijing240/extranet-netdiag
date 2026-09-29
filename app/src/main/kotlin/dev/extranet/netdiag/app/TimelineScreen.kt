package dev.extranet.netdiag.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import dev.extranet.netdiag.measure.RadioTimeline
import dev.extranet.netdiag.measure.TwoHopProbe

/**
 * The TIMELINE tab: B2's live radio dashboard, signal compass, session log and reality check.
 *
 * One scroll of cards rather than a hero with a ruled list under it: the header and the action
 * row scroll away with the content, and each reading gets its own block. The gauges are drawn
 * with a Canvas rather than a progress ring so the needle reads like an instrument, not a spinner.
 */
@Composable
public fun TimelineScreen(
    state: TimelineUiState,
    onRun: (Long) -> Unit,
    onShareCsv: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "B2 - radio timeline",
                title = "The truth gauges.",
                subtitle = "One-second samples of what the antenna actually says - " +
                    "signal, noise, tower distance, direction.",
            )
        }

        item {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton(
                        "Sample 60 s",
                        onClick = { onRun(60_000L) },
                        modifier = Modifier.weight(1f),
                    )
                    LineButton(
                        "Share CSV",
                        onClick = { (state as? TimelineUiState.Done)?.let { onShareCsv(it.csv) } },
                        modifier = Modifier.weight(1f),
                        enabled = state is TimelineUiState.Done,
                    )
                }
            }
        }

        when (state) {
            TimelineUiState.Idle -> item { SectionCard { IdleNote() } }
            is TimelineUiState.Sampling -> item { SectionCard { SamplingNote(state.secondsElapsed) } }
            is TimelineUiState.Failed -> item { SectionCard { FailedNote(state.message) } }
            is TimelineUiState.Done -> sessionItems(state)
        }
    }
}

@Composable
private fun IdleNote() {
    Text(
        "No session yet. A session samples the radio once a second, watches for network " +
            "transitions, then offers the log as CSV and reads the compass over what it saw.",
        style = MaterialTheme.typography.bodySmall,
        color = Editorial.InkSoft,
    )
}

@Composable
private fun SamplingNote(secondsElapsed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(16.dp),
            color = Editorial.Ink,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "SAMPLING - $secondsElapsed s",
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.InkSoft,
        )
    }
}

@Composable
private fun FailedNote(message: String) {
    Column {
        Text(
            "SESSION FAILED",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Editorial.InkSoft)
    }
}

/** The session's readings, one card each, appended to the scroll. */
private fun LazyListScope.sessionItems(state: TimelineUiState.Done) {
    val summary = state.summary

    item {
        SectionCard {
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
        }
    }

    item {
        SectionCard(eyebrow = "latest reading") {
            val latest = state.timeline.snapshot().lastOrNull()
            if (latest == null) {
                Text(
                    "No sample carried a reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Editorial.InkSoft,
                )
            } else {
                GaugeRow(latest)
            }
        }
    }

    item {
        SectionCard(eyebrow = "signal compass") {
            Text(
                state.compass.statement(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.compass.bestSector != null) Editorial.Green else Editorial.InkSoft,
            )
        }
    }

    item {
        SectionCard(eyebrow = "who is to blame") {
            BlameSection(state.blame)
        }
    }

    item {
        SectionCard(eyebrow = "reality check - can data move") {
            ThroughputSection(state.throughput)
        }
    }

    item {
        SectionCard(eyebrow = "network transitions") {
            TransitionSection(state.timeline)
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
                color = if (verdict.gateway.reachable && verdict.internet.reachable) {
                    Editorial.Green
                } else {
                    Editorial.Ink
                },
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
                "moved ${result.bytesMoved} B in ${result.transferMillis} ms   " +
                    "ping ${result.pingMedianMillis ?: "-"} ms",
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

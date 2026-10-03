package dev.extranet.netdiag.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.RadioSample
import dev.extranet.netdiag.measure.RadioTimeline

/**
 * The radio dials, shared by every screen that shows a live reading.
 *
 * Drawn with a Canvas rather than a progress ring so a reading looks like an instrument: an arc
 * with a range, not a spinner that implies it is still loading. Missing readings render as "-"
 * with an empty arc, never as a zero, which is the same rule the samples themselves follow.
 */
@Composable
public fun GaugeRow(sample: RadioSample, modifier: Modifier = Modifier) {
    Column(modifier) {
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
        Spacer(Modifier.height(6.dp))
        Text(
            "network ${sample.networkType ?: "none"}   level ${sample.level?.toString() ?: "-"}   " +
                "rsrq ${sample.rsrqDb?.toString() ?: "-"}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Editorial.InkSoft,
        )
    }
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

/**
 * What the platform actually answered with, per field.
 *
 * Shown next to every raw reading because a mostly-absent column is a capability finding about
 * this phone, not a rendering problem: "rsrp 60/60, ta 0/60" means the radio spoke and the
 * timing advance is genuinely unavailable, which is a fact no other screen can show.
 */
@Composable
public fun FieldCoverage(summary: RadioTimeline.FieldSummary, modifier: Modifier = Modifier) {
    Text(
        "${summary.samples} samples   rsrp ${summary.withRsrp}   rsrq ${summary.withRsrq}   " +
            "ta ${summary.withTimingAdvance}   heading ${summary.withHeading}   events ${summary.withEvents}",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = Editorial.Muted,
        modifier = modifier,
    )
}

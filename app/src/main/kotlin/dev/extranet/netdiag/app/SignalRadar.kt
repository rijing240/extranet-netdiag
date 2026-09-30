package dev.extranet.netdiag.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.RadioSample
import dev.extranet.netdiag.measure.SignalCompass
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The radar: one dial that answers "which way is better" without a sentence.
 *
 * Three things are drawn, in order of how much they can be trusted. The sixteen wedges are the
 * measured fact - each fills as the user faces that direction and the radio reports a strength,
 * coloured green when that sector beat the session's median, grey when it did not, and left as
 * an empty arc where nobody has looked yet. The sweep is decoration with a purpose: while
 * sampling it tells the user the instrument is alive, and the pink is deliberately not a status
 * colour so a spinning sweep is never mistaken for a verdict. The needle appears only when the
 * compass statistics support a direction, which is the same rule the sentence version applied -
 * it just draws the refusal instead of printing it.
 *
 * The dial is drawn with north up and headings clockwise, matching the rotation vector's azimuth
 * convention (0 = north, 90 = east), so what the user sees turns the way they turn.
 */
@Composable
public fun SignalRadar(
    samples: List<RadioSample>,
    compass: SignalCompass.Verdict,
    liveHeading: Double?,
    sampling: Boolean,
    tiltOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val sectors = SignalCompass.sectors(samples)
    val best = compass.bestSector

    // The sweep only runs while a session is sampling; a finished result is a still picture.
    val sweep = rememberInfiniteTransition(label = "radar-sweep")
    val sweepAngle by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar-sweep-angle",
    )

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val radius = min(size.width, size.height) / 2f - stroke
                val center = Offset(size.width / 2f, size.height / 2f)
                val sectorSweep = 360f / 16f

                androidx.compose.ui.graphics.drawscope.rotate(degrees = -(liveHeading?.toFloat() ?: 0f)) {
                    // The empty ring every sector sits on, so an unvisited direction is visibly
                    // "not looked at yet" rather than absent.
                    drawCircle(
                        color = Editorial.Hairline,
                        radius = radius,
                        center = center,
                        style = Stroke(stroke, cap = StrokeCap.Butt),
                    )

                    // Sixteen measured wedges. The compass sector index runs 0 = east clockwise;
                    // Compose arcs run 0 = three o'clock clockwise too, so no rotation offset is
                    // needed - the wedge at index i is simply drawn at i * sectorSweep.
                    for (sector in sectors) {
                        if (sector == null) continue
                        val improvement = sector.improvementDb ?: 0.0
                        val color = when {
                            improvement >= 2.0 -> Editorial.Green
                            improvement <= -2.0 -> Editorial.Red.copy(alpha = 0.55f)
                            else -> Editorial.Muted.copy(alpha = 0.7f)
                        }
                        val centerDegrees = sector.centerDegrees.toFloat()
                        val startAngle = centerDegrees - 90f - (sectorSweep - 2f) / 2f
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sectorSweep - 2f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(stroke, cap = StrokeCap.Butt),
                        )
                    }

                    // The sweep trail, sampling only.
                    if (sampling) {
                        drawArc(
                            color = Editorial.Stitch.copy(alpha = 0.5f),
                            startAngle = sweepAngle - 90f - 40f,
                            sweepAngle = 40f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }

                    // The direction needle, only when the statistics support one.
                    if (best != null) {
                        val radians = Math.toRadians(best.centerDegrees - 90.0)
                        val inner = radius * 0.35f
                        val outer = radius * 0.92f
                        drawLine(
                            color = Editorial.Green,
                            start = Offset(
                                center.x + inner * cos(radians).toFloat(),
                                center.y + inner * sin(radians).toFloat(),
                            ),
                            end = Offset(
                                center.x + outer * cos(radians).toFloat(),
                                center.y + outer * sin(radians).toFloat(),
                            ),
                            strokeWidth = 6.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }

                    // The user's live heading dot, so turning the phone visibly turns the dial.
                    if (liveHeading != null) {
                        val radians = Math.toRadians(liveHeading - 90.0)
                        val dotRadius = 5.dp.toPx()
                        drawCircle(
                            color = Editorial.Blue,
                            radius = dotRadius,
                            center = Offset(
                                center.x + (radius * 0.8f) * cos(radians).toFloat(),
                                center.y + (radius * 0.8f) * sin(radians).toFloat(),
                            ),
                        )
                    }
                }
            }

            // The instruction lives inside the dial, where the eye already is.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        sampling -> "Sampling - turn around slowly"
                        best != null -> "Best this way"
                        else -> "Walk and sample to map directions"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Editorial.Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        compass.samplesWithHeading < 5 ->
                            "${compass.samplesWithHeading} of 16 directions measured"
                        best != null ->
                            "+${formatOne(best.improvementDb ?: 0.0)} dB vs average"
                        else -> "No direction stands out yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Editorial.InkMid,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (tiltOnly) {
                "This phone has no compass: directions are relative to where you started. Green = stronger, red = weaker, empty = not measured."
            } else {
                "N is up. Green = stronger than average, red = weaker, grey = about average, empty = not measured yet."
            },
            style = MaterialTheme.typography.labelSmall,
            color = Editorial.Muted,
        )
    }
}

private fun formatOne(value: Double): String =
    String.format(java.util.Locale.ROOT, "%.1f", value)

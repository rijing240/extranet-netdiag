package dev.extranet.netdiag.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.WalkTracker
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The walking meter: the Signal tab's instrument on a phone with no compass.
 *
 * Without a magnetometer a phone cannot know which way it faces, so a direction dial would sit
 * there empty and look broken. This meter answers the question such a phone *can* answer: "where
 * was the signal best, and how far back is that?" It remembers the strongest spot reached while
 * walking, counts steps with the accelerometer, and says what to do in words - go back about 12
 * steps, keep going, or stay put.
 *
 * Three layers, from the glance to the detail. The dial and one big word say how the signal is
 * right now. The strip under it draws the last stretch of the walk, with the best spot ringed
 * and "you" at the end. The line under that is the instruction.
 *
 * The frame is read through derived state, so the meter redraws a few times a second as the
 * walk changes, not sixty times a second with the display.
 */
@Composable
public fun SignalTrendMeter(
    frame: State<RadarEngine.Frame>,
    modifier: Modifier = Modifier,
) {
    val dbm by remember { derivedStateOf { frame.value.dbm } }
    val kind by remember { derivedStateOf { frame.value.kind } }
    val walk by remember { derivedStateOf { frame.value.walk } }
    val steps by remember { derivedStateOf { frame.value.steps } }
    val recording by remember { derivedStateOf { frame.value.recording } }

    val fraction = dbm?.let { gaugeFraction(it, kind) } ?: 0f
    // Enough smoothing to stop a one-dB wobble from flickering, and no more. The dial used to
    // take half a second to catch up, which made a live reading look like a stale one: the
    // number beside it had moved and the arc had not, so the instrument was lying by half a
    // second. A meter that is meant to be read at a glance has to be where the reading is now.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 140),
        label = "walk-gauge-fraction",
    )
    val animatedColor by animateColorAsState(
        targetValue = when {
            dbm == null -> Editorial.Hairline
            fraction >= 0.66f -> Editorial.Green
            fraction >= 0.33f -> Editorial.Amber
            else -> Editorial.Red
        },
        animationSpec = tween(durationMillis = 140),
        label = "walk-gauge-colour",
    )

    val advice = walk.advice
    val headline = headlineFor(advice, recording)
    val detail = detailFor(advice, steps, recording)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 16.dp.toPx()
                val radius = minOf(size.width, size.height) / 2f - stroke
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                val arcSize = Size(radius * 2, radius * 2)
                drawArc(
                    color = Editorial.Hairline,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = animatedColor,
                    startAngle = 140f,
                    sweepAngle = 260f * animatedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = dbm?.toString() ?: "--",
                    style = MaterialTheme.typography.displaySmall,
                    color = Editorial.Ink,
                )
                Text(
                    text = when (kind) {
                        RadioStrengthFeed.Kind.WIFI -> "Wi-Fi dBm"
                        RadioStrengthFeed.Kind.CELLULAR -> "Mobile dBm"
                        RadioStrengthFeed.Kind.NONE -> "No signal"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.InkMid,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        // The scale the arc is drawn on. Without the two ends an arc position means nothing:
        // three quarters round could be a healthy link or a dead one depending on the network,
        // and a number in the middle with no scale beside it invites the reader to guess.
        Row(
            Modifier.fillMaxWidth(0.7f),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = gaugeScale(kind).first,
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Muted,
            )
            Text(
                text = gaugeScale(kind).second,
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Muted,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = headline.text,
            style = MaterialTheme.typography.headlineSmall,
            color = headline.color,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkMid,
            textAlign = TextAlign.Center,
        )

        if (walk.history.size >= 2) {
            Spacer(Modifier.height(16.dp))
            WalkStrip(walk)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your last ${walk.history.size} readings, oldest on the left. " +
                    "Green ring: the best spot you reached. Blue dot: you, now.",
                style = MaterialTheme.typography.labelSmall,
                color = Editorial.Muted,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "This phone has no compass, so it can't point. Instead it remembers where " +
                "the signal was best and tells you how far to walk back.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Editorial.Bone, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
}

/** The recent walk as a line: higher is stronger, the best spot ringed, you at the end. */
@Composable
private fun WalkStrip(walk: WalkTracker.Snapshot) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(76.dp),
    ) {
        val points = walk.history
        if (points.size < 2) return@Canvas
        val low = points.minOf { it.dbm }
        val high = points.maxOf { it.dbm }
        // Under six dB of spread the wiggle is noise: keep the line flat and mid-height.
        val span = max(high - low, 6.0)
        val middle = (low + high) / 2.0
        val pad = 10.dp.toPx()

        fun x(index: Int): Float = pad + (size.width - pad * 2) * index / (points.size - 1).toFloat()
        fun y(dbm: Double): Float = (size.height * (0.5 - (dbm - middle) / span * 0.8)).toFloat()

        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(x(index), y(point.dbm)) else path.lineTo(x(index), y(point.dbm))
        }
        drawPath(
            path = path,
            color = Editorial.InkMid.copy(alpha = 0.55f),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val bestIndex = walk.best?.let { points.lastIndexOf(it) } ?: -1
        if (bestIndex >= 0) {
            val spot = Offset(x(bestIndex), y(points[bestIndex].dbm))
            drawCircle(color = Editorial.Green, radius = 8.dp.toPx(), center = spot, style = Stroke(3.dp.toPx()))
        }
        val last = points.lastIndex
        drawCircle(color = Editorial.Blue, radius = 5.dp.toPx(), center = Offset(x(last), y(points[last].dbm)))
    }
}

private data class Headline(val text: String, val color: Color)

private fun headlineFor(advice: WalkTracker.Advice, recording: Boolean): Headline = when {
    !recording && advice.kind == WalkTracker.AdviceKind.LEARNING ->
        Headline("Ready", Editorial.InkSoft)
    else -> when (advice.kind) {
        WalkTracker.AdviceKind.LEARNING -> Headline("Keep walking slowly", Editorial.InkSoft)
        WalkTracker.AdviceKind.AT_BEST -> Headline("Best spot so far", Editorial.Green)
        WalkTracker.AdviceKind.GO_BACK -> Headline(
            advice.stepsBack?.takeIf { it > 0 }?.let { "Go back about ${roundSteps(it)} steps" }
                ?: "Go back a little",
            Editorial.Blue,
        )
        WalkTracker.AdviceKind.KEEP_EXPLORING -> Headline("Keep looking", Editorial.InkSoft)
    }
}

private fun detailFor(advice: WalkTracker.Advice, steps: Int, recording: Boolean): String = when {
    !recording && advice.kind == WalkTracker.AdviceKind.LEARNING ->
        "Tap Start, then walk slowly. I'll watch the signal as you go."
    advice.kind == WalkTracker.AdviceKind.LEARNING -> "Learning the area: about $steps steps so far"
    advice.kind == WalkTracker.AdviceKind.AT_BEST -> "The signal here is as good as anywhere you've been"
    advice.kind == WalkTracker.AdviceKind.GO_BACK ->
        "It was about ${(advice.gainDb ?: 0.0).roundToInt()} dB stronger there"
    else -> "Not the best spot, but not much worse either"
}

/** Step counts are approximate, so say them to the nearest few. */
private fun roundSteps(steps: Int): Int = if (steps < 10) steps else (steps / 5.0).roundToInt() * 5

/** Where a reading sits on the dial, 0 to 1, on the scale for the kind of link it came from. */
private fun gaugeFraction(dbm: Int, kind: RadioStrengthFeed.Kind): Float = when (kind) {
    RadioStrengthFeed.Kind.WIFI -> ((dbm + 90f) / 50f).coerceIn(0f, 1f)
    else -> ((dbm + 120f) / 50f).coerceIn(0f, 1f)
}

/** The two ends of that same scale, printed under the arc so the arc can be read. */
private fun gaugeScale(kind: RadioStrengthFeed.Kind): Pair<String, String> = when (kind) {
    RadioStrengthFeed.Kind.WIFI -> "-90 dBm" to "-40 dBm"
    else -> "-120 dBm" to "-70 dBm"
}

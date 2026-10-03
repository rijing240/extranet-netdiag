package dev.extranet.netdiag.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.extranet.netdiag.measure.RadarTracker
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The radar: turn on the spot, and the dial shows where the signal is stronger.
 *
 * The dial is a compass card that always faces the way the phone faces. The top of the screen is
 * "straight ahead"; the ring turns underneath it as the phone turns, so a bar that points at the
 * top is a direction the signal is strong in *right now*, and the instruction in the middle says
 * how far to turn to line up with the best one. Nothing needs interpreting: bars are longer and
 * greener where the signal is stronger, empty where nobody has pointed the phone yet, and the
 * blue ring around the edge fills as the circle is measured.
 *
 * Three things on the dial are honest about uncertainty. The blue marker is where the strongest
 * direction is, and the pale wedge around it is how far that direction might be off: wide while
 * the evidence is thin, narrow once it is firm. Under the dial a pace bar shows how fast the
 * phone is turning against the pace this radio can keep up with, because turning faster than
 * the radio reports blurs the answer, and the person turning cannot otherwise know.
 *
 * Everything drawn comes from [RadarEngine.Frame]. The frame is passed as state and read inside
 * the drawing code, so a sixty-frames-a-second heading redraws the dial without recomposing the
 * screen around it; only the words recompose, and only when they change.
 */
@Composable
public fun SignalRadar(
    frame: State<RadarEngine.Frame>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // The badge says what north it is pointing at. A compass with no location is reading
    // magnetic north, which on much of the planet is not where the map's north is - the
    // difference is the declination, and it can be twenty degrees or more. Marking that badge
    // "N" anyway is a claim the instrument cannot support, so the badge is drawn hollow and
    // labelled as magnetic until a position has given the engine a real declination.
    val northLabel = remember(textMeasurer) {
        textMeasurer.measure(
            text = "N",
            style = TextStyle(
                fontFamily = Editorial.Grotesk,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Editorial.Paper,
            ),
        )
    }

    // The radar only produces a new set of readings about once a second, and drawing those
    // straight onto the dial made the whole card twitch: bars jumped from one length to the next
    // a dozen times a second as the phone turned, and the target leapt between directions. The
    // truth changes at the radio's rate and has to be drawn at the display's rate, so the card
    // now keeps its own moving parts and eases each new measurement into them. The numbers on
    // screen are still the measured ones - only the journey between them is drawn.
    val radarSnapshot by remember { derivedStateOf { frame.value.radar } }
    val motion = remember { RadarMotion(radarSnapshot.bins.size) }
    LaunchedEffect(radarSnapshot) { motion.toward(radarSnapshot) }

    // The strength, counted from one reading to the next rather than swapped, so a person
    // watching it while turning can see which way it is going.
    val latestDbm by remember { derivedStateOf { frame.value.dbm } }
    val shownDbm by animateFloatAsState(
        targetValue = (latestDbm ?: 0).toFloat(),
        animationSpec = tween(READING_MILLIS, easing = FastOutSlowInEasing),
        label = "live-reading",
    )

    val readout by remember { derivedStateOf { readoutFor(frame.value) } }
    val liveLine by remember { derivedStateOf { liveLineFor(frame.value, shownDbm) } }
    val accuracyLine by remember { derivedStateOf { accuracyLineFor(frame.value) } }
    // Which north the dial is reading, said whenever it is not the map's north. A direction is
    // still perfectly usable for steering by - "turn right 40 degrees" does not care which
    // north the dial is drawn against - but a badge marked N on a magnetic reading is a claim
    // about the map that the instrument is not making, and the difference is up to 20 degrees.
    val northLine by remember { derivedStateOf { northLineFor(frame.value) } }
    val warning by remember { derivedStateOf { warningFor(frame.value) } }
    val showPace by remember { derivedStateOf { frame.value.recording && frame.value.headingDegrees != null } }
    val pace by remember { derivedStateOf { paceFor(frame.value) } }
    val circleSeconds by remember {
        derivedStateOf { circleSecondsFor(frame.value.idealTurnRate) }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val current = frame.value
                val heading = (current.headingDegrees ?: 0.0).toFloat()
                val snapshot = current.radar
                val center = Offset(size.width / 2f, size.height / 2f)
                val outer = min(size.width, size.height) / 2f - 18.dp.toPx()
                val ringStroke = 4.dp.toPx()
                val innerRadius = outer * 0.50f
                val maxBarRadius = outer - ringStroke - 6.dp.toPx()
                val sweep = 360f / snapshot.bins.size

                drawCircle(color = Editorial.Bone, radius = outer, center = center)

                // The ring, the bars and the target turn together, opposite to the phone.
                rotate(degrees = -heading, pivot = center) {
                    // Coverage ring: blue where that direction has been measured, fading in and out
                    // rather than switching, so the ring looks like it is filling.
                    for (index in snapshot.bins.indices) {
                        drawArc(
                            color = lerp(Editorial.Hairline, Editorial.Blue, motion.measured[index].value),
                            startAngle = index * sweep - 90f + 0.8f,
                            sweepAngle = sweep - 1.6f,
                            useCenter = false,
                            topLeft = Offset(center.x - outer + ringStroke / 2f, center.y - outer + ringStroke / 2f),
                            size = Size((outer - ringStroke / 2f) * 2f, (outer - ringStroke / 2f) * 2f),
                            style = Stroke(ringStroke, cap = StrokeCap.Butt),
                        )
                    }

                    // Signal bars: one per direction, long and green where the signal is strong. The
                    // stub is always drawn underneath, and the bar eases out of it as that
                    // direction is measured and back into it as it ages out of the evidence,
                    // so the ring of bars never blinks.
                    val minLength = 8.dp.toPx()
                    for (index in snapshot.bins.indices) {
                        val start = index * sweep - 90f + 1f
                        drawArc(
                            color = Editorial.Hairline,
                            startAngle = start,
                            sweepAngle = sweep - 2f,
                            useCenter = false,
                            topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                            size = Size(innerRadius * 2f, innerRadius * 2f),
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt),
                        )
                        val shown = motion.measured[index].value
                        if (shown <= 0.01f) continue
                        val norm = motion.lengths[index].value
                        val length = minLength + (maxBarRadius - innerRadius - minLength) * norm
                        val radius = innerRadius + length / 2f
                        drawArc(
                            color = strengthColor(norm).copy(alpha = shown),
                            startAngle = start,
                            sweepAngle = sweep - 2f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(length, cap = StrokeCap.Butt),
                        )
                    }

                    // The target: a blue marker on the ring at the strongest direction, with a
                    // pale wedge for how far it might be off - one standard error either side.
                    // Drawn only while a direction is claimed, but it slides to each new claim
                    // the short way round the circle rather than jumping the long way.
                    if (snapshot.bearingDegrees != null) {
                        val bearing = motion.bearing.value.toDouble()
                        val error = motion.error.value.coerceIn(3f, 45f)
                        val wedgeRadius = (innerRadius + outer) / 2f
                        drawArc(
                            color = Editorial.Blue.copy(alpha = 0.16f),
                            startAngle = (bearing - 90.0).toFloat() - error,
                            sweepAngle = error * 2f,
                            useCenter = false,
                            topLeft = Offset(center.x - wedgeRadius, center.y - wedgeRadius),
                            size = Size(wedgeRadius * 2f, wedgeRadius * 2f),
                            style = Stroke(outer - innerRadius, cap = StrokeCap.Butt),
                        )
                        val radians = Math.toRadians(bearing - 90.0)
                        val start = Offset(
                            center.x + (innerRadius - 6.dp.toPx()) * cos(radians).toFloat(),
                            center.y + (innerRadius - 6.dp.toPx()) * sin(radians).toFloat(),
                        )
                        val end = Offset(
                            center.x + (outer - ringStroke / 2f) * cos(radians).toFloat(),
                            center.y + (outer - ringStroke / 2f) * sin(radians).toFloat(),
                        )
                        drawLine(
                            color = Editorial.Blue,
                            start = start,
                            end = end,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(color = Editorial.Blue, radius = 9.dp.toPx(), center = end)
                        drawCircle(color = Editorial.Paper, radius = 3.5.dp.toPx(), center = end)
                    }
                }

                // North stays upright: a small red badge on the ring where north is, which
                // follows the ring round but never turns over. Filled means the heading has been
                // corrected to true north; hollow means it is still magnetic, and the line under
                // the dial says so in words.
                //
                // The badge goes where north falls in the dial's own frame, which is not zero.
                // That frame is the gyroscope's yaw, whose origin is wherever the sensor happened
                // to start, so the position is asked for rather than assumed. When the phone cannot
                // say where north is - no magnetometer, or one reporting itself unreliable - there
                // is no honest place to put the badge and none is drawn: a badge at a made-up
                // position is a claim about the map the instrument is not making. The scan carries
                // on regardless, because it measures turning and turning needs no north.
                val northBearing = current.northBearingDegrees
                if (current.headingDegrees != null && northBearing != null) {
                    val radians = Math.toRadians(northBearing - heading - 90.0)
                    val badgeRadius = outer - ringStroke / 2f
                    val badge = Offset(
                        center.x + badgeRadius * cos(radians).toFloat(),
                        center.y + badgeRadius * sin(radians).toFloat(),
                    )
                    val trueNorth = current.trueNorth
                    if (!trueNorth) {
                        drawCircle(color = Editorial.Bone, radius = 10.dp.toPx(), center = badge)
                    }
                    drawCircle(color = Editorial.Red, radius = 10.dp.toPx(), center = badge, style = Stroke(2.dp.toPx()))
                    drawText(
                        textLayoutResult = northLabel,
                        color = if (trueNorth) Editorial.Paper else Editorial.Red,
                        topLeft = Offset(
                            badge.x - northLabel.size.width / 2f,
                            badge.y - northLabel.size.height / 2f,
                        ),
                    )
                }

                // "Straight ahead": fixed at the top, pointing at the ring.
                val ahead = Path().apply {
                    moveTo(center.x, center.y - outer - 1.dp.toPx())
                    lineTo(center.x - 9.dp.toPx(), center.y - outer - 15.dp.toPx())
                    lineTo(center.x + 9.dp.toPx(), center.y - outer - 15.dp.toPx())
                    close()
                }
                drawPath(path = ahead, color = Editorial.Ink)
            }

            Column(
                modifier = Modifier.fillMaxWidth(0.46f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = readout.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = readout.color,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = readout.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Editorial.InkMid,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = liveLine,
            style = MaterialTheme.typography.labelLarge,
            color = Editorial.InkSoft,
        )
        accuracyLine?.let { line ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.InkMid,
                textAlign = TextAlign.Center,
            )
        }
        northLine?.let { line ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Muted,
                textAlign = TextAlign.Center,
            )
        }

        if (showPace) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(0.8f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Turning pace", style = MaterialTheme.typography.labelMedium, color = Editorial.InkMid)
                    Text(pace.word, style = MaterialTheme.typography.labelMedium, color = pace.color)
                }
                Spacer(Modifier.height(6.dp))
                PaceBar(frame)
            }
        }

        warning?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Amber,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Editorial.Amber.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Hold the phone out in front of you and turn slowly with your whole body. " +
                "A full circle takes about $circleSeconds seconds. Longer, greener bars mean a stronger signal.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The pace bar: a track with a green band for the pace this radio can keep up with and a dot for
 * how fast the phone is turning right now. The dot stays in the band when the pace is right,
 * slips left of it when too slow to be useful and past its right edge when too fast.
 */
@Composable
private fun PaceBar(frame: State<RadarEngine.Frame>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val current = frame.value
        val ideal = current.idealTurnRate.toFloat()
        val limit = current.maxTurnRate.toFloat()
        val scaleMax = limit * 1.5f
        val h = size.height

        fun x(rate: Float): Float = size.width * (rate / scaleMax).coerceIn(0f, 1f)

        drawRoundRect(
            color = Editorial.Hairline,
            topLeft = Offset.Zero,
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
        val bandStart = x(ideal * SLOW_FRACTION)
        val bandEnd = x(limit)
        drawRoundRect(
            color = Editorial.Green.copy(alpha = 0.35f),
            topLeft = Offset(bandStart, 0f),
            size = Size(max(bandEnd - bandStart, 0f), h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
        val rate = abs(current.turnRateDegPerSec).toFloat()
        val dot = when {
            rate > limit -> Editorial.Amber
            rate < ideal * SLOW_FRACTION -> Editorial.Muted
            else -> Editorial.Green
        }
        drawCircle(
            color = dot,
            radius = h * 0.9f,
            center = Offset(x(rate).coerceIn(h, size.width - h), h / 2f),
        )
    }
}

/**
 * The dial's moving parts, carried between measurements.
 *
 * The radar hands over a new set of bins about once a second, and a card that jumped straight to
 * each one was unreadable while the phone turned: the bars changed length twelve times a second
 * in fifteen-degree steps and the marker leapt from one direction to another. This holds the
 * drawn value of every bin as an animation and eases the measured value into it, so the display
 * runs at its own sixty frames a second while the evidence arrives at the radio's rate.
 *
 * Nothing here changes a number. It only decides how the card travels between the numbers the
 * tracker reports, and it always travels the short way round the circle: a marker at 350 degrees
 * easing to 10 degrees goes through north, not backwards through everything.
 */
private class RadarMotion(binCount: Int) {

    /** Each direction's bar length, 0 (weakest) to 1 (strongest) within the measured circle. */
    val lengths: List<Animatable<Float, AnimationVector1D>> =
        List(binCount) { Animatable(0f) }

    /** Each direction's measured-ness, 0 for a direction nobody has pointed the phone at. */
    val measured: List<Animatable<Float, AnimationVector1D>> =
        List(binCount) { Animatable(0f) }

    /** The strongest direction, in degrees, allowed to run past 360 so it always turns forwards. */
    val bearing: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** How far that direction might be off, in degrees. */
    val error: Animatable<Float, AnimationVector1D> = Animatable(0f)

    private var lastBearing: Double? = null

    /** Eases the dial from where it is to where [snapshot] says it should be. */
    suspend fun toward(snapshot: RadarTracker.Snapshot) = coroutineScope {
        val minDbm = snapshot.minDbm
        val maxDbm = snapshot.maxDbm
        val midDbm = if (minDbm != null && maxDbm != null) (minDbm + maxDbm) / 2.0 else 0.0
        // Under eight dB of spread the differences are noise, so the bars are held mid-length
        // rather than stretching a one-dB wobble into a dramatic shape.
        val span = if (minDbm != null && maxDbm != null) max(maxDbm - minDbm, 8.0) else 8.0

        for (index in snapshot.bins.indices) {
            val bin = snapshot.bins[index]
            val targetLength = if (bin == null) {
                0f
            } else {
                (0.5 + (bin.meanDbm - midDbm) / span).coerceIn(0.0, 1.0).toFloat()
            }
            launch {
                lengths[index].animateTo(targetLength, tween(BAR_MILLIS, easing = FastOutSlowInEasing))
            }
            launch {
                measured[index].animateTo(
                    targetValue = if (bin == null) 0f else 1f,
                    animationSpec = tween(COVER_MILLIS, easing = LinearOutSlowInEasing),
                )
            }
        }

        val claimed = snapshot.bearingDegrees
        if (claimed != null) {
            val previous = lastBearing
            val target = if (previous == null) {
                claimed
            } else {
                // Never unwind a full circle: carry the angle forward instead, so 350 to 10 is
                // twenty degrees through north and not three hundred and forty the other way.
                previous + RadarTracker.turnDegrees(previous, claimed)
            }
            lastBearing = target
            launch {
                bearing.animateTo(target.toFloat(), tween(MARKER_MILLIS, easing = FastOutSlowInEasing))
            }
            launch {
                error.animateTo(
                    targetValue = (snapshot.bearingErrorDegrees ?: 0.0).toFloat(),
                    animationSpec = tween(MARKER_MILLIS, easing = LinearOutSlowInEasing),
                )
            }
        }
    }
}

/** What the middle of the dial says. */
private data class Readout(val title: String, val subtitle: String, val color: Color)

private fun readoutFor(frame: RadarEngine.Frame): Readout {
    val snapshot = frame.radar
    val heading = frame.headingDegrees
    return when {
        snapshot.status == RadarTracker.Status.FOUND && snapshot.bearingDegrees != null -> {
            if (heading == null) {
                Readout("Hold steady", "Waiting for the compass", Editorial.InkSoft)
            } else {
                // Non-null throughout: the branch is only reached with a claimed bearing.
                val claimed = snapshot.bearingDegrees!!
                val turn = RadarTracker.turnDegrees(heading, claimed)
                val rounded = (abs(turn) / 5.0).roundToInt() * 5
                // Facing it means within the answer's own error bar, never tighter than ten
                // degrees (a phone is not held that steadily) and never looser than twenty.
                val tolerance = (snapshot.bearingErrorDegrees ?: 0.0).coerceIn(ALIGNED_DEGREES, MAX_ALIGNED_DEGREES)
                // The direction is named as well as measured, where the phone can name it. A
                // number of degrees is a quantity; "the north-east side" is something a
                // person standing in a room can act on without doing arithmetic against a
                // wall. Where the compass cannot say which way that is, the turn is still
                // given - it is measured from the gyroscope and needs no north - and only the
                // name is dropped, rather than a name that could be tens of degrees wrong.
                val where = frame.compassOffsetDegrees?.let { compassPoint(claimed + it) }
                when {
                    abs(turn) <= tolerance && where != null ->
                        Readout("Best direction found", "Facing $where gave the strongest reading", Editorial.Green)
                    abs(turn) <= tolerance ->
                        Readout("Best direction found", RELATIVE_FOUND, Editorial.Green)
                    turn > 0 && where != null ->
                        Readout("Turn right $rounded°", "Best readings came facing $where", Editorial.Blue)
                    turn > 0 ->
                        Readout("Turn right $rounded°", RELATIVE_TURN, Editorial.Blue)
                    where != null ->
                        Readout("Turn left $rounded°", "Best readings came facing $where", Editorial.Blue)
                    else ->
                        Readout("Turn left $rounded°", RELATIVE_TURN, Editorial.Blue)
                }
            }
        }
        // Everything else is a refusal, and a refusal is not one thing. The estimator declines to
        // name a direction for six different reasons, and each one asks the user to do something
        // different: keep turning, keep turning for a second lap, turn again more slowly, walk to
        // another spot, or stand still a moment while the answer settles. One sentence was printed
        // for all of them - "the readings disagree, scan again a little slower" - which is how a
        // user ends up on a fourth lap of a problem that was never disagreement, being told to fix
        // something that is not wrong.
        else -> refusalFor(frame)
    }
}

/**
 * What the dial says while no direction is being claimed, in the refusal's own words.
 *
 * The reason comes from the gate that actually fired - [RadarTracker.Refusal] - rather than being
 * inferred from whatever numbers happen to be on screen, so the instruction matches the problem.
 * Where another lap of the same spot would not help - a room with no direction in it, a signal
 * that peaks two ways - it says so and sends the user somewhere else instead of asking them to
 * repeat something that has already been tried.
 */
private fun refusalFor(frame: RadarEngine.Frame): Readout {
    val snapshot = frame.radar
    val measured = (snapshot.coverage * 100).roundToInt()
    // Before Start has been pressed, the useful sentence is about starting rather than about the
    // circle, whatever the estimator would say about the empty radar behind it.
    if (!frame.recording && snapshot.coverage <= 0.0) {
        return if (frame.headingDegrees == null) {
            Readout("Ready", "Waiting for the compass", Editorial.InkSoft)
        } else {
            Readout("Ready", "Tap Start, then turn slowly in a circle", Editorial.InkSoft)
        }
    }
    return when (snapshot.refusal) {
        RadarTracker.Refusal.NOT_TURNING ->
            Readout("Keep turning", "$measured% of the circle measured", Editorial.InkSoft)
        RadarTracker.Refusal.UNMEASURED_ARC ->
            Readout(
                "Keep turning",
                "A ${snapshot.largestGapDegrees.roundToInt()}\u00b0 stretch of the circle is still unmeasured",
                Editorial.InkSoft,
            )
        // The second-lap advice belongs to this gate and no other. One lap at the recommended
        // pace delivers about fourteen readings and the tracker wants sixteen effective ones, so
        // this is the refusal that a second lap actually fixes - a good scan that looks like a
        // failure from the outside, which is why it is worth naming.
        RadarTracker.Refusal.NOT_ENOUGH_READINGS ->
            if (frame.laps >= 1) {
                Readout("One more lap", "Keep turning for a second lap round the same circle", Editorial.InkSoft)
            } else {
                Readout("Checking", "Keep turning slowly: a lap measures every direction once", Editorial.InkSoft)
            }
        RadarTracker.Refusal.NOT_ENOUGH_DIFFERENCE ->
            if (snapshot.contrastDb < FLAT_CONTRAST_DB) {
                Readout("Same all around", "No direction stands out here. Try another spot", Editorial.InkSoft)
            } else {
                Readout(
                    "No clear direction",
                    "There is a swing here, but not enough to steer by. Turn again, a little slower",
                    Editorial.InkSoft,
                )
            }
        RadarTracker.Refusal.TOO_UNCERTAIN ->
            Readout(
                "Almost there",
                "A direction is visible but not yet trustworthy. Turn again, a little slower",
                Editorial.InkSoft,
            )
        RadarTracker.Refusal.MORE_THAN_ONE_DIRECTION ->
            Readout(
                "Two directions",
                "The signal peaks two ways - you may be next to a wall. Try another spot",
                Editorial.InkSoft,
            )
        RadarTracker.Refusal.CONFIRMING ->
            Readout("Checking the answer", "Hold still a moment while the direction settles", Editorial.InkSoft)
        // Only reachable on a snapshot that carries no reason at all; the circle is then the only
        // thing there is to go on.
        null ->
            if (snapshot.coverage >= CHECKING_COVERAGE) {
                Readout("Checking", "Keep turning slowly", Editorial.InkSoft)
            } else {
                Readout("Keep turning", "$measured% of the circle measured", Editorial.InkSoft)
            }
    }
}

private fun liveLineFor(frame: RadarEngine.Frame, shownDbm: Float): String {
    val dbm = frame.dbm ?: return "Waiting for a signal reading"
    val kind = when (frame.kind) {
        RadioStrengthFeed.Kind.WIFI -> "Wi-Fi"
        RadioStrengthFeed.Kind.CELLULAR -> "Mobile"
        RadioStrengthFeed.Kind.NONE -> ""
    }
    // The animated value, so the number travels between readings instead of swapping. It is only
    // ever a fraction of a dB away from the measured one while it is moving.
    val shown = shownDbm.roundToInt()
    val signal = if (kind.isEmpty()) "$shown dBm" else "$kind signal  $shown dBm"
    // The count of readings the radar has actually taken, because that is the honest answer to
    // "is anything being measured?" while a scan runs. A number stuck on zero says the radio or
    // the compass is not delivering, which no amount of turning advice would explain.
    val readings = if (frame.recording) "  ·  ${frame.readings} readings" else ""
    // The lap count, because it is the honest answer to "how much longer" once the circle is
    // measured: one lap is often not enough, and a number going up is the only sign that the
    // second lap is doing anything.
    val laps = if (frame.recording && frame.laps >= 1) "  ·  lap ${frame.laps + 1}" else ""
    return signal + readings + laps
}

/**
 * The line that says how much to trust the direction, in words a person can use: how far off it
 * might be, and how much better the best side is. Absent until a direction has been claimed.
 */
private fun accuracyLineFor(frame: RadarEngine.Frame): String? {
    val snapshot = frame.radar
    val error = snapshot.bearingErrorDegrees
    if (snapshot.status != RadarTracker.Status.FOUND || snapshot.bearingDegrees == null || error == null) return null
    val roundedError = max(5, (error / 5.0).roundToInt() * 5)
    // contrastDb is peak-to-trough. The estimated peak is half that above the scan's fitted
    // average, so report half rather than presenting the full swing as the gain over average.
    val gain = max(1, (snapshot.contrastDb / 2.0).roundToInt())
    return "Estimated direction ±$roundedError°  ·  about $gain dB above this scan's average"
}

/**
 * The line under the dial that says which north it is reading.
 *
 * Null once a position has given the engine a declination, because then it is true north and
 * there is nothing to add. Before that it says magnetic north, and says why that is not a
 * defect: the reading is still right for every instruction the app gives.
 */
private fun northLineFor(frame: RadarEngine.Frame): String? = when {
    frame.headingDegrees == null || frame.trueNorth -> null
    // The badge is hollow here, so the line says what the hollow means: the marked direction is
    // right for steering, and only the relationship to the map's north is approximate.
    else -> "Compass is reading magnetic north - the direction is still right, the map's north may differ"
}

private fun warningFor(frame: RadarEngine.Frame): String? = when {
    // A compass that cannot be trusted no longer stops a scan: the radar measures turning, and
    // on a phone with a gyroscope the turning is measured without the compass at all. So this
    // is a note about what the instrument cannot say, not a reason the answer is withheld - and
    // it is worth saying, because "turn right 40 degrees" with no compass point beside it looks
    // like the instrument has lost the plot.
    frame.labelsHidden -> RELATIVE_NOTE
    frame.needsCalibration -> "Compass accuracy is low: a figure-8 wave helps it settle"
    frame.recording && frame.turningTooFast ->
        "Turn a little slower: about ${circleSecondsFor(frame.idealTurnRate)} seconds for a full circle"
    frame.recording && frame.scanSeconds >= SLOW_NUDGE_SECONDS && frame.radar.coverage < CHECKING_COVERAGE &&
        abs(frame.turnRateDegPerSec) < MIN_TURNING_RATE -> "Keep turning: a slow, steady circle works best"
    else -> null
}

/** The word and colour for the pace bar. */
private data class Pace(val word: String, val color: Color)

private fun paceFor(frame: RadarEngine.Frame): Pace {
    val rate = abs(frame.turnRateDegPerSec)
    return when {
        rate > frame.maxTurnRate -> Pace("Too fast", Editorial.Amber)
        rate < frame.idealTurnRate * SLOW_FRACTION -> Pace("Too slow", Editorial.InkMid)
        else -> Pace("Good", Editorial.Green)
    }
}

/** Seconds for one full circle at the pace that suits this radio, to the nearest five. */
private fun circleSecondsFor(idealTurnRate: Double): Int =
    ((360.0 / idealTurnRate) / 5.0).roundToInt().coerceAtLeast(1) * 5

/** Red through amber to green as a direction goes from weakest to strongest. */
private fun strengthColor(norm: Float): Color =
    if (norm < 0.5f) {
        lerp(Editorial.Red, Editorial.Amber, norm * 2f)
    } else {
        lerp(Editorial.Amber, Editorial.Green, (norm - 0.5f) * 2f)
    }

/** Within this many degrees of the strongest direction counts as facing it, at the least. */
private const val ALIGNED_DEGREES: Double = 10.0

/** ... and never counts as facing it from further than this, whatever the error bar. */
private const val MAX_ALIGNED_DEGREES: Double = 20.0

/**
 * A bearing in the words people use for a compass: the nearest of eight points, rounded to the
 * half-way mark so it names the sector rather than pretending to a precision of one degree.
 *
 * The eight points are the ones on a real compass face, not invented to fit: at 22.5 degrees
 * from north the answer is genuinely "north-east" and not "north" with a decimal on it.
 */
private fun compassPoint(degrees: Double): String {
    val points = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    val index = Math.round(RadarTracker.normalize(degrees) / 45.0).toInt() % points.size
    return points[index]
}

/** How long a bar takes to travel from its last measured length to the new one. */
private const val BAR_MILLIS: Int = 900

/** The coverage ring fades rather than switching, so a direction appearing is not a flash. */
private const val COVER_MILLIS: Int = 700

/** The target slides to a new claim instead of appearing at it. */
private const val MARKER_MILLIS: Int = 800

/** How long the live strength takes to travel between readings. */
private const val READING_MILLIS: Int = 800

/**
 * The subtitles used when the phone cannot put a name to a direction.
 *
 * The turn is still given, because it is measured from the gyroscope and a bearing is only ever
 * a difference. What is missing is the compass point, so the sentence points at the marker on
 * the dial instead of at "north-east" - which is the same instruction, in the one set of words
 * that is true on this phone.
 */
private const val RELATIVE_TURN: String = "Best readings came from the marked direction"
private const val RELATIVE_FOUND: String = "Strongest reading came from the marked direction"

/**
 * Said when the phone cannot name a direction at all.
 *
 * The dial still works and the markers are still in the right place relative to each other; what
 * is missing is the map. Saying exactly that is better than saying nothing, because a user who
 * has been told "turn right 40 degrees" and can see the marker will act on it correctly, while a
 * user who has been told nothing concludes the app is broken.
 */
private const val RELATIVE_NOTE: String = "Direction shown relative to where you started"

/** Under this swing the signal is the same all round, not merely hard to read. */
private const val FLAT_CONTRAST_DB: Double = 3.0

/** Past this much of the circle measured, the radar is confirming rather than still looking. */
private const val CHECKING_COVERAGE: Double = 0.6

/** Below this fraction of the ideal pace the phone is turning too slowly to fill the circle. */
private const val SLOW_FRACTION: Float = 0.3f

/** How long a scan runs before a lack of turning is worth mentioning. */
private const val SLOW_NUDGE_SECONDS: Int = 10

/** Degrees a second under which the phone is as good as still. */
private const val MIN_TURNING_RATE: Double = 3.0

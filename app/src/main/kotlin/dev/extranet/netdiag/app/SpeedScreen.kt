package dev.extranet.netdiag.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.SpeedTest
import java.util.Locale

/**
 * The Speed tab: a live test, a plain verdict, numbers on request.
 *
 * Three moments, one dial. Idle says what the test costs. Running shows the rate the current stage
 * is moving at, with the stage named above it and the three stages laid out underneath, because a
 * speed test that shows nothing for twenty seconds reads as broken - and because a test that does
 * ping, then download, then upload should say so rather than being one mystery bar. Done leads
 * with the rating, then the four numbers, then the verdict, then the raw measurements behind a
 * fold.
 *
 * Everything here is built to be read at a large font size, which is the case that breaks speed
 * tests in particular: the number in the middle of the dial is the largest text in the app, and
 * "12.4 Mbps" set at full size in a circle is a line that stops fitting well before the body copy
 * does. So the value and the unit are set separately, the value is capped, and the four results
 * reflow from two columns into one rather than squeezing.
 */
@Composable
public fun SpeedScreen(
    state: SpeedUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Speed",
                title = "How fast is it?",
            )
        }

        when (state) {
            SpeedUiState.Idle -> {
                item {
                    SectionCard {
                        StageStrip(listOf("Ping" to StageMark.WAITING, "Download" to StageMark.WAITING, "Upload" to StageMark.WAITING))
                        Spacer(Modifier.height(6.dp))
                        SpeedDial(
                            valueText = "--",
                            unitText = "Mbps",
                            caption = "Ready when you are",
                            fraction = 0f,
                            indeterminate = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        InkButton("Start speed test", onClick = onRun, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Three stages: how fast the network answers, how fast it downloads, " +
                                "how fast it uploads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Editorial.InkMid,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            SpeedTest.dataUseNotice(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Editorial.Muted,
                        )
                    }
                }
            }

            is SpeedUiState.Running -> {
                item {
                    SectionCard {
                        StageStrip(stageMarks(state.stage))
                        Spacer(Modifier.height(6.dp))
                        val measuring = state.kbps == null
                        SpeedDial(
                            valueText = state.kbps?.let { formatValue(it) } ?: "…",
                            unitText = if (measuring) "ms" else "Mbps",
                            caption = when (state.stage) {
                                "ping" -> "Measuring response time"
                                "download" -> "Downloading test data"
                                else -> "Uploading test data"
                            },
                            fraction = state.fraction?.toFloat() ?: 0f,
                            indeterminate = measuring,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        LineButton("Stop", onClick = onCancel, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is SpeedUiState.Failed -> {
                item {
                    SectionCard {
                        SpeedDial(
                            valueText = "--",
                            unitText = "",
                            caption = state.message,
                            fraction = 0f,
                            indeterminate = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        InkButton("Try again", onClick = onRun, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is SpeedUiState.Done -> doneItems(state, onRun)
        }
    }
}

private fun LazyListScope.doneItems(state: SpeedUiState.Done, onRun: () -> Unit) {
    val report = state.report
    item {
        SectionCard {
            StageStrip(
                listOf(
                    "Ping" to markOf(report, "ping"),
                    "Download" to markOf(report, "download"),
                    "Upload" to markOf(report, "upload"),
                ),
            )
            Spacer(Modifier.height(6.dp))
            // A dial with a full arc around a number that was never measured is a dial claiming
            // a result it does not have, so the arc is only filled when there is a download
            // number to fill it with. What the test could not do is said underneath instead.
            val measured = report.downloadKbps != null
            SpeedDial(
                valueText = report.downloadKbps?.let { formatValue(it) } ?: "--",
                unitText = if (measured) "Mbps" else "",
                caption = report.rating(),
                fraction = if (measured) 1f else 0f,
                indeterminate = false,
                modifier = Modifier.fillMaxWidth(),
            )
            if (measured) {
                Spacer(Modifier.height(8.dp))
                RatingChip(report.rating())
            }
            val failed = report.stages.filter { !it.ok }
            if (failed.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                StageShortfall(failed, report)
            }
            Spacer(Modifier.height(16.dp))
            InkButton("Run again", onClick = onRun, modifier = Modifier.fillMaxWidth())
        }
    }

    item {
        SectionCard(eyebrow = "Your connection") {
            MetricGrid(
                listOf(
                    "Download" to (report.downloadKbps?.let { "${formatValue(it)} Mbps" } ?: "Not measured"),
                    "Upload" to (report.uploadKbps?.let { "${formatValue(it)} Mbps" } ?: "Not measured"),
                    "Ping" to (report.pingMedianMillis?.let { "%.0f ms".format(it) } ?: "Not measured"),
                    "Jitter" to (report.jitterMillis?.let { "%.0f ms".format(it) } ?: "Not measured"),
                ),
            )
        }
    }

    item { VerdictCard(state.verdict) }

    item { StageDetails(report) }
}

/** How a stage stands: finished, running, not started, or tried and failed. */
private enum class StageMark { DONE, RUNNING, WAITING, FAILED }

/**
 * The three stages, laid out in the order they run.
 *
 * The stages run in a fixed order in the engine, so which ones are behind the current one is
 * known rather than guessed - the strip is telling the user what the dial is about to do next,
 * which is the thing a single bar cannot say.
 */
@Composable
private fun StageStrip(stages: List<Pair<String, StageMark>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        stages.forEachIndexed { index, (name, mark) ->
            if (index > 0) {
                // The connector is a fixed sliver, not a share of the row: weighted equally with
                // the stages it was taking a third of the width off each of their names, which
                // is how "Download" ends up broken across two lines as "Downl oad".
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(8.dp)
                        .height(2.dp)
                        .background(Editorial.Hairline),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            color = when (mark) {
                                StageMark.DONE -> Editorial.Green
                                StageMark.RUNNING -> Editorial.Blue
                                StageMark.FAILED -> Editorial.Red
                                StageMark.WAITING -> Editorial.Hairline
                            },
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (mark) {
                        StageMark.DONE -> Editorial.InkSoft
                        StageMark.RUNNING -> Editorial.Blue
                        StageMark.FAILED -> Editorial.Red
                        StageMark.WAITING -> Editorial.Muted
                    },
                    textAlign = TextAlign.Center,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Where the current stage sits: the ones before it are done, the one after it has not started. */
private fun stageMarks(current: String): List<Pair<String, StageMark>> {
    val order = listOf("Ping", "Download", "Upload")
    val index = when (current) {
        "ping" -> 0
        "download" -> 1
        else -> 2
    }
    return order.mapIndexed { position, name ->
        name to when {
            position < index -> StageMark.DONE
            position == index -> StageMark.RUNNING
            else -> StageMark.WAITING
        }
    }
}

/** After a run, each stage's mark comes from the stage's own result rather than from the order. */
private fun markOf(report: SpeedTest.Report, stage: String): StageMark {
    val result = report.stages.firstOrNull { it.name == stage }
        ?: return StageMark.WAITING
    return if (result.ok) StageMark.DONE else StageMark.FAILED
}

/**
 * The dial: a three-quarter arc that fills with the stage's progress, the rate set inside it.
 *
 * The value and the unit are two pieces of text rather than one. "12.4 Mbps" is nine characters
 * set at the largest size in the app, and inside a circle on a 360dp screen that is the first
 * thing to overflow at a large font setting - so the number stands alone and the unit sits under
 * it in a label, and both are free to grow without the line ever having to fit.
 *
 * While the ping stage runs there is no number to show yet, so the arc spins instead of sitting at
 * zero: a full twenty seconds of a still dial is the thing people mistake for a broken app.
 */
@Composable
private fun SpeedDial(
    valueText: String,
    unitText: String,
    caption: String?,
    fraction: Float,
    indeterminate: Boolean,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 280),
        label = "speed-dial-progress",
    )
    val spin by rememberInfiniteTransition(label = "speed-dial-spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "speed-dial-head",
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val radius = minOf(size.width, size.height) / 2f - stroke
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                val arcSize = Size(radius * 2, radius * 2)
                val sweepTotal = 260f
                val start = 140f

                drawArc(
                    color = Editorial.Hairline,
                    startAngle = start,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (animated > 0.002f) {
                    drawArc(
                        color = Editorial.Blue,
                        startAngle = start,
                        sweepAngle = sweepTotal * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                if (indeterminate) {
                    val headStart = start + sweepTotal * (spin / 360f)
                    drawArc(
                        color = Editorial.Blue,
                        startAngle = headStart,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke * 0.6f, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueText,
                    style = CappedDisplay(MaterialTheme.typography.displaySmall),
                    color = Editorial.Ink,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                if (unitText.isNotEmpty()) {
                    Text(
                        unitText,
                        style = MaterialTheme.typography.labelMedium,
                        color = Editorial.InkMid,
                        maxLines = 1,
                    )
                }
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * What the test could not measure, in a sentence, next to what it could.
 *
 * A stage that returns nothing is a real result and it deserves a real sentence rather than a
 * "--" in a cell and a line hidden behind a fold. The important half is the second clause: a
 * download that never started is a completely different situation from a network that is dead,
 * and the only way to tell the reader which one they are in is to name the stage that worked.
 */
@Composable
private fun StageShortfall(failed: List<SpeedTest.StageResult>, report: SpeedTest.Report) {
    val names = failed.map { stageName(it.name).lowercase() }
    val worked = report.pingMedianMillis?.let { " Response time still measured %.0f ms.".format(it) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Editorial.Amber, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "What did not get measured",
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.InkMid,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "The ${names.joinToString(" and ")} stage${if (names.size > 1) "s" else ""} " +
                "got nothing back from the test server, so there is no number to report for " +
                "it.$worked",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
    }
}

/** The rating as a chip, so the eye lands on the judgement before the numbers. */
@Composable
private fun RatingChip(rating: String) {
    val colour = when {
        rating.contains("Great", ignoreCase = true) || rating.contains("Good", ignoreCase = true) ->
            Editorial.Green
        rating.contains("Poor", ignoreCase = true) || rating.contains("Unusable", ignoreCase = true) ->
            Editorial.Red
        else -> Editorial.Amber
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(colour, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(rating, style = MaterialTheme.typography.titleMedium, color = Editorial.Ink)
    }
}

/**
 * The four results, two across where they fit and one down where they do not.
 *
 * A row of label-and-value pairs was fine at the default text size and wrong at a large one: the
 * labels and the values are competing for the same line, and the value is the thing being read.
 * Stacking them into a cell with the label above the value gives the number the whole width, and
 * dropping to a single column at large text is what keeps a 360dp screen honest.
 */
@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = responsiveColumns(maxWidth, wanted = 2, minColumn = 132.dp, fontScale = fontScale)
        if (columns >= 2) {
            Column(Modifier.fillMaxWidth()) {
                metrics.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { (label, value) ->
                            Metric(label, value, Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                metrics.forEach { (label, value) -> Metric(label, value, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.InkMid,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = Editorial.Ink,
        )
    }
}

/** The raw per-stage measurements, folded away. */
@Composable
private fun StageDetails(report: SpeedTest.Report) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        androidx.compose.material3.TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (expanded) "Hide measurements" else "Show measurements",
                style = MaterialTheme.typography.labelLarge,
                color = Editorial.Blue,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            for (stage in report.stages) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        stageName(stage.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Editorial.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (stage.ok) stageValue(stage) else "Failed",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (stage.ok) Editorial.Ink else Editorial.Red,
                        textAlign = TextAlign.End,
                    )
                }
                stage.detail?.let { detail ->
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Editorial.Muted)
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                SpeedTest.dataUseNotice(),
                style = MaterialTheme.typography.bodySmall,
                color = Editorial.Muted,
            )
        }
    }
}

private fun stageName(stage: String): String = when (stage) {
    "ping" -> "Response time"
    "download" -> "Download"
    else -> "Upload"
}

private fun stageValue(stage: SpeedTest.StageResult): String = when (stage.name) {
    "ping" -> "%.0f ms".format(stage.value ?: 0.0)
    else -> formatMbps(stage.value ?: 0.0)
}

/** Kbps in the unit people read: Mbps. */
private fun formatMbps(kbps: Double): String =
    String.format(Locale.ROOT, "%.1f Mbps", kbps / 1_000.0)

/** The number on its own, so it can be set large without dragging its unit along with it. */
private fun formatValue(kbps: Double): String = String.format(Locale.ROOT, "%.1f", kbps / 1_000.0)

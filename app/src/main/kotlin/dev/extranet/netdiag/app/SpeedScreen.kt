package dev.extranet.netdiag.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.SpeedTest
import java.util.Locale

/**
 * The Speed tab: a live test, a plain verdict, numbers on request.
 *
 * The screen has three moments. Idle says what the test costs. Running shows one big live
 * number - the rate the current stage is moving at - with the stage named, because a speed test
 * that shows nothing for twenty seconds reads as broken. Done shows the rating first ("Great -
 * video calls and streaming feel smooth"), then the four numbers, then the raw measurements
 * behind a Details fold for whoever wants them.
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
                        SpeedGauge(label = "Ready", valueText = "--", fraction = 0f, rateLine = null)
                        Spacer(Modifier.height(14.dp))
                        InkButton("Start speed test", onClick = onRun, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text(
                            SpeedTest.dataUseNotice(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Editorial.InkMid,
                        )
                    }
                }
            }

            is SpeedUiState.Running -> {
                item {
                    SectionCard {
                        SpeedGauge(
                            label = stageLabel(state.stage),
                            valueText = state.kbps?.let { formatMbps(it) } ?: "…",
                            fraction = state.fraction?.toFloat() ?: 0.25f,
                            rateLine = when (state.stage) {
                                "ping" -> "Measuring response time"
                                "download" -> "Downloading test data"
                                else -> "Uploading test data"
                            },
                        )
                        Spacer(Modifier.height(14.dp))
                        LineButton("Stop", onClick = onCancel, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is SpeedUiState.Failed -> {
                item {
                    SectionCard {
                        Text(
                            "Couldn't run the test",
                            style = MaterialTheme.typography.titleLarge,
                            color = Editorial.Red,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Editorial.InkSoft)
                        Spacer(Modifier.height(14.dp))
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
            SpeedGauge(
                label = "Download",
                valueText = report.downloadKbps?.let { formatMbps(it) } ?: "--",
                fraction = 1f,
                rateLine = report.rating(),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Run again", onClick = onRun, modifier = Modifier.weight(1f))
            }
        }
    }

    item {
        SectionCard(eyebrow = "Your connection") {
            Metric("Download", report.downloadKbps?.let { formatMbps(it) } ?: "Not measured")
            Metric("Upload", report.uploadKbps?.let { formatMbps(it) } ?: "Not measured")
            Metric("Ping", report.pingMedianMillis?.let { "%.0f ms".format(it) } ?: "Not measured")
            Metric("Jitter", report.jitterMillis?.let { "%.0f ms".format(it) } ?: "Not measured")
        }
    }

    item { VerdictCard(state.verdict) }

    item {
        SectionCard(eyebrow = "Measurements behind this") {
            for (stage in report.stages) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stageName(stage.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Editorial.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (stage.ok) stageValue(stage) else "Failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (stage.ok) Editorial.Ink else Editorial.Red,
                    )
                }
                stage.detail?.let { detail ->
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Editorial.Muted)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                SpeedTest.dataUseNotice(),
                style = MaterialTheme.typography.bodySmall,
                color = Editorial.Muted,
            )
        }
    }
}

/** The big dial: a three-quarter arc that fills with progress, the rate set inside it. */
@Composable
private fun SpeedGauge(label: String, valueText: String, fraction: Float, rateLine: String?) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "speed-gauge-fraction",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(0.72f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 16.dp.toPx()
                val radius = minOf(size.width, size.height) / 2f - stroke
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                val arcSize = Size(radius * 2, radius * 2)
                val sweepTotal = 260f
                drawArc(
                    color = Editorial.Hairline,
                    startAngle = 140f,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Editorial.Blue,
                    startAngle = 140f,
                    sweepAngle = sweepTotal * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.displaySmall,
                    color = Editorial.Ink,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.InkMid,
                )
            }
        }
        if (rateLine != null) {
            Spacer(Modifier.height(8.dp))
            Text(rateLine, style = MaterialTheme.typography.bodyLarge, color = Editorial.Ink)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkSoft,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = Editorial.Ink,
        )
    }
    Spacer(Modifier.height(8.dp))
}


private fun stageLabel(stage: String): String = when (stage) {
    "ping" -> "Ping"
    "download" -> "Download"
    else -> "Upload"
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

package dev.extranet.netdiag.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.decision.DiagnosisRules
import dev.extranet.netdiag.core.decision.Subjects
import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.measure.DiagnoseRules

/**
 * The Checkup tab: one question, one tap, one answer.
 *
 * The answer is a single full-width card - the state word and one sentence, in the state's colour
 * - because that is the entire thing a person came to see. The path and the measurements sit
 * beneath it, and the raw numbers live behind a Details fold.
 *
 * While the check runs, the same circle becomes a dial: an arc for how much of the checklist is
 * finished, and a head that runs round it while the current step is still working. The two are
 * different facts and both are true - the check knows how far it has got and knows that what it is
 * doing now has not finished - and showing only the first would look hung during the ten seconds
 * it spends listening to the radio.
 */
@Composable
public fun CheckupScreen(
    state: CheckupUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onShareReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Diagnose",
                title = "What exactly is wrong?",
            )
        }

        when (state) {
            CheckupUiState.Idle -> {
                item {
                    SectionCard {
                        CheckRing(
                            state = DiagnosisState.CHECKING,
                            dimmed = true,
                            progress = 0f,
                            spinning = false,
                            word = "?",
                            sub = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Takes about 30 seconds. It checks your network, the internet, " +
                                "and whether data actually moves.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Editorial.InkMid,
                        )
                        Spacer(Modifier.height(14.dp))
                        InkButton("Start checkup", onClick = onRun, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is CheckupUiState.NeedPermission -> {
                item { PermissionNote(state.denied, onOpenSettings) }
            }

            is CheckupUiState.Running -> {
                item {
                    SectionCard {
                        val done = finishedCount(state.steps)
                        CheckRing(
                            state = DiagnosisState.CHECKING,
                            dimmed = false,
                            progress = progressOf(state.steps),
                            spinning = true,
                            word = "Checking",
                            sub = "${state.elapsedSeconds} s · $done of ${state.steps.size}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stateLine(DiagnosisState.CHECKING),
                            style = MaterialTheme.typography.titleMedium,
                            color = Editorial.InkSoft,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        // The checklist, not one changing sentence. A check that takes twenty
                        // seconds and does seven different things has to show that it is doing
                        // them, or the screen looks hung - and showing them afterwards is the
                        // honest answer to "what did it actually test".
                        StepList(state.steps, running = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        LineButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is CheckupUiState.Failed -> {
                item {
                    SectionCard {
                        CheckRing(
                            state = DiagnosisState.UNKNOWN,
                            dimmed = false,
                            progress = 1f,
                            spinning = false,
                            word = "Stuck",
                            sub = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stateLine(DiagnosisState.UNKNOWN),
                            style = MaterialTheme.typography.titleMedium,
                            color = Editorial.InkSoft,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Editorial.InkMid,
                        )
                        Spacer(Modifier.height(14.dp))
                        InkButton("Try again", onClick = onRun, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is CheckupUiState.Done -> answeredItems(state, onRun, onShareReport)
        }
    }
}

/**
 * The dial that answers the question, whichever question is being asked right now.
 *
 * Three states of one element, so the screen looks the same before, during and after: dimmed and
 * still when nothing has been measured, turning while the check works, and a full ring in the
 * answer's colour when it is done. The arc is the checklist's real progress and the spinning head
 * is the current step, so nothing here is decoration pretending to be information.
 *
 * The word in the middle is capped display type: it is the largest text on the screen and it sits
 * inside a circle, which is the worst possible place for text that grows without limit.
 */
@Composable
private fun CheckRing(
    state: DiagnosisState,
    dimmed: Boolean,
    progress: Float,
    spinning: Boolean,
    word: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    val colour = if (dimmed) Editorial.Hairline else stateColor(state)
    val spin by rememberInfiniteTransition(label = "check-ring").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "check-ring-head",
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 10.dp.toPx()
                val radius = minOf(size.width, size.height) / 2f - stroke
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                val arcSize = Size(radius * 2, radius * 2)

                drawArc(
                    color = Editorial.Hairline,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )

                // What is finished. Blue while the check is working, because a filled arc is
                // progress and progress is the accent colour; the answer's own colour takes over
                // once there is an answer to colour it with.
                val done = (progress.coerceIn(0f, 1f)) * 360f
                if (done > 0.5f) {
                    drawArc(
                        color = if (spinning) Editorial.Blue else colour,
                        startAngle = -90f,
                        sweepAngle = done,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }

                // What is happening now: a short bright head running the circle, which is the
                // difference between "working" and "hung" to anyone watching a twenty-second wait.
                if (spinning) {
                    drawArc(
                        color = Editorial.Blue,
                        startAngle = spin,
                        sweepAngle = 84f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke * 0.62f, cap = StrokeCap.Round),
                    )
                    val head = Math.toRadians((spin + 84f).toDouble())
                    drawCircle(
                        color = Editorial.Blue,
                        radius = stroke * 0.85f,
                        center = Offset(
                            size.width / 2f + (radius * kotlin.math.cos(head)).toFloat(),
                            size.height / 2f + (radius * kotlin.math.sin(head)).toFloat(),
                        ),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    word,
                    style = CappedDisplay(MaterialTheme.typography.headlineSmall),
                    color = if (dimmed) Editorial.Muted else Editorial.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (sub != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelMedium,
                        color = Editorial.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** How much of the checklist is behind us: done and skipped both count as finished. */
private fun finishedCount(steps: List<DiagnoseStep>): Int =
    steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }

/** The same thing as a fraction, for the arc. Never reports full until the last step is behind. */
private fun progressOf(steps: List<DiagnoseStep>): Float {
    if (steps.isEmpty()) return 0f
    return finishedCount(steps) / steps.size.toFloat()
}

/** The one line under the dial that says what the state means. */
private fun stateLine(state: DiagnosisState): String = when (state) {
    DiagnosisState.CHECKING -> "Checking your connection"
    DiagnosisState.GOOD -> "Your connection is working well"
    DiagnosisState.FAIR -> "Working, but not at full speed"
    DiagnosisState.SLOW -> "Connected, but very slow"
    DiagnosisState.WEAK -> "Weak signal where you are"
    DiagnosisState.OFFLINE -> "Not connected"
    DiagnosisState.UNKNOWN -> "Couldn't tell"
}

/**
 * The permission state the spec requires to be part of the machine.
 *
 * It names which reading is gated and what the answer will be without it, and the Settings deep
 * link is the only way past a permanent denial - Android will not re-show the dialog after the
 * user has said "never ask again", so an app without the link has no recovery path at all.
 */
@Composable
private fun PermissionNote(denied: List<String>, onOpenSettings: () -> Unit) {
    SectionCard {
        CheckRing(
            state = DiagnosisState.UNKNOWN,
            dimmed = true,
            progress = 0f,
            spinning = false,
            word = "?",
            sub = null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            permissionSentence(denied),
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Your location is only used to read the signal. It never leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkMid,
        )
        Spacer(Modifier.height(14.dp))
        LineButton("Open app settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
    }
}

/** Which permission is missing, in the words of what it gates rather than the API name. */
private fun permissionSentence(denied: List<String>): String = when {
    denied.size > 1 -> "To read the signal, extranet needs the location and phone permissions."
    denied.firstOrNull() == android.Manifest.permission.ACCESS_FINE_LOCATION ->
        "To read the signal, extranet needs the location permission."
    else -> "To read the signal, extranet needs the phone permission."
}

/** The answer, the path, and the measurements behind them. */
private fun LazyListScope.answeredItems(
    state: CheckupUiState.Done,
    onRun: () -> Unit,
    onShareReport: (String) -> Unit,
) {
    val verdict = state.verdict
    val diagnose = state.diagnose

    item {
        SectionCard {
            CheckRing(
                state = verdict.state,
                dimmed = false,
                progress = 1f,
                spinning = false,
                word = verdict.state.label,
                sub = diagnose.confidence.label(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stateLine(verdict.state),
                style = MaterialTheme.typography.titleMedium,
                color = Editorial.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            // The headline verdict, in the seven-situation vocabulary, with the confidence
            // attached to it rather than hidden. Every one of these words is a "likely": the
            // evidence a phone can gather is a handful of probes, and carriers differ.
            Text(
                diagnose.headline,
                style = MaterialTheme.typography.bodyLarge,
                color = Editorial.Ink,
            )
            Spacer(Modifier.height(10.dp))
            ConfidenceTag(diagnose.confidence)
            diagnose.evidence.forEach { line ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "· $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = Editorial.InkMid,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                diagnose.action,
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Blue,
            )
            Spacer(Modifier.height(16.dp))
            InkButton("Run again", onClick = onRun, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LineButton(
                "Share report",
                onClick = { onShareReport(state.report) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    item { StepList(state.steps, running = false, modifier = Modifier.fillMaxWidth()) }

    item { PathDiagram(state.nodes) }

    // A SIM with no data is a real fault that the path cannot show: the path is describing the
    // connection being tested, and on Wi-Fi that connection is genuinely fine. Without this the
    // answer reads "Good" and a phone that cannot make a call is invisible until someone leaves
    // Wi-Fi and finds out. It sits under the path rather than above the answer, because it is
    // not what was asked - the answer above is still the true answer to that question.
    state.findings.firstOrNull { it.subject == Subjects.MOBILE_DATA }
        ?.takeIf { it.assessment == DiagnosisState.OFFLINE }
        ?.let { item { SimNotice(it) } }

    item { DetailsFold(state.findings) }
}

/** The confidence chip, said the same way whether it is the chip or the dial's own line. */
private fun DiagnoseRules.Confidence.label(): String = when (this) {
    DiagnoseRules.Confidence.HIGH -> "Confident"
    DiagnoseRules.Confidence.MEDIUM -> "Fairly confident"
    DiagnoseRules.Confidence.LOW -> "Not sure"
}

/**
 * The SIM's own state, in the same shape as the verdict above it.
 *
 * The words come from the decision layer rather than from here, so this card cannot promise a
 * different thing from what the checkup would have said had Wi-Fi not been masking it.
 */
@Composable
private fun SimNotice(finding: Finding) {
    val verdict = DiagnosisRules().mobileData(finding)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Mobile data")
            Spacer(Modifier.width(8.dp))
            StateBadge(verdict.state)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            verdict.what,
            style = MaterialTheme.typography.bodyLarge,
            color = Editorial.Ink,
        )
        verdict.action?.let { action ->
            Spacer(Modifier.height(6.dp))
            Text(
                action,
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Blue,
            )
        }
    }
}

/** The measurements, folded. The user asked whether the internet works, not for a report. */
@Composable
private fun DetailsFold(findings: List<Finding>) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        androidx.compose.material3.TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (expanded) "Hide details" else "Show details",
                style = MaterialTheme.typography.labelLarge,
                color = Editorial.Blue,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            for (finding in findings) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        subjectLabel(finding.subject),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Editorial.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StateBadge(finding.assessment)
                }
                finding.evidence.firstOrNull()?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Editorial.Muted)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** The finding's subject key, said the way a person would say it. */
private fun subjectLabel(subject: String): String = when (subject) {
    Subjects.SIGNAL -> "Phone signal"
    Subjects.FIRST_HOP -> "Your Wi-Fi or mobile network"
    Subjects.INTERNET_HOP -> "The internet"
    Subjects.THROUGHPUT -> "Data transfer"
    Subjects.MOBILE_DATA -> "Mobile data on your SIM"
    else -> subject
}

/**
 * The confidence chip.
 *
 * Present on every verdict without exception, including the certain-looking ones, because the
 * chip is what stops a "likely" being read as a "definitely". Low confidence is not a failure of
 * the check: it is the honest description of what six probes can establish about a network this
 * app has never seen before.
 */
@Composable
private fun ConfidenceTag(confidence: DiagnoseRules.Confidence) {
    val (label, colour) = when (confidence) {
        DiagnoseRules.Confidence.HIGH -> "Confident" to Editorial.Green
        DiagnoseRules.Confidence.MEDIUM -> "Fairly confident" to Editorial.Amber
        DiagnoseRules.Confidence.LOW -> "Not sure" to Editorial.InkMid
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(colour, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colour,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * The list of what the check did, with a mark per step.
 *
 * Shown while the check runs and again with the result, on purpose: the second showing is what
 * makes "we did not get to that" visible instead of silent, and a step that was skipped is a
 * real fact about the run.
 *
 * The step that is running gets a small turning mark rather than an ellipsis, because an ellipsis
 * says "text continues" and what is actually happening is "this is the one being worked on right
 * now" - the same distinction the spinning head on the dial makes.
 */
@Composable
private fun StepList(
    steps: List<DiagnoseStep>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val done = finishedCount(steps)
    SectionCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What was checked",
                style = MaterialTheme.typography.titleMedium,
                color = Editorial.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$done of ${steps.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Editorial.Muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        for (step in steps) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                StepMark(step.status)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = step.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (step.status) {
                            StepStatus.WAITING -> Editorial.InkMid
                            StepStatus.SKIPPED -> Editorial.Muted
                            else -> Editorial.Ink
                        },
                    )
                    step.note?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = Editorial.InkMid,
                        )
                    }
                    if (running && step.status == StepStatus.RUNNING) {
                        Text(
                            text = "working on it now",
                            style = MaterialTheme.typography.bodySmall,
                            color = Editorial.Blue,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The mark in front of one step.
 *
 * A fixed 22 dp column of punctuation was fine until the reader's font grew and the glyphs
 * started to overlap the step names beside them. The column is now a fixed-size box that centres
 * whatever mark applies, and the running mark is a small ring that turns.
 */
@Composable
private fun StepMark(status: StepStatus) {
    val size = 20.dp
    when (status) {
        StepStatus.DONE -> Box(
            Modifier.size(size).background(Editorial.Green, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MarkGlyph("✓", Editorial.Paper)
        }

        StepStatus.RUNNING -> RunningMark(size)
        StepStatus.SKIPPED -> MarkGlyph("–", Editorial.Muted)
        StepStatus.WAITING -> Box(
            Modifier.size(size).background(Editorial.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MarkGlyph("·", Editorial.InkMid)
        }
    }
}

@Composable
private fun MarkGlyph(glyph: String, colour: Color) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.labelMedium,
        color = colour,
        maxLines = 1,
    )
}

/** The small turning ring in front of the step being worked on. */
@Composable
private fun RunningMark(size: androidx.compose.ui.unit.Dp) {
    val spin by rememberInfiniteTransition(label = "step-mark").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "step-mark-head",
    )
    Canvas(Modifier.size(size)) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = Editorial.Hairline,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = Editorial.Blue,
            startAngle = spin,
            sweepAngle = 110f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** How fast the dial turns. Fast enough to read as working, slow enough not to be a blur. */
private const val SPIN_MILLIS = 800

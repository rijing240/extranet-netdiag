package dev.extranet.netdiag.app

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.decision.Subjects
import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding

/**
 * The Checkup tab: one question, one tap, one answer.
 *
 * The answer is a single full-width status card - the state word and one sentence, in the
 * state's colour - because that is the entire thing a person came to see. The path and the
 * measurements sit beneath it, and the raw numbers live behind a Details fold. The first build
 * opened with two paragraphs about how the test works; a person with slow internet does not want
 * an explanation before an answer.
 */
@Composable
public fun CheckupScreen(
    state: CheckupUiState,
    onRun: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Checkup",
                title = "Is your internet working?",
            )
        }

        when (state) {
            CheckupUiState.Idle -> {
                item {
                    SectionCard {
                        StatusCircle(DiagnosisState.CHECKING, dimmed = true, modifier = Modifier.fillMaxWidth())
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
                        StatusCircle(DiagnosisState.CHECKING, dimmed = false, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        RunningNote(state.step, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        LineButton("Stop waiting", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), enabled = false)
                    }
                }
            }

            is CheckupUiState.Failed -> {
                item {
                    SectionCard {
                        StatusCircle(DiagnosisState.UNKNOWN, dimmed = false, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "The check couldn't finish. Check that you're online and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Editorial.InkSoft,
                        )
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Editorial.Muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        InkButton("Try again", onClick = onRun, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is CheckupUiState.Done -> answeredItems(state, onRun)
        }
    }
}

/**
 * The big status circle: the state word set inside an arc of the state's colour.
 *
 * Dimmed, it is the idle promise - "an answer will go here". Lit, it is the answer. One element
 * carries both, so the screen reads the same before and after a check.
 */
@Composable
private fun StatusCircle(state: DiagnosisState, dimmed: Boolean, modifier: Modifier = Modifier) {
    val color = if (dimmed) Editorial.Hairline else stateColor(state)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.52f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val radius = minOf(size.width, size.height) / 2f - stroke
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (dimmed) "?" else state.label,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (dimmed) Editorial.Muted else Editorial.Ink,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val line = when (state) {
            DiagnosisState.CHECKING -> "Checking your connection"
            DiagnosisState.GOOD -> "Your connection is working well"
            DiagnosisState.FAIR -> "Working, but not at full speed"
            DiagnosisState.SLOW -> "Connected, but very slow"
            DiagnosisState.WEAK -> "Weak signal where you are"
            DiagnosisState.OFFLINE -> "Not connected"
            DiagnosisState.UNKNOWN -> "Couldn't tell"
        }
        Text(line, style = MaterialTheme.typography.titleMedium, color = Editorial.InkSoft)
    }
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
        StatusCircle(DiagnosisState.UNKNOWN, dimmed = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
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
    denied.size > 1 -> "To read the signal, NetDiag needs the location and phone permissions."
    denied.firstOrNull() == android.Manifest.permission.ACCESS_FINE_LOCATION ->
        "To read the signal, NetDiag needs the location permission."
    else -> "To read the signal, NetDiag needs the phone permission."
}

/** The answer, the path, and the measurements behind them. */
private fun LazyListScope.answeredItems(state: CheckupUiState.Done, onRun: () -> Unit) {
    val verdict = state.verdict
    item {
        SectionCard {
            StatusCircle(verdict.state, dimmed = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            // The one-sentence answer under the circle, always.
            Text(
                verdict.what,
                style = MaterialTheme.typography.bodyLarge,
                color = Editorial.InkSoft,
            )
            verdict.action?.let { action ->
                Spacer(Modifier.height(12.dp))
                Text(
                    action,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Editorial.Blue,
                )
            }
            Spacer(Modifier.height(14.dp))
            LineButton("Run again", onClick = onRun, modifier = Modifier.fillMaxWidth())
        }
    }

    item { PathDiagram(state.nodes) }

    item { DetailsFold(state.findings) }
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
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        subjectLabel(finding.subject),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Editorial.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
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
    else -> subject
}



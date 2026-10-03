package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.decision.PathNode
import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Verdict

/**
 * The components every screen answers with: the state word, the one verdict shape, the path.
 *
 * These are the only places a [Verdict] is turned into pixels, which is what keeps the three
 * tabs from drifting apart: Checkup, Speed and Signal cannot disagree about what Slow looks
 * like, and a fourth screen would inherit the same disclosure levels for free.
 */

/** The state word, sentence case, in the colour the language reserves for it. */
@Composable
public fun StateBadge(state: DiagnosisState, modifier: Modifier = Modifier) {
    Text(
        text = state.label,
        style = MaterialTheme.typography.labelLarge,
        color = stateColor(state),
        modifier = modifier,
    )
}

/**
 * The colour a state is allowed to carry.
 *
 * Green is good, amber is workable but worth a nudge, red is a real problem. Fair was plain ink
 * in the first theme, which read as "no opinion"; amber says the connection works and could be
 * better, which is what Fair actually means.
 */
/** Shared with the Checkup status circle, which draws the same state in the same colour. */
@Composable
internal fun stateColor(state: DiagnosisState): Color = when (state) {
    DiagnosisState.GOOD -> Editorial.Green
    DiagnosisState.FAIR -> Editorial.Amber
    DiagnosisState.SLOW, DiagnosisState.WEAK, DiagnosisState.OFFLINE -> Editorial.Red
    DiagnosisState.CHECKING, DiagnosisState.UNKNOWN -> Editorial.InkMid
}

/** The one result shape: what happened, where, what to do, with the numbers folded away. */
@Composable
public fun VerdictCard(verdict: Verdict, modifier: Modifier = Modifier) {
    // Deliberately not keyed to the verdict: the Signal tab produces a fresh verdict every
    // second, and a fold that snapped shut each tick would be impossible to read while sampling.
    var expanded by remember { mutableStateOf(false) }

    SectionCard(modifier) {
        StateBadge(verdict.state)
        Spacer(Modifier.height(8.dp))
        Text(
            verdict.what,
            style = CappedDisplay(MaterialTheme.typography.headlineSmall),
            color = Editorial.Ink,
        )
        // The cause slot is always drawn, including when there is no cause: an Unknown answer
        // that silently dropped this row would leave the user with what happened and no word
        // about why nothing was blamed, which is the one thing the spec insists on saying.
        Spacer(Modifier.height(14.dp))
        Eyebrow("Where")
        Spacer(Modifier.height(2.dp))
        val cause = verdict.where
        Text(
            cause ?: Verdict.NO_CAUSE_COPY,
            style = MaterialTheme.typography.bodyLarge,
            color = if (cause == null) Editorial.InkMid else Editorial.Ink,
        )
        verdict.action?.let { action ->
            Spacer(Modifier.height(14.dp))
            Eyebrow("What to do")
            Spacer(Modifier.height(2.dp))
            Text(action, style = MaterialTheme.typography.bodyLarge, color = Editorial.Ink)
        }
        Spacer(Modifier.height(16.dp))
        ConfidenceLine(verdict.confidence)
        Spacer(Modifier.height(14.dp))
        LineButton(
            text = if (expanded) "Hide details" else "Show details",
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))
            Eyebrow("Measurements behind this")
            Spacer(Modifier.height(6.dp))
            for (line in verdict.evidence) {
                Text(
                    "•  $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = Editorial.InkSoft,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/**
 * The confidence behind the cause, as a bar rather than a percentage.
 *
 * The spec forbids a percentage figure without a written formula and weights, and none exists
 * yet, so the bar shows the strength of the evidence without implying a precision the rules do
 * not have. It is the same number the confidence floor is compared against, shown to the user
 * instead of being hidden behind it.
 */
@Composable
private fun ConfidenceLine(confidence: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("Confidence")
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .background(Editorial.Hairline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(confidence.toFloat().coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(Editorial.Blue),
            )
        }
    }
}

/**
 * The path diagram: this phone, the network in front of it, the internet.
 *
 * One row of nodes rather than a drawing, because three boxes on a phone screen is already a
 * diagram and any more furniture would compete with the verdict above it - but only while the row
 * fits. Three nodes sharing the width of a 360dp screen leaves each of them a hundred-odd dp for
 * a name, a state word and a sentence, which is a layout that survives exactly one font size. So
 * the row is kept when there is room for it and the nodes stack, with the arrow becoming a
 * downwards one, when there is not. The states come from `PathModel`, so Checkup, Hotspot and
 * Wi-Fi diagnosis cannot put the fault in different hops.
 */
@Composable
public fun PathDiagram(nodes: List<PathNode>, modifier: Modifier = Modifier) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    SectionCard(modifier, eyebrow = "The path") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val across = responsiveColumns(maxWidth, nodes.size, minColumn = 104.dp, fontScale = fontScale)
            if (across >= nodes.size) {
                Row(verticalAlignment = Alignment.Top) {
                    nodes.forEachIndexed { index, node ->
                        if (index > 0) {
                            Text(
                                "→",
                                style = MaterialTheme.typography.titleMedium,
                                color = Editorial.Muted,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        PathNodeCell(node, Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    nodes.forEachIndexed { index, node ->
                        Row(verticalAlignment = Alignment.Top) {
                            PathNodeCell(node, Modifier.weight(1f))
                            if (index < nodes.lastIndex) {
                                Text(
                                    "↓",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Editorial.Muted,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One hop: what it is, how it is doing, and what it said. */
@Composable
private fun PathNodeCell(node: PathNode, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            node.name,
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.InkMid,
        )
        Spacer(Modifier.height(4.dp))
        StateBadge(node.state)
        node.detail?.let { detail ->
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = Editorial.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The progress note a running check shows: a small spinner and one line of what it is doing. */
@Composable
public fun RunningNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(16.dp),
            color = Editorial.Blue,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Editorial.InkSoft)
    }
}

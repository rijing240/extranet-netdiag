package dev.extranet.netdiag.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Signal tab: the radar, the verdict, and one honest sentence about direction.
 *
 * The radar is the screen's hero: sixteen measured wedges, a sweep while sampling, a needle when
 * the statistics support a direction. The verdict answers from the radio alone, and the session
 * card keeps the log and the platform's per-field answers - folded, because a normal user's
 * question is "which way should I walk", not "what did the radio say each second".
 */
@Composable
public fun SignalScreen(
    state: SignalUiState,
    liveHeading: Double?,
    tiltOnly: Boolean,
    onSample: () -> Unit,
    onStop: () -> Unit,
    onShareLog: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                SectionHeader(
                    eyebrow = "Signal",
                    title = "Where is the signal better?",
                    subtitle = "Walk slowly with the screen open. The dial fills in as you turn.",
                )
                Spacer(Modifier.height(12.dp))
                ActionCard(state, onSample, onStop, onShareLog)
            }
        }

        when (state) {
            SignalUiState.Idle -> item { SectionCard { IdleNote() } }
            is SignalUiState.NeedPermission -> item {
                SignalPermissionNote(state.denied, onOpenSettings)
            }
            is SignalUiState.Failed -> item { SectionCard { FailedNote(state.message) } }
            is SignalUiState.Live -> sessionItems(state, liveHeading, tiltOnly, onShareLog)
        }
    }
}

@Composable
private fun ActionCard(
    state: SignalUiState,
    onSample: () -> Unit,
    onStop: () -> Unit,
    onShareLog: (String) -> Unit,
) {
    val sampling = state is SignalUiState.Live && !state.finished
    SectionCard {
        if (sampling) {
            LineButton("Stop", onClick = onStop, modifier = Modifier.fillMaxWidth())
        } else {
            InkButton(
                text = if (state is SignalUiState.Live) "Sample again" else "Start sampling",
                onClick = onSample,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IdleNote() {
    Text(
        "Press sample and walk slowly with the phone held flat. The dial records which way you " +
            "were facing whenever the signal was stronger, then points at the best one.",
        style = MaterialTheme.typography.bodyMedium,
        color = Editorial.InkSoft,
    )
}

/**
 * The Signal tab's permission state.
 *
 * This tab is nothing but radio readings, so a denied location permission is not one missing
 * input among several - there is no session without it. The copy says so plainly, and Settings
 * is offered because a permanent denial has no other way back.
 */
@Composable
private fun SignalPermissionNote(denied: List<String>, onOpenSettings: () -> Unit) {
    SectionCard(eyebrow = "permission needed") {
        Text(
            "NetDiag needs the location and phone permissions to read the radio, and this " +
                "screen is the radio.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Without them every second samples nothing: no strength, no noise, no direction. " +
                "Location never leaves the device.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkSoft,
        )
        Spacer(Modifier.height(12.dp))
        LineButton("Open app settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedNote(message: String) {
    Column {
        Text(
            "THE SESSION FAILED",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Editorial.InkSoft)
    }
}

private fun LazyListScope.sessionItems(
    state: SignalUiState.Live,
    liveHeading: Double?,
    tiltOnly: Boolean,
    onShareLog: (String) -> Unit,
) {
    item {
        SectionCard {
            SignalRadar(
                samples = state.samplesSnapshot,
                compass = state.compass,
                liveHeading = liveHeading,
                sampling = !state.finished,
                tiltOnly = tiltOnly,
            )
        }
    }

    item { VerdictCard(state.verdict) }

    item {
        SectionCard(eyebrow = "This session") {
            Text(
                if (state.finished) {
                    "Finished - ${state.seconds} seconds sampled"
                } else {
                    "Sampling - ${state.seconds} seconds so far"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Ink,
            )
            val latest = state.latest
            if (latest != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    RadarStat("Signal", latest.rsrpDbm?.let { "$it dBm" } ?: "-")
                    RadarStat("Quality", latest.rssnrDb?.let { "$it dB" } ?: "-")
                    RadarStat("Level", latest.level?.let { "$it of 4" } ?: "-")
                }
            }
            if (state.csv != null) {
                Spacer(Modifier.height(12.dp))
                LineButton("Share the log", onClick = { state.csv?.let(onShareLog) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RadarStat(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Editorial.InkMid)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Editorial.Ink)
    }
}

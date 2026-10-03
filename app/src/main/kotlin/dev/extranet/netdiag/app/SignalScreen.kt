package dev.extranet.netdiag.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Signal tab: one instrument, one button, one answer.
 *
 * The instrument is on screen from the moment the tab opens, not after the first scan: a phone
 * with a compass shows the radar dial turning with the phone, and a phone without one shows the
 * walking meter, so the user can see the thing is alive before they press anything. The button
 * starts and stops a scan. Below it, the verdict and the session numbers answer the other
 * question - how good is the signal here - and stay folded behind the instrument because the
 * question a person arrived with is "which way, or how far, for a better signal".
 */
@Composable
public fun SignalScreen(
    state: SignalUiState,
    radar: State<RadarEngine.Frame>,
    hasCompass: Boolean,
    onSample: () -> Unit,
    onStop: () -> Unit,
    onShareLog: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRoomMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Signal",
                title = "Where is the signal better?",
                subtitle = if (hasCompass) {
                    "Hold the phone out flat and turn slowly all the way around."
                } else {
                    "Walk slowly. You'll be told when the signal gets better or worse."
                },
            )
        }

        when (state) {
            is SignalUiState.NeedPermission -> item {
                SignalPermissionNote(onOpenSettings)
            }
            is SignalUiState.Failed -> item { SectionCard { FailedNote(state.message) } }
            else -> {
                item {
                    SectionCard {
                        if (hasCompass) SignalRadar(frame = radar) else SignalTrendMeter(frame = radar)
                    }
                }
                item { ActionCard(state, hasCompass, onSample, onStop) }
                // Turning measures which way the signal comes from, which is the right question
                // outdoors and the wrong one indoors: a Wi-Fi access point is a fixed box in a
                // fixed room, and walking toward it changes the answer far more than turning on
                // the spot does. So on Wi-Fi the radar is not wrong, it is just the lesser tool,
                // and the screen points at the better one.
                if (radar.value.kind == RadioStrengthFeed.Kind.WIFI) {
                    item { WalkCloserHint(onOpenRoomMap = onOpenRoomMap) }
                }
                if (state is SignalUiState.Live) sessionItems(state, onShareLog)
            }
        }
    }
}

@Composable
private fun ActionCard(
    state: SignalUiState,
    hasCompass: Boolean,
    onSample: () -> Unit,
    onStop: () -> Unit,
) {
    val scanning = state is SignalUiState.Live && !state.finished
    SectionCard {
        if (scanning) {
            LineButton("Stop", onClick = onStop, modifier = Modifier.fillMaxWidth())
        } else {
            val label = when {
                state is SignalUiState.Live -> if (hasCompass) "Scan again" else "Walk again"
                hasCompass -> "Start scanning"
                else -> "Start walking"
            }
            InkButton(text = label, onClick = onSample, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The Signal tab's permission state.
 *
 * This tab is nothing but radio readings, so a denied location permission is not one missing
 * input among several - there is no scan without it. The copy says so plainly, and Settings is
 * offered because a permanent denial has no other way back.
 */
@Composable
private fun SignalPermissionNote(onOpenSettings: () -> Unit) {
    SectionCard {
        Text(
            "To read the signal, extranet needs the location and phone permissions.",
            style = MaterialTheme.typography.bodyLarge,
            color = Editorial.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Without them there is nothing to measure. Your location is only used to read " +
                "the signal and never leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = Editorial.InkMid,
        )
        Spacer(Modifier.height(14.dp))
        LineButton("Open app settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedNote(message: String) {
    Column {
        Text(
            "The scan couldn't finish",
            style = MaterialTheme.typography.titleMedium,
            color = Editorial.Red,
        )
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Editorial.InkSoft)
    }
}

private fun LazyListScope.sessionItems(
    state: SignalUiState.Live,
    onShareLog: (String) -> Unit,
) {
    item { VerdictCard(state.verdict) }

    item {
        SectionCard(eyebrow = "This scan") {
            Text(
                if (state.finished) {
                    "Finished - ${state.seconds} seconds"
                } else {
                    "Scanning - ${state.seconds} seconds so far"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Ink,
            )
            val latest = state.latest
            if (latest != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("Signal", latest.rsrpDbm?.let { "$it dBm" } ?: "-")
                    Stat("Quality", latest.rssnrDb?.let { "$it dB" } ?: "-")
                    Stat("Level", latest.level?.let { "$it of 4" } ?: "-")
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
private fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Editorial.InkMid)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Editorial.Ink)
    }
}

/**
 * The Wi-Fi hint: turning works, but walking is what actually moves a Wi-Fi reading.
 *
 * Deliberately shown below the dial rather than in front of it. It must not read as an error or
 * as a reason the scan is not working, because the scan is working - it is a note about which
 * instrument answers the user's real question best, offered at the moment they can act on it.
 */
@Composable
private fun WalkCloserHint(onOpenRoomMap: () -> Unit) {
    SectionCard {
        Text(
            text = "On Wi-Fi, walking closer to your router usually matters more than turning.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.Ink,
        )
        Spacer(Modifier.height(8.dp))
        LineButton("Open Room Map", onClick = onOpenRoomMap, modifier = Modifier.fillMaxWidth())
    }
}

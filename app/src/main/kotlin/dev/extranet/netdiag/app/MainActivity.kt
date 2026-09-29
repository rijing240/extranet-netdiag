package dev.extranet.netdiag.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * S6 Presentation, B0 and B1 slices, set in the editorial design language.
 *
 * The wordmark and tab row behave like the reference site's nav: fixed, hairline-separated,
 * mono upper-case labels. Screens are the content below the rule.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EditorialTheme {
                Surface(color = Editorial.Paper) {
                    NetDiagApp()
                }
            }
        }
    }
}

/** The screens built so far, in the order the batches produced them. */
private enum class Screen(val tab: String) {
    CAPABILITY("Probe"),
    WATERFALL("Waterfall"),
    TIMELINE("Timeline"),
}

@Composable
private fun NetDiagApp() {
    var current by remember { mutableStateOf(Screen.CAPABILITY) }

    Column(Modifier.fillMaxSize()) {
        // --- nav: wordmark + tab row, hairline underneath -------------------------------
        Column(Modifier.fillMaxWidth().background(Editorial.Paper)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "extranet",
                    style = MaterialTheme.typography.titleLarge,
                    color = Editorial.Ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "NET·DIAG",
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.Muted,
                )
            }
            Row(Modifier.fillMaxWidth().height(40.dp)) {
                for (screen in Screen.entries) {
                    TabLabel(
                        label = screen.tab,
                        selected = screen == current,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable { current = screen },
                    )
                }
            }
            Hairline(color = Editorial.Ink)
        }

        when (current) {
            Screen.CAPABILITY -> ProbeRoute(Modifier.weight(1f))
            Screen.WATERFALL -> MeasurementRoute(Modifier.weight(1f))
            Screen.TIMELINE -> TimelineRoute(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabLabel(label: String, selected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 10.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Editorial.Ink else Editorial.Muted,
            )
        }
        if (selected) {
            Box(Modifier.width(28.dp).height(2.dp).background(Editorial.Ink))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TimelineRoute(modifier: Modifier = Modifier) {
    val viewModel: TimelineViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    TimelineScreen(
        state = state,
        onRun = { durationMillis -> viewModel.runSession(durationMillis) },
        onShareCsv = { csv ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "B2 radio timeline log")
                putExtra(Intent.EXTRA_TEXT, csv)
            }
            context.startActivity(Intent.createChooser(intent, "Share radio timeline"))
        },
        modifier = modifier,
    )
}

@Composable
private fun ProbeRoute(modifier: Modifier = Modifier) {
    val viewModel: CapabilityProbeViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    CapabilityProbeScreen(
        state = state,
        onRunSync = { viewModel.run(liveSession = false) },
        onRunLive = { viewModel.run(liveSession = true) },
        onShare = {
            val json = (state as? ProbeUiState.Done)?.json.orEmpty()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "B0 capability report")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            context.startActivity(Intent.createChooser(intent, "Share capability report"))
        },
        modifier = modifier,
    )
}

@Composable
private fun MeasurementRoute(modifier: Modifier = Modifier) {
    val viewModel: MeasurementViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    MeasurementScreen(
        state = state,
        onRun = { viewModel.run() },
        onShare = {
            val json = (state as? MeasurementUiState.Done)?.json.orEmpty()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "B1 latency waterfall")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            context.startActivity(Intent.createChooser(intent, "Share waterfall report"))
        },
        modifier = modifier,
    )
}

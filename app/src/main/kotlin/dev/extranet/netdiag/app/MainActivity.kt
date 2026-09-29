package dev.extranet.netdiag.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * S6 Presentation, B0 and B1 slices.
 *
 * Two screens behind a selector because there are exactly two deliverables so far. The latency
 * waterfall as a flame graph, the forecast timeline and the relative capacity index arrive in
 * B11, once there is data worth drawing; what is here is the raw evidence those will be drawn
 * from.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    NetDiagApp()
                }
            }
        }
    }
}

/** The screens built so far, in the order the batches produced them. */
private enum class Screen(val title: String) {
    CAPABILITY("Capability (B0)"),
    WATERFALL("Waterfall (B1)"),
}

@Composable
private fun NetDiagApp() {
    var current by remember { mutableStateOf(Screen.CAPABILITY) }

    Column {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (screen in Screen.entries) {
                if (screen == current) {
                    Button(onClick = { current = screen }) { Text(screen.title) }
                } else {
                    OutlinedButton(onClick = { current = screen }) { Text(screen.title) }
                }
            }
        }

        when (current) {
            Screen.CAPABILITY -> ProbeRoute(Modifier.weight(1f))
            Screen.WATERFALL -> MeasurementRoute(Modifier.weight(1f))
        }
    }
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

package dev.extranet.netdiag.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * S6 Presentation, B0 slice.
 *
 * The only thing on screen is the capability report, because that is B0's entire deliverable.
 * The latency waterfall, forecast timeline and relative capacity index from the plan arrive in
 * B11 once there is data worth drawing.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    ProbeRoute()
                }
            }
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

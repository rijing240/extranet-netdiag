package dev.extranet.netdiag.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * S6 Presentation: the app shell.
 *
 * The shell is what makes this read as an application rather than a document. A page puts its
 * navigation in a header and lets one flat surface run the whole length of the viewport; an app
 * puts a top bar over the current screen, a bar of icon tabs under the thumb, and scrolls its
 * content between them on a tinted canvas. So: edge to edge, a top bar carrying the screen name,
 * a bottom NavigationBar for the three screens, and every screen laid out as cards on bone.
 *
 * The editorial language survives intact inside that frame - palette, type, hairlines, the stitch.
 * What it loses is the page furniture: no wordmark header, no tab row across the top, no hero band.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app is light-only, so the bars are forced to the light style rather than left to
        // detect the system's dark mode - otherwise a dark system theme would put white status
        // bar icons on our bone background.
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )

        setContent {
            EditorialTheme {
                NetDiagApp()
            }
        }
    }
}

/** The screens built so far, in the order the batches produced them. */
private enum class Screen(val label: String, val icon: ImageVector) {
    CAPABILITY("Probe", Icons.Outlined.NetworkCheck),
    WATERFALL("Waterfall", Icons.Outlined.BarChart),
    TIMELINE("Timeline", Icons.Outlined.Timeline),
}

@Composable
private fun NetDiagApp() {
    var current by remember { mutableStateOf(Screen.CAPABILITY) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Editorial.Bone,
        // The bars carry their own insets: TopAppBar pads against the status bar and
        // NavigationBar against the gesture bar, so the content would otherwise be padded twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { NetDiagTopBar(current) },
        bottomBar = { NetDiagBottomBar(current, onSelect = { current = it }) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (current) {
                Screen.CAPABILITY -> ProbeRoute(Modifier.fillMaxSize())
                Screen.WATERFALL -> MeasurementRoute(Modifier.fillMaxSize())
                Screen.TIMELINE -> TimelineRoute(Modifier.fillMaxSize())
            }
        }
    }
}

/** The top bar: brand eyebrow over the screen name, on paper, with the status bar behind it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetDiagTopBar(screen: Screen) {
    TopAppBar(
        title = {
            Column {
                Text(
                    "NET·DIAG",
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.Muted,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    screen.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = Editorial.Ink,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Editorial.Paper,
            titleContentColor = Editorial.Ink,
            navigationIconContentColor = Editorial.Ink,
            actionIconContentColor = Editorial.InkSoft,
        ),
    )
}

/**
 * The bottom navigation bar, with a hairline on top of it.
 *
 * This is the single strongest signal that the surface is an app: tabs under the thumb with an
 * ink pill behind the selected icon, instead of text links across a header.
 */
@Composable
private fun NetDiagBottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline(color = Editorial.Hairline)
        NavigationBar(
            containerColor = Editorial.Paper,
            tonalElevation = 0.dp,
        ) {
            for (screen in Screen.entries) {
                NavigationBarItem(
                    selected = screen == current,
                    onClick = { onSelect(screen) },
                    icon = {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(screen.label, style = MaterialTheme.typography.labelSmall)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Editorial.Paper,
                        selectedTextColor = Editorial.Ink,
                        indicatorColor = Editorial.Ink,
                        unselectedIconColor = Editorial.InkMid,
                        unselectedTextColor = Editorial.InkMid,
                    ),
                )
            }
        }
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

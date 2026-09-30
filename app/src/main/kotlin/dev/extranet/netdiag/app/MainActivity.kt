package dev.extranet.netdiag.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SignalCellularAlt
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

/** The product's three tabs. Hotspot and History are reached from a result, not from here. */
private enum class Screen(val label: String, val icon: ImageVector) {
    CAPABILITY("Checkup", Icons.Outlined.NetworkCheck),
    WATERFALL("Speed", Icons.Outlined.Speed),
    TIMELINE("Signal", Icons.Outlined.Radar),
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
                Screen.CAPABILITY -> CheckupRoute(Modifier.fillMaxSize())
                Screen.WATERFALL -> SpeedRoute(Modifier.fillMaxSize())
                Screen.TIMELINE -> SignalRoute(Modifier.fillMaxSize())
            }
        }
    }
}

/** The top bar: the app name small over the tab's large title, on the canvas colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetDiagTopBar(screen: Screen) {
    TopAppBar(
        title = {
            Column {
                Text(
                    "NetDiag",
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.Blue,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    screen.label,
                    style = MaterialTheme.typography.displaySmall,
                    color = Editorial.Ink,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Editorial.Bone,
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

/** The deep link into this app's Settings page: the only way past a permanent denial. */
private fun appSettingsIntent(context: android.content.Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

/**
 * The runtime permission dialog, shared by both radio-reading routes.
 *
 * One launcher per route rather than a global one, so the result lands in the state machine of
 * the screen that asked. After the dialog the route re-runs; a permanent denial simply produces
 * the NeedPermission state again, whose Settings button is the recovery path.
 */
@Composable
private fun rememberRadioPermissionLauncher(onDone: () -> Unit): androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>> {
    return rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Both answers re-run: granted, the checkup proceeds; denied, the state machine
        // rebuilds NeedPermission with the same names and the screen shows the Settings path.
        onDone()
    }
}

@Composable
private fun SignalRoute(modifier: Modifier = Modifier) {
    val viewModel: SignalViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val requestPermissions = rememberRadioPermissionLauncher { viewModel.start() }

    // The radar's live dot wants a heading stream, not the sampler's once-a-second read. The
    // source is owned by the route and lives exactly as long as the tab is on screen. The value
    // is polled through produceState at radar-frame pace: the sensor delivers far faster than
    // the dial needs, and a volatile read per frame is cheaper than a flow per event.
    val headingSource = remember { LiveHeadingSource(context) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        headingSource.start()
        onDispose { headingSource.stop() }
    }
    val liveHeading by androidx.compose.runtime.produceState<Double?>(initialValue = null, headingSource) {
        while (true) {
            value = headingSource.headingDegrees()
            kotlinx.coroutines.delay(50)
        }
    }

    // The view model publishes the live heading into the session's samples on every tick, so
    // the radar's wedges fill even where the sampler's own compass read comes back empty.
    viewModel.liveHeadingDegrees = liveHeading

    SignalScreen(
        state = state,
        liveHeading = liveHeading,
        tiltOnly = headingSource.isTiltOnly,
        onSample = {
            // The view model's own gate decides whether a dialog or a session is next, so the
            // permission map lives in one place and the route only carries the launcher.
            val denied = MeasurementPermissions.missing(context, MeasurementPermissions.SIGNAL)
            if (denied.isEmpty()) viewModel.start() else requestPermissions.launch(denied.toTypedArray())
        },
        onStop = { viewModel.stop() },
        onShareLog = { csv ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "NetDiag radio log")
                putExtra(Intent.EXTRA_TEXT, csv)
            }
            context.startActivity(Intent.createChooser(intent, "Share the radio log"))
        },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        modifier = modifier,
    )
}

@Composable
private fun CheckupRoute(modifier: Modifier = Modifier) {
    val viewModel: CheckupViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val requestPermissions = rememberRadioPermissionLauncher { viewModel.run() }

    CheckupScreen(
        state = state,
        onRun = {
            val denied = MeasurementPermissions.missing(context, MeasurementPermissions.CHECKUP)
            if (denied.isEmpty()) viewModel.run() else requestPermissions.launch(denied.toTypedArray())
        },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        modifier = modifier,
    )
}

@Composable
private fun SpeedRoute(modifier: Modifier = Modifier) {
    val viewModel: SpeedViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    SpeedScreen(
        state = state,
        onRun = { viewModel.run() },
        onCancel = { viewModel.cancel() },
        modifier = modifier,
    )
}

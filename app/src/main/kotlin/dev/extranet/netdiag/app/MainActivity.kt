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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/**
 * The product's tabs, in the order a person asks the questions.
 *
 * Signal first, because "which way should I face" is the question someone asks while standing
 * still with a phone in their hand; the Room Map next, because "where in here is it better" is
 * the same question with a walk attached; Diagnose after that, because it is the one you reach
 * for when the signal looks fine and the internet does not work; and Speed last, because it is a
 * measurement rather than a search.
 */
private enum class Screen(val label: String, val icon: ImageVector) {
    TIMELINE("Signal", Icons.Outlined.Radar),
    MAP("Room Map", Icons.Outlined.Map),
    CAPABILITY("Diagnose", Icons.Outlined.NetworkCheck),
    WATERFALL("Speed", Icons.Outlined.Speed),
}

@Composable
private fun NetDiagApp() {
    var current by remember { mutableStateOf(Screen.CAPABILITY) }
    var updatesOpen by remember { mutableStateOf(false) }

    // One question at launch: has the project retired this build? The app is drawn first and the
    // answer is allowed to replace it - a withdrawn build stops working, and nothing else does.
    // The check never delays the shell, because an instrument that waits on a network before it
    // can be used is an instrument nobody trusts on a train.
    val withdrawalViewModel: WithdrawalViewModel = viewModel()
    val withdrawalState by withdrawalViewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { withdrawalViewModel.checkOnce() }
    val withdrawn = withdrawalState as? WithdrawalUiState.Withdrawn
    if (withdrawn != null) {
        val withdrawalContext = LocalContext.current
        WithdrawnScreen(
            message = withdrawn.message,
            url = withdrawn.url,
            onOpen = { openInBrowser(withdrawalContext, it) },
        )
        return
    }

    // The update notice is a dialog over whatever screen is showing, and it is only ever opened
    // by a tap on the info action. Opening it does not check anything: the request waits for the
    // button inside it, because an app that phones home when a sheet is opened is an app that
    // phones home.
    if (updatesOpen) UpdatesRoute(onDismiss = { updatesOpen = false })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Editorial.Bone,
        // The bars carry their own insets: TopAppBar pads against the status bar and
        // NavigationBar against the gesture bar, so the content would otherwise be padded twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { NetDiagTopBar(current, onOpenUpdates = { updatesOpen = true }) },
        bottomBar = { NetDiagBottomBar(current, onSelect = { current = it }) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (current) {
                Screen.CAPABILITY -> CheckupRoute(Modifier.fillMaxSize())
                Screen.WATERFALL -> SpeedRoute(Modifier.fillMaxSize())
                Screen.TIMELINE -> SignalRoute(
                    modifier = Modifier.fillMaxSize(),
                    onOpenRoomMap = { current = Screen.MAP },
                )
                Screen.MAP -> RoomMapRoute(
                    modifier = Modifier.fillMaxSize(),
                    onOpenSignal = { current = Screen.TIMELINE },
                )
            }
        }
    }
}

/** The top bar: the app name small over the tab's large title, on the canvas colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetDiagTopBar(screen: Screen, onOpenUpdates: () -> Unit) {
    TopAppBar(
        actions = {
            // The way to the version and the update check. One icon, no badge, no dot: there is
            // nothing to tell the user until they ask, because nothing is checked until they ask.
            IconButton(onClick = onOpenUpdates) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "Version and updates",
                    tint = Editorial.InkSoft,
                )
            }
        },
        title = {
            Column {
                Text(
                    "extranet",
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.Blue,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    screen.label,
                    style = CappedDisplay(MaterialTheme.typography.displaySmall),
                    color = Editorial.Ink,
                    maxLines = 1,
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
private fun rememberRadioPermissionLauncher(onDone: () -> Unit): androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, kotlin.collections.Map<String, Boolean>> {
    return rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Both answers re-run: granted, the checkup proceeds; denied, the state machine
        // rebuilds NeedPermission with the same names and the screen shows the Settings path.
        onDone()
    }
}

@Composable
private fun SignalRoute(modifier: Modifier = Modifier, onOpenRoomMap: () -> Unit) {
    val viewModel: SignalViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val requestPermissions = rememberRadioPermissionLauncher { viewModel.start() }

    // One engine per visit to the tab. It owns the compass, the signal feed and the step counter,
    // and is ticked once per drawn frame, so the dial turns as smoothly as the phone does. The
    // frame goes down as State rather than as a value: the radar reads it while drawing, so a
    // sixty-a-second heading redraws the dial without recomposing the screen around it.
    val engine = remember { RadarEngine(context) }
    androidx.compose.runtime.DisposableEffect(engine) {
        engine.startListening()
        onDispose { engine.stopListening() }
    }
    val radarFrame = remember { mutableStateOf(engine.tick()) }

    // A scan only learns while the session is running; the dial turns the rest of the time.
    val scanning = (state as? SignalUiState.Live)?.finished == false
    androidx.compose.runtime.LaunchedEffect(scanning) { engine.setRecording(scanning) }

    androidx.compose.runtime.LaunchedEffect(engine) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                val frame = engine.tick()
                radarFrame.value = frame
                // The session log records the same heading the dial shows.
                viewModel.liveHeadingDegrees = frame.headingDegrees
            }
        }
    }

    SignalScreen(
        state = state,
        radar = radarFrame,
        hasCompass = engine.hasCompass,
        onSample = {
            // The view model's own gate decides whether a dialog or a session is next, so the
            // permission map lives in one place and the route only carries the launcher.
            val denied = MeasurementPermissions.missing(context, MeasurementPermissions.SIGNAL)
            if (denied.isEmpty()) viewModel.start() else requestPermissions.launch(denied.toTypedArray())
        },
        onStop = { viewModel.stop() },
        onOpenRoomMap = onOpenRoomMap,
        onShareLog = { csv ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "extranet radio log")
                putExtra(Intent.EXTRA_TEXT, csv)
            }
            context.startActivity(Intent.createChooser(intent, "Share the radio log"))
        },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        modifier = modifier,
    )
}

@Composable
private fun RoomMapRoute(modifier: Modifier = Modifier, onOpenSignal: () -> Unit) {
    val viewModel: RoomMapViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val requestPermissions = rememberRadioPermissionLauncher { viewModel.start() }

    // Same shape as the Signal tab: one engine per visit, ticked once per drawn frame, with the
    // frame handed down as State so the trail can grow without recomposing the screen around it.
    val engine = remember { RoomMapEngine(context) }
    androidx.compose.runtime.DisposableEffect(engine) {
        engine.startListening()
        onDispose { engine.stopListening() }
    }
    val mapFrame = remember { mutableStateOf(engine.tick()) }

    // The trail only learns while the walk is running; the sensors run the whole time the tab is
    // open so the arrow is already pointing the right way when the user taps Start.
    val walking = (state as? RoomMapUiState.Live)?.finished == false
    androidx.compose.runtime.LaunchedEffect(walking) { engine.setRecording(walking) }

    androidx.compose.runtime.LaunchedEffect(engine) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { mapFrame.value = engine.tick() }
        }
    }

    RoomMapScreen(
        state = state,
        map = mapFrame,
        canMap = engine.canMap,
        onStart = {
            val denied = MeasurementPermissions.missing(context, MeasurementPermissions.SIGNAL)
            if (denied.isEmpty()) viewModel.start() else requestPermissions.launch(denied.toTypedArray())
        },
        onStop = { viewModel.stop() },
        onReset = { viewModel.reset() },
        onShare = { summary ->
            val frame = mapFrame.value
            val file = RoomMapPicture.write(
                context = context,
                snapshot = frame.map,
                startHeadingDegrees = frame.startHeadingDegrees,
                nowX = frame.xMeters,
                nowY = frame.yMeters,
                headingDegrees = frame.headingDegrees,
                summary = summary,
            )
            // The picture when there is one to send, and the words on their own when there is
            // not: a share sheet that offers nothing is worse than one that offers text.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (file != null) "image/png" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "extranet room map")
                putExtra(Intent.EXTRA_TEXT, summary)
                if (file != null) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share room map"))
        },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        onOpenSignal = onOpenSignal,
        // The screen owns the size, the engine owns the fit. Told on every layout rather than
        // assumed, because the same map is drawn at whatever size this phone's panel gives it.
        onViewport = { width, height, padding -> engine.setViewport(width, height, padding) },
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
        onCancel = { viewModel.cancel() },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        onShareReport = { report ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "extranet report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(intent, "Share report"))
        },
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

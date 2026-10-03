package dev.extranet.netdiag.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.extranet.netdiag.measure.GitHubReleasesSource
import dev.extranet.netdiag.measure.ReleasedVersion
import dev.extranet.netdiag.measure.UpdateOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where this project publishes its builds. One constant, because it is the whole address of the
 * update question and it should be readable in one place.
 */
/** Where this project publishes its builds. Read by the manual check and the launch check alike. */
internal const val RELEASES_OWNER: String = "rijing240"
internal const val RELEASES_REPOSITORY: String = "extranet-netdiag"

/** What the update notice is showing. */
public sealed interface UpdateUiState {

    /** Nothing has been asked yet. The state the app is in unless somebody taps. */
    public object Idle : UpdateUiState

    /** The one request is in flight. */
    public data class Checking(public val current: String) : UpdateUiState

    /** An answer arrived, of whichever kind. */
    public data class Answered(public val outcome: UpdateOutcome) : UpdateUiState
}

/**
 * The update notice's state machine.
 *
 * It holds one job and refuses to start another while one is running, so a tapped-twice button
 * cannot put two requests on the network. Nothing here is scheduled, repeated, or triggered by
 * anything except [check] being called.
 */
public class UpdatesViewModel : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    public val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var running: Job? = null

    /** Asks GitHub once, off the main thread, and publishes whatever the answer is. */
    public fun check(current: String) {
        if (running?.isActive == true) return
        _state.value = UpdateUiState.Checking(current)
        running = viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                GitHubReleasesSource(RELEASES_OWNER, RELEASES_REPOSITORY).check(current)
            }
            _state.value = UpdateUiState.Answered(outcome)
        }
    }

    /** Puts the notice back to rest; the sheet is being closed. */
    public fun reset() {
        running?.cancel()
        _state.value = UpdateUiState.Idle
    }
}

/**
 * The version actually installed on this phone, read from the package manager.
 *
 * Deliberately not a compiled-in constant: the honest question is "is the thing on this phone
 * out of date", and the thing on this phone is whatever the package manager says. A build
 * constant would answer a question about the source tree instead, and would be the same wrong
 * answer after an install that did not replace the app.
 */
public fun installedVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

/** The update notice, wired to a view model and to the system's browser. */
@Composable
public fun UpdatesRoute(onDismiss: () -> Unit) {
    val updates: UpdatesViewModel = viewModel()
    val state by updates.state.collectAsState()
    val context = LocalContext.current
    val installed = installedVersionName(context)

    UpdatesDialog(
        installedVersion = installed,
        state = state,
        onCheck = { updates.check(installed) },
        onOpen = { url -> openInBrowser(context, url) },
        onDismiss = {
            updates.reset()
            onDismiss()
        },
    )
}

/**
 * Hand a release page to whatever the user browses with.
 *
 * Shared with the withdrawal screen, which opens the project's current build for the same reason:
 * the app can put a link in front of a person, and only the person can follow it.
 */
internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * What the update notice says, at every state it can be in.
 *
 * Three sentences carry the honesty of the whole feature, and they are the same three every time
 * it is opened: nothing is checked unless the user asks, the app cannot install anything by
 * itself, and a failed check costs the user nothing. An update notice that hides any of those
 * turns a courtesy into a worry.
 */
@Composable
public fun UpdatesDialog(
    installedVersion: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = (state as? UpdateUiState.Answered)?.outcome as? UpdateOutcome.Available
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Editorial.CardShape,
        containerColor = Editorial.Paper,
        titleContentColor = Editorial.Ink,
        textContentColor = Editorial.InkSoft,
        title = {
            Column {
                Text(
                    "extranet",
                    style = MaterialTheme.typography.labelMedium,
                    color = Editorial.Blue,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Version $installedVersion",
                    style = CappedDisplay(MaterialTheme.typography.headlineSmall),
                    color = Editorial.Ink,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (state) {
                    is UpdateUiState.Checking -> Notice("Asking GitHub what is published...")
                    is UpdateUiState.Answered -> when (val outcome = state.outcome) {
                        is UpdateOutcome.UpToDate -> Notice(
                            "This is the newest published build. Nothing to do.",
                        )
                        is UpdateOutcome.Available -> {
                            val release = outcome.newest
                            Notice(
                                if (release.testBuild) {
                                    "Version ${release.tag} is published as a test build."
                                } else {
                                    "Version ${release.tag} is available."
                                },
                            )
                            val asset = release.apk
                            if (asset != null) {
                                Spacer(Modifier.height(6.dp))
                                MonoMeta(
                                    buildString {
                                        append(asset.name)
                                        val size = asset.bytes
                                        if (size != null && size > 0) {
                                            append("  ·  ")
                                            append("%.1f MB".format(size / 1_048_576.0))
                                        }
                                    },
                                    color = Editorial.InkMid,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Notice(
                                "Android will ask you to confirm the install; no app can replace " +
                                    "itself silently.",
                            )
                        }
                        is UpdateOutcome.Unavailable -> {
                            Notice("Could not check: ${outcome.reason}.")
                            Spacer(Modifier.height(6.dp))
                            Notice("The app is unaffected - it works offline and nothing was changed.")
                        }
                    }
                    is UpdateUiState.Idle -> Notice(
                        "When the app opens it looks once - two questions to GitHub: is this " +
                            "build still wanted, and is a newer one published. Nothing is sent " +
                            "about you or this phone, and nothing is asked again until you open " +
                            "the app again.",
                    )
                }
            }
        },
        confirmButton = {
            val url = available?.let { it.newest.pageUrl ?: it.newest.apk?.downloadUrl }
            if (url != null) {
                InkButton(text = "Open the download page", onClick = { onOpen(url) })
            } else {
                InkButton(
                    text = "Check for updates",
                    onClick = onCheck,
                    enabled = state !is UpdateUiState.Checking,
                )
            }
        },
        dismissButton = {
            LineButton(text = "Close", onClick = onDismiss)
        },
    )
}

/**
 * The launch notice: a newer build is published, here it is, and here is what to do about it.
 *
 * A banner rather than a dialog, on purpose. A dialog on every launch is something people learn to
 * dismiss without reading, which makes it worse than useless; a banner sits above the screen,
 * costs nothing to ignore, and is still there next time. "Not now" forgets **this release** and
 * not the question, so the next published build asks again — see [UpdatePrompt].
 *
 * Nothing is claimed here either. The app says the build exists, and the button opens GitHub in
 * the browser; Android still asks the person to confirm the install, which is the platform's
 * decision and not one this app can talk its way around.
 */
@Composable
public fun UpdateBanner(
    release: ReleasedVersion,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = release.pageUrl ?: release.apk?.downloadUrl
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Editorial.Paper)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            if (release.testBuild) "Update available · test build" else "Update available",
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.Blue,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Version ${release.tag} is published. Android will ask you to confirm the install.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.Ink,
        )
        val asset = release.apk
        if (asset != null) {
            Spacer(Modifier.height(4.dp))
            MonoMeta(
                buildString {
                    append(asset.name)
                    val size = asset.bytes
                    if (size != null && size > 0) {
                        append("  ·  ")
                        append("%.1f MB".format(size / 1_048_576.0))
                    }
                },
                color = Editorial.InkMid,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            if (url != null) {
                InkButton(text = "Get it", onClick = { onOpen(url) })
                Spacer(Modifier.width(10.dp))
            }
            LineButton(text = "Not now", onClick = onDismiss)
        }
    }
    Hairline(color = Editorial.Hairline)
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Editorial.InkSoft,
    )
}

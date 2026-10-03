package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.measure.Withdrawal
import dev.extranet.netdiag.measure.WithdrawalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the project has retired this build, read once when the app opens.
 *
 * The one thing the app does without being asked, and the reason it is allowed to: somebody who
 * publishes the app has no other way to stop a copy already in the field. Android will not let one
 * app close another, and the only other lever is shipping a replacement — which needs the owner's
 * action, and does not help if the thing being replaced is giving bad answers.
 *
 * So the shape of it matters more than the existence of it:
 *
 * - **The app is usable while the answer is on its way.** A network round trip is not allowed to
 *   delay an instrument. The question is asked in the background and only ever *replaces* the
 *   screens when the answer is `off`; it never covers them.
 * - **It is asked once per launch and never retried.** The switch is a decision, not a poll.
 *   Re-reading it on every resume would let one phone sit there asking the question all day.
 * - **Only `off` withdraws a build.** Anything else — no network, a timeout, a 404, a portal's
 *   HTML, a typo — runs the app. See [Withdrawal] for why that direction is the only safe one.
 */
public sealed interface WithdrawalUiState {

    /** Running, or running for now. The two are deliberately the same thing to the app. */
    public object Running : WithdrawalUiState

    /** This build was withdrawn. Nothing else renders while this is the state. */
    public data class Withdrawn(
        public val message: String?,
        public val url: String?,
    ) : WithdrawalUiState
}

/** The switch, asked once at launch and answered into [state]. */
public class WithdrawalViewModel : ViewModel() {

    private val _state = MutableStateFlow<WithdrawalUiState>(WithdrawalUiState.Running)
    public val state: StateFlow<WithdrawalUiState> = _state.asStateFlow()

    private var asked = false

    /**
     * Asks once, off the main thread, and publishes whatever the answer is.
     *
     * Repeated calls after the first are ignored rather than queued: this is not a button, it is
     * a launch check, and a recomposition must not turn it into a poll.
     */
    public fun checkOnce() {
        if (asked) return
        asked = true
        viewModelScope.launch {
            val answer = withContext(Dispatchers.IO) { WithdrawalSource(WithdrawalSource.DEFAULT_URL).read() }
            _state.value = when (answer) {
                is Withdrawal.Withdrawn -> WithdrawalUiState.Withdrawn(answer.message, answer.url)
                // Running, and could-not-tell, are the same answer to this app.
                is Withdrawal.Running -> WithdrawalUiState.Running
            }
        }
    }
}

/**
 * What a person is told when the build they are holding has been retired.
 *
 * Three things are said on purpose. The app says *which* build is retired, so somebody with two
 * copies installed can tell them apart. It says *nothing was changed and nothing was uploaded*,
 * because a stopped app is the moment somebody wonders what it did with their data. And it says
 * what to do next, with the link the project put in the switch file — opened only when the
 * person taps it, and only ever because the address passed an `https` check on the way here.
 */
@Composable
public fun WithdrawnScreen(
    message: String?,
    url: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val installed = installedVersionName(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Editorial.Bone)
            // The shell is not drawing, so nothing else is applying the status and gesture bars.
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            "extranet",
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.Blue,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This build has been withdrawn",
            style = CappedDisplay(MaterialTheme.typography.displaySmall),
            color = Editorial.Ink,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            message ?: "The project has retired this version of NetDiag. Install the current " +
                "build to carry on measuring.",
            style = MaterialTheme.typography.bodyLarge,
            color = Editorial.InkSoft,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Nothing on this phone was changed and no measurement was uploaded. The app asked " +
                "one question when it opened, and stopped when the answer was no.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkMid,
        )
        Spacer(Modifier.height(12.dp))
        MonoMeta("installed build: $installed", color = Editorial.InkMid)

        Spacer(Modifier.height(24.dp))
        if (url != null) {
            InkButton(text = "Get the current build", onClick = { onOpen(url) })
        } else {
            // No link means the switch file gave none we trust, which is the app's own doing, not
            // the user's. Saying so beats a button that silently does nothing.
            Text(
                "This build's switch file named no download page, so there is nowhere to send " +
                    "you from here. The project's release page is in the README.",
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.InkMid,
            )
        }
        Spacer(Modifier.height(8.dp))
        Hairline(color = Editorial.Hairline)
        Spacer(Modifier.height(8.dp))
        Text(
            "You can uninstall this app at any time from Android's settings. Android does not " +
                "let one app close another, so stopping a copy is the project's decision, made " +
                "in a file, and this screen is all it can do about it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkMid,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
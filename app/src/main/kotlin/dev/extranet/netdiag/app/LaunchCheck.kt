package dev.extranet.netdiag.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.measure.GitHubReleasesSource
import dev.extranet.netdiag.measure.ReleasedVersion
import dev.extranet.netdiag.measure.UpdateOutcome
import dev.extranet.netdiag.measure.Withdrawal
import dev.extranet.netdiag.measure.WithdrawalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one pass at launch found out.
 *
 * Two questions, because they are the same question asked of the same place at the same moment:
 * *is this build still wanted*, and *is there something newer to have instead*. Both are answered
 * from GitHub, both happen once, and both are reported as they arrive rather than as a blocker —
 * the app is fully usable while this is in flight and while any part of it is missing.
 */
public data class LaunchReport(
    /** Set when the project has retired this build. */
    val retired: Withdrawal.Withdrawn? = null,
    /** The newest published release, when it is newer than what is installed. */
    val newest: ReleasedVersion? = null,
) {
    val isRetired: Boolean get() = retired != null
}

/**
 * The one pass the app makes without being asked.
 *
 * It replaces what used to be two separate behaviours with one question asked once, because there
 * is no sense in a phone making two journeys at a moment when it has already agreed to make one:
 * the retirement switch says whether this build may run, and the release list says whether there
 * is a better one. A withdrawn build needs the second answer most of all — it has just refused to
 * measure anything, so leaving its owner with no way forward would be a dead end rather than a
 * withdrawal.
 *
 * Deliberately absent: retrying, polling, checking on resume, and any failure that reaches the
 * user as an error. A phone that cannot answer either question gets an app that works and says
 * nothing, which is the correct outcome for an instrument.
 */
public class LaunchCheckViewModel : ViewModel() {

    private val _report = MutableStateFlow(LaunchReport())
    public val report: StateFlow<LaunchReport> = _report.asStateFlow()

    private var asked = false

    /**
     * Asks once, off the main thread.
     *
     * [context] is used for one thing — reading which version is actually installed, so the
     * comparison is about this phone rather than about whichever build compiled the code — and is
     * not kept. Repeated calls are ignored, because a recomposition must not turn a launch check
     * into a poll.
     */
    public fun checkOnce(context: Context) {
        if (asked) return
        asked = true
        val installed = installedVersionName(context)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { look(installed) }
            _report.value = found
        }
    }

    private fun look(installedVersion: String): LaunchReport {
        val retired = WithdrawalSource(WithdrawalSource.DEFAULT_URL).read() as? Withdrawal.Withdrawn
        val newest = (GitHubReleasesSource(RELEASES_OWNER, RELEASES_REPOSITORY)
            .check(installedVersion) as? UpdateOutcome.Available)?.newest
        return LaunchReport(retired = retired, newest = newest)
    }
}

/**
 * Which release the user has already turned down.
 *
 * One string in one preferences file, because that is the entire memory this feature needs: a
 * dismissal belongs to a release, so it goes stale by itself when a newer one is published and
 * never needs clearing. It is deliberately not a "hide updates" switch — somebody who wants the
 * app to stop mentioning builds has the honest way to say so, which is not to install it.
 */
internal object UpdatePromptMemory {

    private const val PREFERENCES = "extranet-update-prompt"
    private const val DISMISSED_RELEASE = "dismissed-release"

    fun dismissedRelease(context: Context): String? =
        preferences(context).getString(DISMISSED_RELEASE, null)

    fun dismiss(context: Context, releaseTag: String) {
        preferences(context).edit().putString(DISMISSED_RELEASE, releaseTag).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
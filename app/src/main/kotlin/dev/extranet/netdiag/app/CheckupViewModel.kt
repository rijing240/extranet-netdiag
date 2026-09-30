package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.core.decision.DiagnosisRules
import dev.extranet.netdiag.core.decision.PathModel
import dev.extranet.netdiag.core.decision.PathNode
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.verdict.Verdict
import dev.extranet.netdiag.measure.Observations
import dev.extranet.netdiag.measure.RadioTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Checkup screen's state machine. */
public sealed interface CheckupUiState {

    /** Nothing measured yet in this process. */
    public data object Idle : CheckupUiState

    /** A reading the check needs has not been granted; [denied] names the ones missing. */
    public data class NeedPermission(public val denied: List<String>) : CheckupUiState

    /** The check is running; [step] is the phase the user is waiting on. */
    public data class Running(public val step: String) : CheckupUiState

    /** The check finished and the verdict is ready. */
    public data class Done(
        public val verdict: Verdict,
        public val nodes: List<PathNode>,
        public val findings: List<Finding>,
        public val summary: RadioTimeline.FieldSummary,
    ) : CheckupUiState

    /** The check itself failed; a missing measurement is never this state. */
    public data class Failed(public val message: String) : CheckupUiState
}

/**
 * One Checkup: radio for ten seconds, then the two hops, then payload.
 *
 * The three measurements are run in the order they are weighted, and the step text changes as
 * each one starts, because the whole check takes around fifteen seconds and a screen that sat
 * still for that long would read as broken. Everything after the radio sampling goes through
 * `IO`: the probes open sockets and the vendor's TLS endpoint can take seconds to answer.
 *
 * The view model owns the sequence and nothing else. What the numbers mean is decided by
 * `Observations` and `DiagnosisRules`, both of which are pure and tested without a phone, so a
 * wrong verdict is a bug in a unit test's reach rather than in this file.
 *
 * The permission check is deliberately synchronous with `run()` rather than a separate
 * permission screen: the user asked a question, and the app's first response should be the
 * system's own dialog - not a wall of explanation in front of it.
 */
public class CheckupViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<CheckupUiState>(CheckupUiState.Idle)

    /** Current state, observed by the Checkup screen. */
    public val state: StateFlow<CheckupUiState> = mutableState.asStateFlow()

    private val rules = DiagnosisRules()

    /** Runs one checkup; a second call while one is running is ignored. */
    public fun run() {
        if (mutableState.value is CheckupUiState.Running) return

        val context = getApplication<Application>()
        // The gate runs before anything else: a user who has denied location would otherwise sit
        // through the whole check and receive a radio finding of Unknown with advice to retry,
        // which no amount of retrying can fix.
        val denied = MeasurementPermissions.missing(context, MeasurementPermissions.CHECKUP)
        if (denied.isNotEmpty()) {
            mutableState.value = CheckupUiState.NeedPermission(denied)
            return
        }

        viewModelScope.launch {
            val session = TimelineSession(context)
            try {
                mutableState.value = CheckupUiState.Running("Sampling the radio for ${RADIO_SECONDS} s")
                // Registration touches telephony and needs the main thread; the sampling itself
                // does not, and reading the compass sensor blocks briefly each second, so it is
                // kept off the thread drawing the progress note.
                withContext(Dispatchers.Main) { session.start() }
                val timeline = withContext(Dispatchers.Default) { session.sample(RADIO_MILLIS) }

                mutableState.value = CheckupUiState.Running("Asking the first hop and the internet")
                val hops = withContext(Dispatchers.IO) { session.twoHopVerdict() }

                mutableState.value = CheckupUiState.Running("Moving 1 MB to see whether data flows")
                val throughput = withContext(Dispatchers.IO) { session.throughputCheck() }

                val findings = Observations.checkup(timeline.snapshot(), hops, throughput)
                mutableState.value = CheckupUiState.Done(
                    verdict = rules.checkup(findings),
                    nodes = PathModel.of(findings),
                    findings = findings,
                    summary = timeline.summary(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = CheckupUiState.Failed(
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
                )
            } finally {
                withContext(Dispatchers.Main) { session.stop() }
            }
        }
    }

    private companion object {
        /**
         * Ten seconds of radio sampling: enough for a median over ten readings, which is twice
         * the minimum the inference layer will call a measurement, and short enough that the
         * whole checkup stays inside the time a user will wait for an answer.
         */
        const val RADIO_SECONDS: Int = 10
        const val RADIO_MILLIS: Long = 10_000L
    }
}

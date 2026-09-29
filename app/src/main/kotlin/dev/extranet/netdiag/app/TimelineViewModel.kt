package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.measure.MiniThroughputProbe
import dev.extranet.netdiag.measure.RadioTimeline
import dev.extranet.netdiag.measure.SignalCompass
import dev.extranet.netdiag.measure.TwoHopProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The timeline screen's state machine, one value at a time. */
public sealed interface TimelineUiState {

    /** Nothing sampled yet in this process. */
    public data object Idle : TimelineUiState

    /** A session is sampling. */
    public data class Sampling(public val secondsElapsed: Int) : TimelineUiState

    /** A session finished; the log, verdicts and CSV are ready. */
    public data class Done(
        public val timeline: RadioTimeline,
        public val csv: String,
        public val csvPath: String?,
        public val compass: SignalCompass.Verdict,
        public val summary: RadioTimeline.FieldSummary,
        public val blame: TwoHopProbe.Verdict?,
        public val throughput: MiniThroughputProbe.Result?,
    ) : TimelineUiState

    /** The session itself failed; per-sample gaps are never this state. */
    public data class Failed(public val message: String) : TimelineUiState
}

/**
 * Holds the sampling session across configuration changes.
 *
 * The session object outlives rotations on purpose: a black-box recorder that restarts its log
 * when the phone rotates is not a recorder. After sampling completes, the two one-shot probes
 * (who-is-to-blame, reality check) run on a worker thread and patch into the state when they
 * answer, so the log appears immediately and the network probes never block the screen.
 */
public class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<TimelineUiState>(TimelineUiState.Idle)

    /** Current state, observed by the timeline screen. */
    public val state: StateFlow<TimelineUiState> = mutableState.asStateFlow()

    private var session: TimelineSession? = null

    /** Starts a session, samples for [durationMillis], then runs the one-shot probes. */
    public fun runSession(durationMillis: Long) {
        if (mutableState.value is TimelineUiState.Sampling) return

        val appContext = getApplication<Application>()
        val activeSession = TimelineSession(appContext).also { session = it }

        viewModelScope.launch {
            mutableState.value = TimelineUiState.Sampling(0)
            try {
                val registered = withContext(Dispatchers.Main) { activeSession.start() }
                if (!registered) {
                    // Not fatal: the log records gaps, and the harness below still runs.
                    mutableState.value = TimelineUiState.Sampling(0)
                }

                val timeline = activeSession.sample(durationMillis) { /* surfaced via samples */ }
                val csv = activeSession.exportCsv()

                mutableState.value = TimelineUiState.Done(
                    timeline = timeline,
                    csv = csv,
                    csvPath = writeCsv(activeSession),
                    compass = activeSession.compassVerdict(),
                    summary = timeline.summary(),
                    blame = null,
                    throughput = null,
                )

                // One-shot probes, off the main thread, patched in as they answer.
                val blame = runCatching { withContext(Dispatchers.IO) { activeSession.twoHopVerdict() } }.getOrNull()
                patchState { it.copy(blame = blame) }

                val throughput = runCatching { withContext(Dispatchers.IO) { activeSession.throughputCheck() } }.getOrNull()
                patchState { it.copy(throughput = throughput) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = TimelineUiState.Failed(
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
                )
            } finally {
                withContext(Dispatchers.Main) { activeSession.stop() }
                session = null
            }
        }
    }

    private fun patchState(patch: (TimelineUiState.Done) -> TimelineUiState.Done) {
        val current = mutableState.value
        if (current is TimelineUiState.Done) mutableState.value = patch(current)
    }

    private fun writeCsv(activeSession: TimelineSession): String? = runCatching {
        ReportFiles.write(getApplication(), "radio-timeline.csv", activeSession.exportCsv()).path
    }.getOrNull()
}

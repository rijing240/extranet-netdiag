package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.core.decision.DiagnosisRules
import dev.extranet.netdiag.core.verdict.Verdict
import dev.extranet.netdiag.measure.Observations
import dev.extranet.netdiag.measure.RadioSample
import dev.extranet.netdiag.measure.RadioTimeline
import dev.extranet.netdiag.measure.SignalCompass
import dev.extranet.netdiag.measure.SignalTrend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Signal screen's state machine. */
public sealed interface SignalUiState {

    /** Nothing sampled yet in this process. */
    public data object Idle : SignalUiState

    /** The radio is permission-gated and the gate is closed; [denied] names what is missing. */
    public data class NeedPermission(public val denied: List<String>) : SignalUiState

    /** A session's current reading; [finished] separates "sampling now" from "the last session". */
    public data class Live(
        public val seconds: Int,
        public val finished: Boolean,
        public val verdict: Verdict,
        public val compass: SignalCompass.Verdict,
        public val latest: RadioSample?,
        public val samplesSnapshot: List<RadioSample>,
        public val csv: String?,
        /** The warmer/colder trend, or null until the window fills. */
        public val trend: SignalTrend.Reading?,
    ) : SignalUiState

    /** The session itself failed. */
    public data class Failed(public val message: String) : SignalUiState
}

/**
 * The live Signal session: one sample a second, redrawn every second.
 *
 * The timeline is appended by the sampler while this coroutine reads it, so the screen can show
 * a reading as it arrives rather than only when the session ends - which matters here, because
 * the whole point of the tab is watching the number change as the user walks. The verdict and
 * the compass are recomputed from the same snapshot on every tick: both are pure functions over
 * samples, so there is no second copy of the truth to keep in step.
 *
 * The sampler runs off the main thread. Reading the compass briefly blocks once a second, and
 * that is not something to do on the thread drawing the gauges.
 */
public class SignalViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<SignalUiState>(SignalUiState.Idle)

    /** Current state, observed by the Signal screen. */
    public val state: StateFlow<SignalUiState> = mutableState.asStateFlow()

    private val rules = DiagnosisRules()
    private var session: TimelineSession? = null
    private var sessionJob: Job? = null
    private val trend = SignalTrend()

    /**
     * The live heading, fed in by the route's sensor source.
     *
     * The route owns the streaming sensor; this field is the hand-off into the session. Applied
     * on every publish, so the radar's wedges reflect where the phone is pointing *now*, not
     * wherever the sampler's once-a-second blocking read happened to catch it - and on devices
     * with no magnetometer it is the only heading the session ever sees.
     */
    @Volatile
    public var liveHeadingDegrees: Double? = null

    /** Samples for [durationMillis]; a session already running is left alone. */
    public fun start(durationMillis: Long = SESSION_MILLIS) {
        if (sessionJob?.isActive == true) return

        val context = getApplication<Application>()
        // Same reasoning as Checkup: an empty radio read is a permissions question, and asking
        // up front turns a verdict of Unknown into a dialog the user can actually answer.
        val denied = MeasurementPermissions.missing(context, MeasurementPermissions.SIGNAL)
        if (denied.isNotEmpty()) {
            mutableState.value = SignalUiState.NeedPermission(denied)
            return
        }

        val active = TimelineSession(context).also { session = it }
        val timeline = active.timeline
        trend.reset()

        sessionJob = viewModelScope.launch {
            try {
                mutableState.value = SignalUiState.Live(
                    seconds = 0,
                    finished = false,
                    verdict = rules.signal(emptyList()),
                    compass = SignalCompass.verdict(emptyList()),
                    latest = null,
                    samplesSnapshot = emptyList(),
                    csv = null,
                    trend = null,
                )
                // Registering telephony listeners wants the main thread; sampling does not.
                withContext(Dispatchers.Main) { active.start() }

                val sampling = launch(Dispatchers.Default) { active.sample(durationMillis) }
                val ticker = launch {
                    var seconds = 0
                    while (isActive && sampling.isActive) {
                        delay(TICK_MILLIS)
                        seconds++
                        publish(timeline, seconds, finished = false, csv = null)
                    }
                }
                sampling.join()
                ticker.cancel()
                publish(
                    timeline = timeline,
                    seconds = (durationMillis / 1_000L).toInt(),
                    finished = true,
                    csv = exportLog(active),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = SignalUiState.Failed(
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
                )
            } finally {
                withContext(Dispatchers.Main) { active.stop() }
                session = null
            }
        }
    }

    /**
     * Ends the session early, keeping the reading on screen and the log it collected.
     *
     * The log is exported from the samples taken so far, so an interrupted walk still keeps
     * what it saw; a log that only exists when the user waited the full minute would disappear
     * exactly in the case a log is wanted. The export runs in its own coroutine rather than in
     * this call, because writing the file on the main thread would stall the frame that is
     * showing the last reading.
     */
    public fun stop() {
        val active = session
        val current = mutableState.value
        if (active != null && current is SignalUiState.Live) {
            mutableState.value = current.copy(finished = true)
            viewModelScope.launch {
                val csv = exportLog(active)
                val live = mutableState.value
                if (live is SignalUiState.Live) mutableState.value = live.copy(csv = csv)
            }
        }
        sessionJob?.cancel()
    }

    private fun publish(timeline: RadioTimeline, seconds: Int, finished: Boolean, csv: String?) {
        val samples = timeline.snapshot()
        val heading = liveHeadingDegrees
        // The sampler records whatever heading it caught at sample time; on a device whose
        // compass read blocked or arrived empty, the route's live value fills the newest sample
        // so the radar's wedges keep turning with the phone. A null leaves the sample as-is:
        // absence is recorded, never a guess.
        val withHeadings = if (heading != null && samples.isNotEmpty()) {
            val last = samples.last()
            if (last.headingDegrees == null) {
                samples.subList(0, samples.lastIndex) + last.copy(headingDegrees = heading)
            } else {
                samples
            }
        } else {
            samples
        }
        // Every sample's strength feeds the trend, not just the newest: a walk's shape is in
        // the whole window, and a gap (null RSRP) is skipped by the tracker, not counted.
        for (sample in samples) trend.append(sample.rsrpDbm)
        mutableState.value = SignalUiState.Live(
            seconds = seconds,
            finished = finished,
            verdict = rules.signal(listOf(Observations.signal(withHeadings))),
            compass = SignalCompass.verdict(withHeadings),
            latest = withHeadings.lastOrNull(),
            samplesSnapshot = withHeadings,
            csv = csv,
            trend = trend.evaluate(),
        )
    }

    /**
     * The session log, written to the reports directory and returned for sharing.
     *
     * Both, because a user sharing a bad spot wants the log to leave the phone, while the file
     * is what a bug report can point at later.
     */
    private suspend fun exportLog(session: TimelineSession): String? = withContext(Dispatchers.IO) {
        runCatching {
            val csv = session.exportCsv()
            ReportFiles.write(getApplication(), SIGNAL_LOG_FILE_NAME, csv)
            csv
        }.getOrNull()
    }

    private companion object {
        /**
         * A minute and a half: a slow full circle takes about half a minute at the radio's
         * once-a-second reporting rate, which leaves time to turn again and confirm the answer.
         */
        const val SESSION_MILLIS: Long = 90_000L

        /** The screen redraws once a second, matching the sampler's own cadence. */
        const val TICK_MILLIS: Long = 1_000L

        const val SIGNAL_LOG_FILE_NAME: String = "signal-session.csv"
    }
}

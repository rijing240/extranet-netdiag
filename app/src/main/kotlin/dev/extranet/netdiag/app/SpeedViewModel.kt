package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.core.decision.DiagnosisRules
import dev.extranet.netdiag.core.ledger.TimelineBudget
import dev.extranet.netdiag.core.verdict.Verdict
import dev.extranet.netdiag.measure.MiniThroughputProbe
import dev.extranet.netdiag.measure.Observations
import dev.extranet.netdiag.measure.SpeedTest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Speed screen's state machine. */
public sealed interface SpeedUiState {

    /** No speed test run yet in this process. */
    public data object Idle : SpeedUiState

    /** A staged test is running: [stage] is ping, download or upload, with its live numbers. */
    public data class Running(
        public val stage: String,
        public val kbps: Double?,
        public val fraction: Double?,
    ) : SpeedUiState

    /** The test finished: the plain rating, the report, and the verdict the rules produce. */
    public data class Done(
        public val report: SpeedTest.Report,
        public val verdict: Verdict,
    ) : SpeedUiState

    /** The test itself failed; a stage that failed inside a run is not this state. */
    public data class Failed(public val message: String) : SpeedUiState
}

/**
 * The staged speed test's view model.
 *
 * The engine does the measuring; this class owns the state machine and one judgement call: the
 * tab's verdict still comes from the decision layer, fed by the download finding, so a staged
 * report and the 1 MB reality check answer in the same vocabulary. Upload and jitter inform the
 * rating line on the report; they do not enter the diagnosis, because the diagnosis states
 * describe the path, and a thin uplink is a plan property, not a fault.
 *
 * Progress is forwarded as the engine reports it, which is what makes the gauge live: the
 * download stage samples its own rate twice a second while the payload streams.
 */
public class SpeedViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<SpeedUiState>(SpeedUiState.Idle)

    /** Current state, observed by the Speed screen. */
    public val state: StateFlow<SpeedUiState> = mutableState.asStateFlow()

    private val rules = DiagnosisRules()
    private var runJob: Job? = null
    private var pingMedianMillis: Double? = null

    /** Runs one staged test; a second call while one is running is ignored. */
    public fun run() {
        if (runJob?.isActive == true) return

        runJob = viewModelScope.launch {
            mutableState.value = SpeedUiState.Running("ping", null, null)
            try {
                pingMedianMillis = null
                val engine = SpeedTest()
                val report = withContext(Dispatchers.IO) {
                    engine.run { progress ->
                        mutableState.value = SpeedUiState.Running(progress.stage, progress.kbps, progress.fractionDone)
                    }
                }
                pingMedianMillis = report.pingMedianMillis
                val download = report.downloadKbps
                val verdict = if (download != null) {
                    rules.speed(listOf(Observations.throughput(asProbeResult(download))))
                } else {
                    rules.speed(emptyList())
                }
                mutableState.value = SpeedUiState.Done(report, verdict)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = SpeedUiState.Failed(
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
                )
            }
        }
    }

    /** Cancels a running test. The engine's stages are time-boxed, so this is a courtesy stop. */
    public fun cancel() {
        runJob?.cancel()
        runJob = null
        if (mutableState.value is SpeedUiState.Running) {
            mutableState.value = SpeedUiState.Failed("Cancelled.")
        }
    }

    /**
     * The staged download as the probe result shape the decision rules already speak.
     *
     * The rules' bands are bytes per second, so this is the single conversion between the two
     * vocabularies; the numbers are the staged test's own, dressed in the shared type.
     */
    private fun asProbeResult(downloadKbps: Double): MiniThroughputProbe.Result {
        val bytesPerSecond = downloadKbps * 1_000.0 / 8.0
        return MiniThroughputProbe.Result(
            bytesMoved = SpeedTest.DOWNLOAD_BYTES,
            transferMillis = (SpeedTest.DOWNLOAD_BYTES / bytesPerSecond * 1_000).toLong(),
            bytesPerSecond = bytesPerSecond,
            verdict = TimelineBudget.throughputVerdict(bytesPerSecond),
            pingMedianMillis = pingMedianMillis?.toLong(),
            detail = "staged test: download ${formatKbps(downloadKbps)}",
        )
    }

    private fun formatKbps(kbps: Double): String =
        String.format(Locale.ROOT, "%.0f kbps", kbps)
}

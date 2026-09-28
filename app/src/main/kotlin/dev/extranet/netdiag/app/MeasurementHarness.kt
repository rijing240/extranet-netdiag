package dev.extranet.netdiag.app

import android.content.Context
import dev.extranet.netdiag.android.measurement.AndroidOsDiagnostics
import dev.extranet.netdiag.core.ledger.MeasurementBudget
import dev.extranet.netdiag.measure.ProbeEngine
import dev.extranet.netdiag.measure.ProbeTarget
import dev.extranet.netdiag.measure.SocketProbeSetSource
import java.io.File

/** Outcome of one probe-engine run. */
public sealed interface MeasurementUiState {

    /** Nothing has been run yet. */
    public data object Idle : MeasurementUiState

    /** A run is in flight. */
    public data class Running(public val note: String) : MeasurementUiState

    /** A run finished and the waterfall report was written to disk. */
    public data class Done(
        public val run: ProbeRun,
        public val json: String,
        public val reportPath: String,
        public val externalReportPath: String?,
    ) : MeasurementUiState

    /** The harness itself failed. Individual probe failures never reach this state. */
    public data class Failed(public val message: String) : MeasurementUiState
}

/**
 * Runs B1's deliverable and persists the latency waterfall.
 *
 * Blocking by nature: the engine waits for the platform's connectivity report and then performs
 * real network work. Callers hand it a worker thread - the ViewModel does exactly that - and the
 * instrumented test calls it directly.
 *
 * Like B0's harness, this is the single entry point shared by the screen and the test. If they
 * diverged, the test would stop being evidence about the screen.
 */
public object MeasurementHarness {

    /** File name the waterfall is written under, in the app's internal files directory. */
    public const val REPORT_FILE_NAME: String = "probe-waterfall.json"

    /**
     * Runs [sets] probe sets against the default targets.
     *
     * @param sets how many sets to attempt; the report states how many actually ran, so a smaller
     *   number is visible rather than assumed.
     */
    public fun execute(
        context: Context,
        sets: Int = MeasurementBudget.SETS_PER_RUN,
        collectOsDiagnostics: Boolean = true,
        outputDirectory: File? = null,
    ): MeasurementUiState = try {
        val appContext = context.applicationContext

        val engine = ProbeEngine(
            setSource = SocketProbeSetSource(),
            osDiagnosticsSource = AndroidOsDiagnostics(appContext),
        )

        val run = engine.run(
            targets = ProbeTarget.DEFAULT,
            sets = sets,
            collectOsDiagnostics = collectOsDiagnostics,
        )
        val json = run.toJson()
        val written = ReportFiles.write(appContext, REPORT_FILE_NAME, json, outputDirectory)

        MeasurementUiState.Done(
            run = run,
            json = json,
            reportPath = written.path,
            externalReportPath = written.externalPath,
        )
    } catch (throwable: Throwable) {
        MeasurementUiState.Failed("${throwable.javaClass.simpleName}: ${throwable.message}")
    }
}

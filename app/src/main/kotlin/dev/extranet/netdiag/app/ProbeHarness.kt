package dev.extranet.netdiag.app

import android.content.Context
import dev.extranet.netdiag.android.measurement.AndroidPlatformProbe
import dev.extranet.netdiag.android.measurement.AsyncCapabilitySession
import dev.extranet.netdiag.core.report.CapabilityReport
import dev.extranet.netdiag.probe.CapabilityProbeRunner
import dev.extranet.netdiag.probe.ProbeCatalog
import dev.extranet.netdiag.probe.ProbeOutcome
import java.io.File

/** Outcome of one probe run. */
sealed interface ProbeUiState {

    /** Nothing has been run yet. */
    public data object Idle : ProbeUiState

    /** A run is in flight. */
    public data class Running(public val note: String) : ProbeUiState

    /** A run completed and the report was written to disk. */
    public data class Done(
        public val report: CapabilityReport,
        public val json: String,
        public val reportPath: String,
        public val asyncObserved: Int,
    ) : ProbeUiState

    /** The harness itself failed. Individual API failures never reach this state. */
    public data class Failed(public val message: String) : ProbeUiState
}

/**
 * Runs B0's deliverable and persists the machine-readable report.
 *
 * Kept as a single shared entry point so the debug screen and the instrumented test exercise
 * exactly the same code path. If they diverged, the test would stop being evidence about the
 * screen.
 */
public object ProbeHarness {

    /** File name the report is written under, in the app's external files directory. */
    public const val REPORT_FILE_NAME: String = "capability-report.json"

    /**
     * Runs the probe.
     *
     * @param context any context; the application context is used internally.
     * @param liveSession when true, first spends a bounded window listening for the
     *   asynchronous capabilities (GNSS measurements, telephony callbacks, network
     *   transitions). Costs a few seconds and needs location permission to yield anything.
     * @param outputDirectory where to write the report; defaults to the app's external files
     *   directory so CI can pull it with `adb pull`.
     */
    public fun execute(
        context: Context,
        liveSession: Boolean,
        outputDirectory: File? = null,
    ): ProbeUiState = try {
        val appContext = context.applicationContext
        val source = AndroidPlatformProbe(appContext)
        val runner = CapabilityProbeRunner(source)

        val asyncOutcomes: Map<String, ProbeOutcome> =
            if (liveSession) AsyncCapabilitySession(appContext).run() else emptyMap()

        val notes = buildList {
            add("B0 capability probe")
            add(
                if (liveSession) {
                    "live session ran: ${asyncOutcomes.size} of ${ProbeCatalog.asyncIds().size} " +
                        "asynchronous spec(s) observed"
                } else {
                    "synchronous pass only: asynchronous specs reported as NOT_PROBED"
                },
            )
            add("raw coordinates and cell identities are never included in this report")
        }

        val report = runner.run(
            catalog = ProbeCatalog.ALL,
            resolvedAsync = asyncOutcomes,
            notes = notes,
        )
        val json = report.toJson()

        val directory = outputDirectory
            ?: appContext.getExternalFilesDir(null)
            ?: appContext.filesDir
        val file = File(directory, REPORT_FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText(json)

        ProbeUiState.Done(
            report = report,
            json = json,
            reportPath = file.absolutePath,
            asyncObserved = asyncOutcomes.size,
        )
    } catch (throwable: Throwable) {
        ProbeUiState.Failed("${throwable.javaClass.simpleName}: ${throwable.message}")
    }
}

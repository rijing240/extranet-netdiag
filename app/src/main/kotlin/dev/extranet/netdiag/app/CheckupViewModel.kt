package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.extranet.netdiag.core.decision.DiagnosisRules
import dev.extranet.netdiag.core.decision.PathModel
import dev.extranet.netdiag.core.decision.PathNode
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.verdict.Verdict
import dev.extranet.netdiag.android.sensor.MobileDataReader
import dev.extranet.netdiag.measure.DiagnoseConfig
import dev.extranet.netdiag.measure.DiagnoseProbe
import dev.extranet.netdiag.measure.DiagnoseRules
import dev.extranet.netdiag.measure.Observations
import dev.extranet.netdiag.measure.RadioSample
import dev.extranet.netdiag.measure.RadioTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How far along one step of the check is. */
public enum class StepStatus {
    /** Not started; the check may never get to it inside its budget. */
    WAITING,

    /** Running now. */
    RUNNING,

    /** Finished; [DiagnoseStep.note] says what it found. */
    DONE,

    /** Not run, because the twenty seconds ran out or nothing before it made it worth asking. */
    SKIPPED,
}

/**
 * One line of the check's progress list.
 *
 * The list exists because the check takes up to twenty seconds and does several different things
 * in that time, and a screen showing one unchanging sentence for twenty seconds reads as a hang.
 * It is also the honest answer to "what did it actually test": the steps that ran are shown with
 * what they found, and the steps that did not run are shown as not having run, rather than being
 * quietly dropped.
 */
public data class DiagnoseStep(
    public val name: String,
    public val status: StepStatus,
    public val note: String? = null,
)

/** The Checkup screen's state machine. */
public sealed interface CheckupUiState {

    /** Nothing measured yet in this process. */
    public data object Idle : CheckupUiState

    /** A reading the check needs has not been granted; [denied] names the ones missing. */
    public data class NeedPermission(public val denied: List<String>) : CheckupUiState

    /**
     * The check is running; [steps] is the checklist as it stands.
     *
     * [elapsedSeconds] is here rather than in the screen because the screen has no clock of its
     * own and the check spends ten seconds inside one step with nothing to republish. A screen
     * that cannot tick looks stuck in exactly the step the user is most likely to assume has
     * hung, which is the moment a second number is worth having.
     */
    public data class Running(
        public val steps: List<DiagnoseStep>,
        public val elapsedSeconds: Int = 0,
    ) : CheckupUiState

    /** The check finished and the verdict is ready. */
    public data class Done(
        public val verdict: Verdict,
        public val diagnose: DiagnoseRules.Result,
        public val steps: List<DiagnoseStep>,
        public val nodes: List<PathNode>,
        public val findings: List<Finding>,
        public val summary: RadioTimeline.FieldSummary,
        /** A plain-text snapshot of this run, for pasting into a support conversation. */
        public val report: String,
    ) : CheckupUiState

    /** The check itself failed; a missing measurement is never this state. */
    public data class Failed(public val message: String) : CheckupUiState
}

/**
 * One Diagnose run: the platform's view of the link, then ten seconds of radio, then the probes.
 *
 * The order is not arbitrary, and it is the order the spec asks for. Each step is cheaper and more
 * certain than the one after it, and several of them can end the check on their own: a phone in
 * airplane mode has an answer before a socket is opened, and a network that redirects a plain
 * request to a top-up page has an answer before anything is measured. That is why the steps run in
 * this sequence rather than in parallel - running them together would take the same time and throw
 * away the chance to stop early.
 *
 * The view model owns the sequence and nothing else. What the numbers mean is decided by
 * `DiagnoseRules`, which is pure and has a table of tests behind it, so a wrong verdict is a bug a
 * unit test can catch rather than one that needs a carrier to reproduce.
 *
 * The whole run is bounded: once [DiagnoseConfig.totalBudgetMillis] is spent, the remaining network
 * steps are marked skipped and whatever was found is reported. A diagnosis that takes longer than
 * the user's patience is not a diagnosis.
 */
public class CheckupViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<CheckupUiState>(CheckupUiState.Idle)

    /** Current state, observed by the Diagnose screen. */
    public val state: StateFlow<CheckupUiState> = mutableState.asStateFlow()

    private val rules = DiagnosisRules()
    private val config = DiagnoseConfig.Default
    private var runningJob: Job? = null

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

        runningJob = viewModelScope.launch {
            val session = TimelineSession(context)
            val steps = STEP_NAMES.map { DiagnoseStep(it, StepStatus.WAITING) }.toMutableList()
            val startedAt = android.os.SystemClock.elapsedRealtime()

            fun spentMillis(): Long = android.os.SystemClock.elapsedRealtime() - startedAt
            fun budgetLeft(): Boolean = spentMillis() < config.totalBudgetMillis
            fun elapsed(): Int = (spentMillis() / 1000L).toInt()

            /** Marks a step and republishes, so the list moves while the check works. */
            fun step(index: Int, status: StepStatus, note: String? = null) {
                steps[index] = steps[index].copy(status = status, note = note)
                mutableState.value = CheckupUiState.Running(steps.toList(), elapsed())
            }

            // A tick a second, on its own, so the clock in the ring advances even while the
            // check is inside a single ten-second step. It republishes the same step list rather
            // than inventing progress: only the elapsed time changes, and that is a measurement.
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(TICK_MILLIS)
                    val current = mutableState.value
                    if (current is CheckupUiState.Running) {
                        mutableState.value = current.copy(elapsedSeconds = elapsed())
                    }
                }
            }

            try {
                // ------------------------------------------------ 1. what the platform knows
                step(STEP_LINK, StepStatus.RUNNING)
                val link = withContext(Dispatchers.Main) {
                    runCatching { LinkStateReader(context).read() }.getOrNull()
                }
                val linkFacts = link ?: DiagnoseRules.LinkFacts(
                    transport = DiagnoseRules.Transport.NONE,
                    connected = false,
                    hasInternetCapability = false,
                    validated = false,
                    captivePortal = false,
                    airplaneMode = false,
                )
                step(
                    STEP_LINK,
                    StepStatus.DONE,
                    when {
                        linkFacts.airplaneMode -> "Airplane mode is on"
                        linkFacts.captivePortal -> "The network needs a sign-in page"
                        !linkFacts.connected -> "No network is connected"
                        else -> "Connected over ${linkFacts.transport.name.lowercase()}"
                    },
                )

                // ------------------------------------------------ 2. the radio
                step(STEP_RADIO, StepStatus.RUNNING)
                withContext(Dispatchers.Main) { session.start() }
                val timeline = withContext(Dispatchers.Default) { session.sample(RADIO_MILLIS) }
                val samples = timeline.snapshot()
                val signal = signalFacts(samples)
                step(
                    STEP_RADIO,
                    StepStatus.DONE,
                    signalSummary(signal),
                )

                // ------------------------------------------------ 3. the plain request
                val probeWorthRunning = linkFacts.connected && budgetLeft()
                step(
                    STEP_HTTP,
                    if (probeWorthRunning) StepStatus.RUNNING else StepStatus.SKIPPED,
                    if (probeWorthRunning) null else skipNote(linkFacts, budgetLeft()),
                )
                val http = if (probeWorthRunning) {
                    withContext(Dispatchers.IO) { DiagnoseProbe.httpProbe(config) }
                } else {
                    DiagnoseRules.HttpFacts()
                }
                if (probeWorthRunning) {
                    step(STEP_HTTP, StepStatus.DONE, httpSummary(http))
                }

                // ------------------------------------------------ 4. names and addresses
                val addressWorthRunning = linkFacts.connected && budgetLeft()
                step(STEP_ADDRESS, if (addressWorthRunning) StepStatus.RUNNING else StepStatus.SKIPPED)
                val address = if (addressWorthRunning) {
                    withContext(Dispatchers.IO) { DiagnoseProbe.addressProbe(config) }
                } else {
                    DiagnoseRules.AddressFacts()
                }
                if (addressWorthRunning) {
                    step(STEP_ADDRESS, StepStatus.DONE, addressSummary(address))
                }

                // ------------------------------------------------ 5. round-trip time
                val latencyWorthRunning = linkFacts.connected && budgetLeft()
                step(STEP_LATENCY, if (latencyWorthRunning) StepStatus.RUNNING else StepStatus.SKIPPED)
                val latency = if (latencyWorthRunning) {
                    withContext(Dispatchers.IO) { DiagnoseProbe.latencyProbe(config) }
                } else {
                    DiagnoseRules.PerformanceFacts()
                }
                if (latencyWorthRunning) {
                    step(
                        STEP_LATENCY,
                        StepStatus.DONE,
                        latency.latencyMedianMillis?.let { "Median round trip $it ms" } ?: "Nothing answered",
                    )
                }

                // ------------------------------------------------ 6. the two hops
                step(STEP_HOPS, StepStatus.RUNNING)
                val hops = withContext(Dispatchers.IO) { session.twoHopVerdict() }
                step(STEP_HOPS, StepStatus.DONE, hops.statement())

                // ------------------------------------------------ 7. payload
                step(STEP_THROUGHPUT, StepStatus.RUNNING)
                val throughput = withContext(Dispatchers.IO) { session.throughputCheck() }
                step(STEP_THROUGHPUT, StepStatus.DONE, throughput.statement())

                // Read on the main thread with the other telephony work: the SIM's data state is
                // part of what a checkup is asked, and a carrier that has switched data off
                // explains every "nothing answered" result below it.
                val mobileData = withContext(Dispatchers.Main) {
                    runCatching { MobileDataReader(context).read() }.getOrNull()
                }

                val performance = latency.copy(
                    throughputBitsPerSecond = throughput.bytesPerSecond * 8.0,
                )
                val diagnose = DiagnoseRules.classify(
                    DiagnoseRules.Facts(
                        link = linkFacts,
                        signal = signal,
                        http = http,
                        address = address,
                        performance = performance,
                    ),
                    config,
                )

                val findings = Observations.checkup(samples, hops, throughput, mobileData)
                val operator = withContext(Dispatchers.Main) {
                    runCatching { (context.getSystemService(android.content.Context.TELEPHONY_SERVICE)
                        as? android.telephony.TelephonyManager)?.networkOperatorName }.getOrNull()
                }

                mutableState.value = CheckupUiState.Done(
                    verdict = rules.checkup(findings),
                    diagnose = diagnose,
                    steps = steps.toList(),
                    nodes = PathModel.of(findings),
                    findings = findings,
                    summary = timeline.summary(),
                    report = report(
                        diagnose = diagnose,
                        signal = signal,
                        link = linkFacts,
                        performance = performance,
                        operator = operator,
                        steps = steps.toList(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = CheckupUiState.Failed(
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
                )
            } finally {
                ticker.cancel()
                withContext(Dispatchers.Main) { session.stop() }
            }
        }
    }

    /**
     * Abandons a running check and returns to idle.
     *
     * Cancellation unwinds the sampling and probe coroutines, and the session's stop runs in
     * the cancelled job's finally block, so the radio listeners are released on the way out -
     * a cancelled check must not leave the timeline registered.
     */
    public fun cancel() {
        runningJob?.cancel()
        runningJob = null
        if (mutableState.value is CheckupUiState.Running) {
            mutableState.value = CheckupUiState.Idle
        }
    }

    /** The middle of each signal reading, or null where the platform said nothing. */
    private fun signalFacts(samples: List<RadioSample>): DiagnoseRules.SignalFacts = DiagnoseRules.SignalFacts(
        rsrpDbm = medianOrNull(samples.mapNotNull { it.rsrpDbm }),
        rsrqDb = medianOrNull(samples.mapNotNull { it.rsrqDb }),
        sinrDb = medianOrNull(samples.mapNotNull { it.rssnrDb }),
        rssiDbm = medianOrNull(samples.mapNotNull { it.rssiDbm }),
    )

    private fun signalSummary(signal: DiagnoseRules.SignalFacts): String {
        val parts = listOfNotNull(
            signal.rsrpDbm?.let { "RSRP $it dBm" },
            signal.rssiDbm?.let { "RSSI $it dBm" },
            signal.rsrqDb?.let { "RSRQ $it dB" },
            signal.sinrDb?.let { "SINR $it dB" },
        )
        return if (parts.isEmpty()) "The platform reported no signal values" else parts.joinToString(", ")
    }

    private fun httpSummary(http: DiagnoseRules.HttpFacts): String = when {
        !http.attempted -> "Not run"
        !http.reached -> "No answer after ${http.elapsedMillis} ms"
        http.redirectedToHost != null -> "Redirected to ${http.redirectedToHost}"
        http.unexpectedContent -> "The network answered with its own page"
        else -> "Answered normally in ${http.elapsedMillis} ms"
    }

    private fun addressSummary(address: DiagnoseRules.AddressFacts): String = when {
        address.nameResolved == true && address.rawAddressReached == true ->
            "Names and addresses both work"
        address.nameResolved == false && address.rawAddressReached == true ->
            "Addresses work but names do not"
        address.nameResolved == true -> "Names resolve but the address did not answer"
        else -> "Neither names nor addresses answered"
    }

    private fun skipNote(link: DiagnoseRules.LinkFacts, budgetLeft: Boolean): String = when {
        !budgetLeft -> "Skipped: the check ran out of time"
        !link.connected -> "Skipped: nothing is connected"
        else -> "Skipped"
    }

    /**
     * The plain-text snapshot offered by "share report".
     *
     * It carries the steps, including the ones that were skipped, because the first question
     * anyone reading a support report asks is what was actually tested. It carries no identifiers
     * beyond the operator's own name, which the user can see on their own screen.
     */
    private fun report(
        diagnose: DiagnoseRules.Result,
        signal: DiagnoseRules.SignalFacts,
        link: DiagnoseRules.LinkFacts,
        performance: DiagnoseRules.PerformanceFacts,
        operator: String?,
        steps: List<DiagnoseStep>,
    ): String = buildString {
        appendLine("extranet report")
        appendLine("When: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())}")
        operator?.takeIf { it.isNotBlank() }?.let { appendLine("Network: $it (${link.transport.name.lowercase()})") }
        appendLine("Result: ${diagnose.headline} (confidence ${diagnose.confidence.name.lowercase()})")
        appendLine()
        appendLine("Evidence")
        diagnose.evidence.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Signal")
        appendLine("- RSRP: ${signal.rsrpDbm ?: "not reported"} dBm")
        appendLine("- RSRQ: ${signal.rsrqDb ?: "not reported"} dB")
        appendLine("- SINR: ${signal.sinrDb ?: "not reported"} dB")
        appendLine("- RSSI: ${signal.rssiDbm ?: "not reported"} dBm")
        performance.latencyMedianMillis?.let { appendLine("- Median round trip: $it ms") }
        performance.throughputBitsPerSecond?.let {
            appendLine("- Throughput: ${(it / 1_000_000.0 * 10).toInt() / 10.0} Mbit/s")
        }
        appendLine()
        appendLine("What was checked")
        steps.forEach { appendLine("- ${it.name}: ${it.status.name.lowercase()}${it.note?.let { note -> " - $note" } ?: ""}") }
        appendLine()
        appendLine("Suggested next step: ${diagnose.action}")
    }

    private fun medianOrNull(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        /**
         * Ten seconds of radio sampling: enough for a median over ten readings, which is twice
         * the minimum the inference layer will call a measurement, and short enough that the
         * whole checkup stays inside the time a user will wait for an answer.
         */
        const val RADIO_SECONDS: Int = 10
        const val RADIO_MILLIS: Long = 10_000L

        val STEP_NAMES = listOf(
            "Checking the connection",
            "Sampling the radio for 10 s",
            "Asking the network for a small plain page",
            "Checking names and addresses",
            "Measuring the round trip",
            "Asking the first hop and the internet",
            "Moving a megabyte to see whether data flows",
        )

        const val STEP_LINK = 0
        const val STEP_RADIO = 1
        const val STEP_HTTP = 2
        const val STEP_ADDRESS = 3
        const val STEP_LATENCY = 4
        const val STEP_HOPS = 5
        const val STEP_THROUGHPUT = 6

        /** How often the running check republishes its elapsed time. */
        const val TICK_MILLIS = 500L
    }
}
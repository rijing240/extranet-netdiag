package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.decision.Subjects
import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.ledger.TimelineBudget
import java.util.Locale

/**
 * The inference layer: measurements in, findings out, no advice.
 *
 * A finding is an observation with a confidence, and the numbers behind it are quoted in the
 * evidence so the screen can show them without re-reading the radio. Nothing here decides what
 * the user should do, and nothing here writes a sentence a user reads as an answer: that split
 * is what lets the decision rules be tested against fixed findings and this file be tested
 * against fixed measurements.
 *
 * The thresholds below are inference judgements, not physical constants, so they live next to
 * the code that applies them rather than in the ledger. Each one is chosen to be defensible on
 * a phone rather than precise: a weak link is where LTE starts dropping from QPSK to nothing,
 * not where a lab would draw the line.
 */
public object Observations {

    /**
     * RSRP at or below which the radio link is called weak, dBm.
     *
     * About -110 dBm is where LTE stops holding a usable data channel: -80 is a good link,
     * -100 is marginal, and below -110 the phone is usually clinging to the cell it can still
     * hear. A median rather than a worst sample is compared, because one dip behind a wall is
     * not a weak link.
     */
    public const val WEAK_RSRP_DBM: Int = -110

    /** RSRP at or below which the link is workable but not strong, dBm. */
    public const val FAIR_RSRP_DBM: Int = -100

    /**
     * Samples needed before the radio's own reading may be called anything at all.
     *
     * The timeline samples once a second, so this is five seconds of a mostly still phone. Below
     * it the honest answer is Unknown: a median of two samples is a coin toss, not a measurement.
     */
    public const val MINIMUM_SIGNAL_SAMPLES: Int = 5

    /** Samples at which the signal reading is trusted nearly fully; thirty seconds at 1 Hz. */
    public const val CONFIDENT_SIGNAL_SAMPLES: Int = 30

    /**
     * Confidence in a hop that answered.
     *
     * Higher than the unreachable case because a positive answer is hard to fake: something on
     * the local link completed a TCP handshake. The probe's round count is fixed by the ledger
     * and aggregated inside it, so this is a judgement about the kind of answer rather than a
     * proportion of rounds that happened to survive.
     */
    public const val REACHABLE_HOP_CONFIDENCE: Double = 0.85

    /**
     * Confidence in a hop that did not answer.
     *
     * Lower than [REACHABLE_HOP_CONFIDENCE] on purpose: a filter, a sleeping router or a
     * firewall can all swallow a probe, so silence is weaker evidence than an answer.
     */
    public const val UNREACHABLE_HOP_CONFIDENCE: Double = 0.75

    /** The radio link's own reading, over one session's samples. */
    public fun signal(samples: List<RadioSample>): Finding {
        val readings = samples.mapNotNull { it.rsrpDbm }
        if (readings.size < MINIMUM_SIGNAL_SAMPLES) {
            return Finding(
                subject = Subjects.SIGNAL,
                assessment = DiagnosisState.UNKNOWN,
                confidence = 0.0,
                evidence = listOf(
                    "only ${readings.size} of ${samples.size} samples carried an RSRP; " +
                        "$MINIMUM_SIGNAL_SAMPLES are needed before it is a measurement",
                ),
            )
        }
        val median = median(readings)
        val assessment = when {
            median <= WEAK_RSRP_DBM -> DiagnosisState.WEAK
            median <= FAIR_RSRP_DBM -> DiagnosisState.FAIR
            else -> DiagnosisState.GOOD
        }
        return Finding(
            subject = Subjects.SIGNAL,
            assessment = assessment,
            confidence = signalConfidence(readings.size),
            evidence = listOf(
                "median RSRP ${median.toInt()} dBm over ${readings.size} samples",
                "RSRP reported in ${readings.size} of ${samples.size} samples",
            ),
        )
    }

    /** The first hop and the internet hop, from one two-hop probe run. */
    public fun hops(verdict: TwoHopProbe.Verdict): List<Finding> = listOf(
        hopFinding(
            subject = Subjects.FIRST_HOP,
            address = verdict.gateway.address,
            label = "gateway ${verdict.gateway.address} on port ${TimelineBudget.LOCAL_HOP_PORT}",
            reachable = verdict.gateway.reachable,
            latencyMillis = verdict.gatewayMedianMillis,
        ),
        hopFinding(
            subject = Subjects.INTERNET_HOP,
            address = verdict.internet.address,
            label = verdict.internet.address,
            reachable = verdict.internet.reachable,
            latencyMillis = verdict.internetMedianMillis,
        ),
    )

    /** What the payload actually did, from one mini-throughput run. */
    public fun throughput(result: MiniThroughputProbe.Result): Finding {
        if (result.bytesMoved == 0L && result.verdict != "INTERCEPTED") {
            // A transfer that brought nothing back is not a slow transfer: the rate of a failed
            // fetch is unknown, and calling it zero would hand the rules a speed claim they
            // cannot support.
            return Finding(
                subject = Subjects.THROUGHPUT,
                assessment = DiagnosisState.UNKNOWN,
                confidence = 0.0,
                evidence = listOf("no body arrived: ${result.detail ?: "the transfer produced nothing"}"),
            )
        }
        val assessment = when (result.verdict) {
            "GOOD" -> DiagnosisState.GOOD
            "MARGINAL" -> DiagnosisState.FAIR
            "INTERCEPTED" -> DiagnosisState.OFFLINE
            else -> DiagnosisState.SLOW
        }
        val evidence = mutableListOf<String>()
        if (result.verdict != "INTERCEPTED") {
            evidence.add(
                "moved ${result.bytesMoved} B in ${result.transferMillis} ms " +
                "(${format(result.bytesPerSecond * 8.0 / 1_000_000.0)} Mbps)"
            )
        }
        result.pingMedianMillis?.let {
            evidence += "ping ${it} ms median over ${TimelineBudget.THROUGHPUT_PING_ROUNDS} rounds"
        }
        result.detail?.let { evidence += it }
        return Finding(
            subject = Subjects.THROUGHPUT,
            assessment = assessment,
            confidence = when (assessment) {
                DiagnosisState.GOOD -> 0.9
                DiagnosisState.FAIR -> 0.8
                DiagnosisState.OFFLINE -> 0.95
                else -> 0.85
            },
            evidence = evidence,
        )
    }

    /**
     * The findings one Checkup produces, in the order the rules weigh them.
     *
     * Composed here rather than in the screen so the view model and the tests build the same
     * list from the same measurements: a checkup that skipped the radio finding on a device
     * without a SIM must be the same code path the rules were tested against.
     */
    public fun checkup(
        samples: List<RadioSample>,
        hops: TwoHopProbe.Verdict,
        throughput: MiniThroughputProbe.Result,
    ): List<Finding> = listOf(signal(samples)) + hops(hops) + listOf(throughput(throughput))

    private fun hopFinding(
        subject: String,
        address: String,
        label: String,
        reachable: Boolean,
        latencyMillis: Long?,
    ): Finding {
        if (address == TwoHopProbe.UNKNOWN_GATEWAY_ADDRESS) {
            return Finding(
                subject = subject,
                assessment = DiagnosisState.UNKNOWN,
                confidence = 0.0,
                evidence = listOf("no gateway address was available, so this hop was never asked"),
            )
        }
        return if (reachable) {
            Finding(
                subject = subject,
                assessment = DiagnosisState.GOOD,
                confidence = REACHABLE_HOP_CONFIDENCE,
                evidence = listOf("$label answered in ${latencyMillis ?: 0L} ms"),
            )
        } else {
            Finding(
                subject = subject,
                assessment = DiagnosisState.OFFLINE,
                confidence = UNREACHABLE_HOP_CONFIDENCE,
                evidence = listOf("$label: no answer in ${TimelineBudget.TWO_HOP_ROUNDS} rounds"),
            )
        }
    }

    private fun signalConfidence(readingCount: Int): Double = when {
        readingCount >= CONFIDENT_SIGNAL_SAMPLES -> 0.9
        readingCount >= 10 -> 0.8
        else -> 0.65
    }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}

package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.decision.Subjects
import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.verdict.FindingCause
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

    /** The pseudo network type the sampler writes for a Wi-Fi transport. */
    public const val WIFI_TYPE: String = "WIFI"

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
     * Wi-Fi bands, dBm RSSI.
     *
     * Deliberately different from the LTE thresholds: -100 dBm of LTE RSRP is a marginal cell
     * edge, while -100 dBm of Wi-Fi RSSI is a network you cannot use at all. -67 keeps HD
     * video smooth, -75 starts to stutter, and past -85 packets stop moving.
     */
    public const val FAIR_WIFI_RSSI_DBM: Int = -67
    public const val WEAK_WIFI_RSSI_DBM: Int = -80

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
        // The link's technology decides what the numbers mean. A Wi-Fi RSSI of -45 is an
        // excellent link; an LTE RSRP of -45 cannot exist. Sessions sometimes straddle a
        // transport switch, so the majority type wins and a mixed session says so.
        val types = samples.mapNotNull { it.networkType }
        val networkType = types.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val median = median(readings)
        val assessment = when (networkType) {
            WIFI_TYPE -> when {
                median <= WEAK_WIFI_RSSI_DBM -> DiagnosisState.WEAK
                median <= FAIR_WIFI_RSSI_DBM -> DiagnosisState.FAIR
                else -> DiagnosisState.GOOD
            }
            else -> when {
                median <= WEAK_RSRP_DBM -> DiagnosisState.WEAK
                median <= FAIR_RSRP_DBM -> DiagnosisState.FAIR
                else -> DiagnosisState.GOOD
            }
        }
        return Finding(
            subject = Subjects.SIGNAL,
            assessment = assessment,
            confidence = signalConfidence(readings.size),
            evidence = listOf(
                "median ${median.toInt()} dBm over ${readings.size} samples on $networkType",
                "strength reported in ${readings.size} of ${samples.size} samples",
            ),
        )
    }

    /**
     * The first hop and the internet hop, from one two-hop probe run.
     *
     * [onCellular] downgrades a silent first hop to Unknown rather than Offline, and the reason
     * is not politeness: on mobile data the first hop is the carrier's gateway address, which is
     * a router and not a web server, so it answers nothing on port 80 every single time. Reading
     * that silence as a dead link paints "Carrier network: Offline" on a phone whose data is
     * working perfectly, which is precisely the false alarm this app exists to remove. A probe
     * that cannot distinguish a healthy router from a broken one has measured nothing, and is
     * reported as having measured nothing.
     */
    public fun hops(verdict: TwoHopProbe.Verdict, onCellular: Boolean = false): List<Finding> = listOf(
        if (onCellular && !verdict.gateway.reachable) {
            Finding(
                subject = Subjects.FIRST_HOP,
                assessment = DiagnosisState.UNKNOWN,
                confidence = 0.0,
                evidence = listOf(
                    "gateway ${verdict.gateway.address} did not answer on port " +
                        "${TimelineBudget.LOCAL_HOP_PORT}, which a cellular gateway normally does not, " +
                        "so this hop could not be measured",
                ),
            )
        } else {
            hopFinding(
                subject = Subjects.FIRST_HOP,
                address = verdict.gateway.address,
                label = "gateway ${verdict.gateway.address}",
                reachable = verdict.gateway.reachable,
                latencyMillis = verdict.gatewayMedianMillis,
                answeredEcho = verdict.gateway.answeredEcho,
            )
        },
        hopFinding(
            subject = Subjects.INTERNET_HOP,
            address = verdict.internet.address,
            label = verdict.internet.address,
            reachable = verdict.internet.reachable,
            latencyMillis = verdict.internetMedianMillis,
            answeredEcho = verdict.internet.answeredEcho,
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
     * What the SIM's mobile data is doing, as one finding.
     *
     * Deliberately silent about the balance, because the balance is not knowable - see
     * [MobileDataStatus]. What it does instead is name *why* data is off, which is the question
     * that helps a user choose a next step without claiming a carrier restriction is definitely
     * an exhausted bundle.
     */
    public fun mobileData(status: MobileDataStatus): Finding {
        val carrier = status.carrierName?.takeIf { it.isNotBlank() }
        val evidence = mutableListOf<String>()

        if (!status.hasActiveSim) {
            return Finding(
                subject = Subjects.MOBILE_DATA,
                assessment = DiagnosisState.UNKNOWN,
                confidence = 0.0,
                evidence = listOf("no SIM is registered, so there is no mobile data to have used up"),
            )
        }
        if (carrier != null) evidence += "SIM is $carrier"

        // The order is the argument's order: the most specific reason the platform gives comes
        // first, because every later branch would otherwise explain away a firmer fact.
        when {
            status.offReason == DataOffReason.CARRIER -> {
                evidence += "the carrier has switched mobile data off on this SIM"
                evidence += "this can be a bundle, account, roaming, or other carrier restriction; the exact bundle amount was not available to this app"
                return Finding(Subjects.MOBILE_DATA, DiagnosisState.OFFLINE, 0.9, evidence, cause = FindingCause.CARRIER_BLOCKED)
            }
            status.offReason == DataOffReason.POLICY -> {
                evidence += "a device policy is blocking mobile data; this may be the phone's data limit or a managed-device rule"
                return Finding(Subjects.MOBILE_DATA, DiagnosisState.OFFLINE, 0.85, evidence, cause = FindingCause.POLICY_BLOCKED)
            }
            status.offReason == DataOffReason.USER -> {
                evidence += "mobile data is switched off in this phone's settings"
                return Finding(Subjects.MOBILE_DATA, DiagnosisState.OFFLINE, 0.9, evidence, cause = FindingCause.USER_DISABLED)
            }
            status.offReason == DataOffReason.THERMAL -> {
                evidence += "the phone switched mobile data off to protect itself from heat or battery"
                return Finding(Subjects.MOBILE_DATA, DiagnosisState.OFFLINE, 0.8, evidence, cause = FindingCause.THERMAL_BLOCKED)
            }
            !status.dataEnabled -> {
                evidence += "mobile data is off and the phone will not say who turned it off"
                return Finding(Subjects.MOBILE_DATA, DiagnosisState.OFFLINE, 0.55, evidence, cause = FindingCause.BLOCKED_UNKNOWN)
            }
        }

        // Data is on. Whether a connection is up depends on what else the phone is using, so an
        // idle cellular radio is not a fault and must not be reported as one.
        evidence += if (status.roaming) "roaming" else "not roaming"
        evidence += when (status.dataState) {
            DataLinkState.CONNECTED -> "the mobile data connection is up"
            DataLinkState.CONNECTING -> "the mobile data connection is coming up"
            DataLinkState.DISCONNECTED -> "no mobile data connection is up at the moment"
            DataLinkState.UNKNOWN -> "the data connection state was not reported"
            DataLinkState.SUSPENDED -> "IP traffic is temporarily suspended on the mobile data connection"
        }
        status.mobileBytesSinceBoot?.let {
            evidence += "${formatBytes(it)} has moved over mobile data since the phone last restarted"
        }

        if (status.dataState == DataLinkState.SUSPENDED && !status.cellularValidated) {
            return Finding(
                subject = Subjects.MOBILE_DATA,
                assessment = DiagnosisState.FAIR,
                confidence = 0.6,
                evidence = evidence,
            )
        }

        return when {
            status.cellularActive && status.cellularValidated -> Finding(
                subject = Subjects.MOBILE_DATA,
                assessment = DiagnosisState.GOOD,
                confidence = 0.85,
                evidence = evidence +
                    listOf("the phone is using mobile data and the system confirmed internet access over it"),
            )
            status.cellularActive -> Finding(
                subject = Subjects.MOBILE_DATA,
                assessment = DiagnosisState.FAIR,
                confidence = 0.7,
                evidence = evidence +
                    listOf("mobile data is up but the system could not confirm internet access over it"),
            )
            else -> Finding(
                subject = Subjects.MOBILE_DATA,
                assessment = DiagnosisState.FAIR,
                confidence = 0.6,
                evidence = evidence +
                    listOf("mobile data is on but idle, because the phone is using another connection"),
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        val megabytes = bytes / 1_000_000.0
        return if (megabytes >= 1_000.0) String.format(Locale.ROOT, "%.1f GB", megabytes / 1_000.0)
        else String.format(Locale.ROOT, "%.0f MB", megabytes)
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
        mobileData: MobileDataStatus? = null,
    ): List<Finding> = buildList {
        add(signal(samples))
        mobileData?.let { add(mobileData(it)) }
        addAll(hops(hops, onCellular(samples)))
        add(throughput(throughput))
    }

    /** True when most of the session rode the cell rather than Wi-Fi. */
    private fun onCellular(samples: List<RadioSample>): Boolean {
        val types = samples.mapNotNull { it.networkType }
        val majority = types.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return majority != null && majority != WIFI_TYPE
    }

    private fun hopFinding(
        subject: String,
        address: String,
        label: String,
        reachable: Boolean,
        latencyMillis: Long?,
        answeredEcho: Boolean = false,
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
                // An echo answer proves the hop is alive but times nothing, so no figure is
                // quoted: "answered in 0 ms" would be a measurement that was never made, and the
                // congestion rule compares these numbers against each other.
                evidence = listOf(
                    when {
                        answeredEcho && latencyMillis == null -> "$label answered an echo request"
                        latencyMillis != null -> "$label answered in $latencyMillis ms"
                        else -> "$label answered"
                    },
                ),
                latencyMillis = latencyMillis,
            )
        } else {
            Finding(
                subject = subject,
                assessment = DiagnosisState.OFFLINE,
                confidence = UNREACHABLE_HOP_CONFIDENCE,
                evidence = listOf("$label: no answer on its port or to an echo in ${TimelineBudget.TWO_HOP_ROUNDS} rounds"),
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

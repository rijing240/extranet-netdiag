package dev.extranet.netdiag.measure

/**
 * What the Diagnose tab decides, and why, kept apart from how it measures.
 *
 * Every fact this needs is handed in as data, so the whole decision table can be exercised without
 * a phone, a network or a socket. That matters more here than anywhere else in the app: a wrong
 * verdict on the Signal tab sends someone across a room, and a wrong verdict here tells them to
 * go and check their balance with their carrier, or to wait for an outage that is not happening.
 *
 * The vocabulary is the point of the design. Every answer is one of seven situations, and every
 * one of them is a *likely* - carriers behave differently from each other and from themselves a
 * month ago, and the evidence a phone can gather is a handful of probes against a handful of
 * endpoints. So the result always carries a confidence, the lines of evidence it was built from,
 * and exactly one action. Nothing is stated as certain, and nothing is stated without saying what
 * it was based on.
 *
 * Two orderings matter and are deliberate.
 *
 * **The link comes first**, because everything after it is meaningless without it: a phone with no
 * network cannot say anything about the network, and a phone whose Wi-Fi has a captive portal has
 * not got a data problem at all.
 *
 * **A strong signal with no data is not a signal problem.** Once the radio is known to be strong,
 * a failure to reach anything means the trouble is past the radio - the account, the carrier, or
 * the network beyond it - and saying "weak signal" there would send the user to the window for a
 * fault that is not in the room.
 */
public object DiagnoseRules {

    /** The seven situations the check can find. */
    public enum class Situation {
        /** No working link at all: airplane mode, nothing connected, or Wi-Fi with no internet. */
        NO_LINK,

        /** The radio link itself is weak where the user is standing. */
        WEAK_SIGNAL,

        /** The link is fine but traffic is redirected or refused the way depleted plans are. */
        DATA_BLOCKED,

        /** Signal is good and data works, but it is slow - the cell is busy. */
        CONGESTION,

        /** The link looks healthy but nothing beyond the carrier answers. */
        UPSTREAM_OUTAGE,

        /** Names are not resolving, though raw addresses work. */
        DNS_PROBLEM,

        /** Everything checked out. */
        NORMAL,
    }

    /** How much the evidence supports the answer. */
    public enum class Confidence { HIGH, MEDIUM, LOW }

    /** Which kind of link the check ran over. */
    public enum class Transport { CELLULAR, WIFI, NONE }

    /** What the platform says about the active network, before any traffic is sent. */
    public data class LinkFacts(
        public val transport: Transport,
        public val connected: Boolean,
        public val hasInternetCapability: Boolean,
        public val validated: Boolean,
        public val captivePortal: Boolean,
        public val airplaneMode: Boolean,
    )

    /** What the radio reported, any of which may be absent. */
    public data class SignalFacts(
        public val rsrpDbm: Int? = null,
        public val rssiDbm: Int? = null,
        public val rsrqDb: Int? = null,
        public val sinrDb: Int? = null,
    )

    /**
     * What came back from the plain-HTTP probe.
     *
     * [redirectedToHost] is set only when the answer came from a different host than the one
     * asked for, which is the shape of every carrier top-up page: the network intercepts the
     * request and answers with its own destination.
     */
    public data class HttpFacts(
        public val attempted: Boolean = false,
        public val reached: Boolean = false,
        public val statusCode: Int? = null,
        public val redirectedToHost: String? = null,
        /**
         * True when the answer was not the empty page that was asked for.
         *
         * A carrier that intercepts a request usually redirects it, but it sometimes simply
         * answers it itself with a page of its own. Both are interception; only one of them has a
         * destination to name, and the screen shows the destination when there is one because
         * "answered by topup.example-carrier.example" is the sentence that makes the user say
         * "ah, that is my carrier's page".
         */
        public val unexpectedContent: Boolean = false,
        public val elapsedMillis: Long = 0L,
    )

    /** What came back from reaching an address without asking DNS first. */
    public data class AddressFacts(
        public val nameResolved: Boolean? = null,
        public val rawAddressReached: Boolean? = null,
    )

    /** What came back from the latency and throughput measurements. */
    public data class PerformanceFacts(
        public val latencyMedianMillis: Long? = null,
        public val throughputBitsPerSecond: Double? = null,
        public val endpointsTried: Int = 0,
        public val endpointsFailed: Int = 0,
    )

    /** Everything the classifier is allowed to see. */
    public data class Facts(
        public val link: LinkFacts,
        public val signal: SignalFacts = SignalFacts(),
        public val http: HttpFacts = HttpFacts(),
        public val address: AddressFacts = AddressFacts(),
        public val performance: PerformanceFacts = PerformanceFacts(),
    )

    /** The answer: which situation, how sure, what it was based on, and the one thing to do. */
    public data class Result(
        public val situation: Situation,
        public val confidence: Confidence,
        public val headline: String,
        public val evidence: List<String>,
        public val action: String,
    )

    /** Decides the situation from the facts. Never throws; missing facts narrow the answer. */
    public fun classify(facts: Facts, config: DiagnoseConfig = DiagnoseConfig.Default): Result {
        val link = facts.link

        // ---------------------------------------------------------------- 1. the link itself
        if (link.airplaneMode) {
            return Result(
                situation = Situation.NO_LINK,
                confidence = Confidence.HIGH,
                headline = "Airplane mode is on, so there is no connection",
                evidence = listOf("Airplane mode is switched on"),
                action = "Turn airplane mode off, then run this again",
            )
        }
        if (!link.connected || link.transport == Transport.NONE) {
            return Result(
                situation = Situation.NO_LINK,
                confidence = Confidence.HIGH,
                headline = "This phone is not connected to anything",
                evidence = listOf("No active network connection"),
                action = "Join a Wi-Fi network or turn mobile data on, then run this again",
            )
        }
        if (link.captivePortal) {
            return Result(
                situation = if (link.transport == Transport.WIFI) Situation.NO_LINK else Situation.DATA_BLOCKED,
                confidence = Confidence.HIGH,
                headline = if (link.transport == Transport.WIFI) {
                    "This Wi-Fi network wants you to sign in first"
                } else {
                    "The network is redirecting traffic to a sign-in page"
                },
                evidence = listOf(
                    "The network reports itself as a captive portal",
                    "Traffic is being intercepted until you sign in",
                ),
                action = if (link.transport == Transport.WIFI) {
                    "Open your browser and complete the Wi-Fi sign-in page"
                } else {
                    "Open your browser and complete the sign-in page it shows you"
                },
            )
        }
        if (!link.validated && !link.hasInternetCapability) {
            return Result(
                situation = Situation.NO_LINK,
                confidence = Confidence.MEDIUM,
                headline = "This network does not appear to reach the internet",
                evidence = listOf(
                    "Connected to ${transportName(link.transport)}",
                    "The platform does not report internet access on it",
                ),
                action = "Try another network, or run this again in a moment",
            )
        }

        // ---------------------------------------------------------------- 2. the radio
        val weak = isWeak(facts.signal, link.transport, config)
        if (weak == true) {
            return Result(
                situation = Situation.WEAK_SIGNAL,
                confidence = Confidence.HIGH,
                headline = "The signal here is weak",
                evidence = listOfNotNull(
                    facts.signal.rsrpDbm?.let { "Mobile signal $it dBm" },
                    facts.signal.rssiDbm?.let { "Wi-Fi signal $it dBm" },
                    facts.signal.rsrqDb?.let { "Signal quality $it dB" },
                    "Below the level at which data reliably gets through",
                ),
                action = "Move closer to your router, or to a window, and run this again",
            )
        }

        // ---------------------------------------------------------------- 3. interception
        val redirected = facts.http.redirectedToHost
        val intercepted = redirected != null || (facts.http.reached && facts.http.unexpectedContent)
        if (intercepted) {
            return Result(
                situation = Situation.DATA_BLOCKED,
                confidence = Confidence.HIGH,
                headline = "Traffic is being redirected, which usually means the plan is used up",
                evidence = listOfNotNull(
                    if (redirected != null) {
                        "A request to ${config.probeHost} was answered by $redirected"
                    } else {
                        "A request to ${config.probeHost} was answered with a page instead of the empty reply it asks for"
                    },
                    facts.http.statusCode?.let { "It came back as HTTP $it" },
                    "This is how carriers send you to a top-up page",
                ),
                action = "Check your data balance with your carrier",
            )
        }
        val refusedInstantly = facts.http.attempted && !facts.http.reached &&
            facts.http.elapsedMillis in 1 until config.instantFailureMillis
        if (refusedInstantly) {
            return Result(
                situation = Situation.DATA_BLOCKED,
                confidence = Confidence.MEDIUM,
                headline = "Data may be finished, or the line may be blocked",
                evidence = listOfNotNull(
                    "The request was refused at once rather than timing out",
                    facts.signal.rsrpDbm?.let { "Signal is strong at $it dBm" },
                    "A network that has stopped serving a line says no immediately",
                ),
                action = "Check your data balance with your carrier",
            )
        }

        // ---------------------------------------------------------------- 4. names vs addresses
        val nameResolved = facts.address.nameResolved
        val rawReached = facts.address.rawAddressReached
        if (nameResolved == false && rawReached == true) {
            return Result(
                situation = Situation.DNS_PROBLEM,
                confidence = Confidence.HIGH,
                headline = "Names are not resolving, but addresses work",
                evidence = listOf(
                    "Looking up ${config.dnsHost} failed",
                    "Reaching ${config.rawIpAddress} without a name lookup worked",
                    "The connection itself is fine; the address book is not",
                ),
                action = "Try another DNS server in your network settings",
            )
        }

        // ---------------------------------------------------------------- 5. speed and shape
        val strong = isStrong(facts.signal, link.transport, config)
        val latency = facts.performance.latencyMedianMillis
        val throughput = facts.performance.throughputBitsPerSecond
        val allEndpointsFailed = facts.performance.endpointsTried > 0 &&
            facts.performance.endpointsFailed == facts.performance.endpointsTried

        if (strong == true && allEndpointsFailed && facts.http.attempted && !facts.http.reached) {
            return Result(
                situation = Situation.UPSTREAM_OUTAGE,
                confidence = Confidence.MEDIUM,
                headline = "The connection looks healthy, but nothing beyond it answers",
                evidence = listOfNotNull(
                    facts.signal.rsrpDbm?.let { "Signal is strong at $it dBm" },
                    "All ${facts.performance.endpointsTried} endpoints timed out rather than refusing",
                    "The slow failures point past your phone and past the local network",
                ),
                action = "Wait a few minutes and try again; if it persists, report it to your provider",
            )
        }

        val slow = (latency != null && latency > config.busyLatencyMillis) ||
            (throughput != null && throughput < config.congestedThroughputBps)
        if (slow && strong != false) {
            // Poor quality with strong signal is the cell being busy rather than far away, which
            // is worth saying at a higher confidence because the two mean different things to the
            // person reading it: one is "move", the other is "wait".
            // Strength and quality are different facts about the same cell. Strong power with
            // poor quality is a cell crowded with other people's traffic or fighting interference,
            // and it is both a firmer diagnosis and a different piece of advice from "move".
            val poorQuality = weak != true &&
                ((facts.signal.rsrqDb != null && facts.signal.rsrqDb < POOR_RSRQ_DB) ||
                    (facts.signal.sinrDb != null && facts.signal.sinrDb < POOR_SINR_DB))
            return Result(
                situation = Situation.CONGESTION,
                confidence = if (poorQuality) Confidence.HIGH else Confidence.MEDIUM,
                headline = "The connection works but is slow right now",
                evidence = listOfNotNull(
                    latency?.let { "Median latency ${it} ms" },
                    throughput?.let { "About ${formatRate(it)}" },
                    facts.signal.rsrpDbm?.let { "Signal is strong at $it dBm, so the radio is not the problem" },
                    if (poorQuality) {
                        "Signal quality is poor while strength is good: the cell is busy or interference is high"
                    } else {
                        null
                    },
                ),
                action = "Wait a few minutes, or move to a less busy spot, and try again",
            )
        }

        if (facts.http.attempted && facts.http.reached && slow) {
            return Result(
                situation = Situation.CONGESTION,
                confidence = Confidence.MEDIUM,
                headline = "The connection works but is slow right now",
                evidence = listOfNotNull(
                    "The internet answered, so the link is intact",
                    latency?.let { "Median latency ${it} ms" },
                    throughput?.let { "About ${formatRate(it)}" },
                ),
                action = "Wait a few minutes and try again",
            )
        }

        // ---------------------------------------------------------------- 6. nothing wrong
        val anythingFailed = nameResolved == false || rawReached == false ||
            (facts.http.attempted && !facts.http.reached) || allEndpointsFailed
        if (anythingFailed) {
            return Result(
                situation = Situation.UPSTREAM_OUTAGE,
                confidence = Confidence.LOW,
                headline = "Something did not answer, but not enough to be sure what",
                evidence = listOfNotNull(
                    nameResolved?.let { if (it) "Names resolved" else "A name lookup failed" },
                    rawReached?.let { if (it) "A raw address was reached" else "A raw address did not answer" },
                    if (facts.performance.endpointsTried > 0) {
                        "${facts.performance.endpointsTried - facts.performance.endpointsFailed} of " +
                            "${facts.performance.endpointsTried} endpoints answered"
                    } else {
                        null
                    },
                ),
                action = "Run this again, and try a different network if you can",
            )
        }

        return Result(
            situation = Situation.NORMAL,
            confidence = if (facts.signal.rsrpDbm == null && facts.signal.rssiDbm == null) {
                Confidence.MEDIUM
            } else {
                Confidence.HIGH
            },
            headline = "Everything checked out",
            evidence = listOfNotNull(
                "Connected over ${transportName(link.transport)}",
                facts.signal.rsrpDbm?.let { "Signal $it dBm" },
                facts.signal.rssiDbm?.let { "Signal $it dBm" },
                facts.performance.latencyMedianMillis?.let { "Median latency $it ms" },
                throughput?.let { "About ${formatRate(it)}" },
                "The internet answered an ordinary request without being redirected",
            ),
            action = "Nothing to do; if a particular app is still failing, the problem is in that app",
        )
    }

    /** True when the radio is weak for the link in use; null when nothing was reported. */
    private fun isWeak(signal: SignalFacts, transport: Transport, config: DiagnoseConfig): Boolean? = when (transport) {
        Transport.CELLULAR -> signal.rsrpDbm?.let { it < config.weakRsrpDbm }
        Transport.WIFI -> signal.rssiDbm?.let { it < config.weakRssiDbm }
        Transport.NONE -> null
    }

    /** True when the radio is strong for the link in use; null when nothing was reported. */
    private fun isStrong(signal: SignalFacts, transport: Transport, config: DiagnoseConfig): Boolean? = when (transport) {
        // Wi-Fi has no equivalent of "strong enough that the fault must be elsewhere": RSSI is a
        // measure of distance to one box, and a router two metres away with nothing behind it is
        // an ordinary thing to find.
        Transport.CELLULAR -> signal.rsrpDbm?.let { it >= config.strongRsrpDbm }
        Transport.WIFI -> null
        Transport.NONE -> null
    }

    private fun transportName(transport: Transport): String = when (transport) {
        Transport.CELLULAR -> "mobile data"
        Transport.WIFI -> "Wi-Fi"
        Transport.NONE -> "no connection"
    }

    private fun formatRate(bitsPerSecond: Double): String = when {
        bitsPerSecond >= 1_000_000 -> "${(bitsPerSecond / 1_000_000 * 10).toInt() / 10.0} Mbit/s"
        bitsPerSecond >= 1_000 -> "${(bitsPerSecond / 1_000).toInt()} kbit/s"
        else -> "${bitsPerSecond.toInt()} bit/s"
    }

    /** RSRQ below this with strong RSRP means a busy or interfered cell rather than distance. */
    public const val POOR_RSRQ_DB: Int = -15

    /** The same for signal-to-noise, in dB. */
    public const val POOR_SINR_DB: Int = 5
}
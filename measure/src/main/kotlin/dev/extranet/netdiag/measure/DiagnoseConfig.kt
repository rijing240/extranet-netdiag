package dev.extranet.netdiag.measure

/**
 * Every number the Diagnose tab acts on, in one place.
 *
 * They are here rather than scattered through the probes because they are all guesses of the
 * same kind: a threshold that separates "this is the problem" from "this is normal" for a
 * network nobody in this codebase has ever seen. Carriers differ by country, by generation and by
 * how busy the cell is, and a number that is right on one network is wrong on another. Gathering
 * them means the day one of them is wrong, it is wrong in one line that can be changed without
 * touching anything that reads it.
 *
 * The comments say *why* each is where it is, and that matters more here than elsewhere: a
 * threshold with no reason attached cannot be revised safely, because nobody can tell what it was
 * protecting.
 */
public data class DiagnoseConfig(
    /**
     * RSRP below which cellular is called weak, in dBm.
     *
     * The middle of the usual band. A cell's reference power runs from about -140 dBm (nothing) to
     * -80 dBm (under the mast); below -110 is where throughput collapses on most bands and where
     * both Android and the carriers themselves draw the bottom bar. It is a heuristic and it is
     * deliberately not the same number as "bad" for the user, who may be perfectly served at
     * -112 on a quiet band.
     */
    public val weakRsrpDbm: Int = -110,

    /** RSSI below which Wi-Fi is called weak, in dBm, on the same reasoning as [weakRsrpDbm]. */
    public val weakRssiDbm: Int = -80,

    /**
     * RSRP above which cellular is called strong, in dBm.
     *
     * Used only to decide whether a *failure* to reach the internet is a signal problem or
     * something further up. Strong signal with no data is the interesting case - it is what
     * exhaustion, blocking and upstream outages all look like - so this number is deliberately
     * generous.
     */
    public val strongRsrpDbm: Int = -95,

    /**
     * Median latency above which the connection is called busy, in milliseconds.
     *
     * Three hundred milliseconds is roughly the point at which every tap feels wrong to a person
     * using the phone, which is the symptom that brings them here. It is high enough that an
     * ordinary loaded cell does not trip it.
     */
    public val busyLatencyMillis: Long = 300,

    /** Below this measured throughput, in bits per second, data is barely moving. */
    public val congestedThroughputBps: Double = 500_000.0,

    /**
     * The plain-HTTP endpoint used to catch carriers' top-up and captive portals.
     *
     * Plain HTTP on purpose, and the only plain-HTTP request the app makes. A redirect to a
     * carrier's payment page cannot be detected over TLS without breaking the transport's own
     * protection, and the thing being measured is precisely whether the network is willing to
     * let an ordinary request through unaltered. It returns an empty 204 and no content, so
     * nothing is downloaded and nothing about the user is sent.
     */
    public val probeUrl: String = "http://connectivitycheck.gstatic.com/generate_204",

    /** The host [probeUrl] names, to compare against where the answer actually came from. */
    public val probeHost: String = "connectivitycheck.gstatic.com",

    /** How long to wait for the plain-HTTP probe before calling it a slow failure. */
    public val probeTimeoutMillis: Int = 6_000,

    /**
     * A failure faster than this is the network refusing, not the network being slow.
     *
     * Connection refused and connection reset come back immediately, and they mean something
     * completely different from a timeout: a carrier that has stopped serving a line says no at
     * once, while a congested cell swallows the request and answers eventually or not at all.
     */
    public val instantFailureMillis: Long = 1_000,

    /** How many small HTTPS requests the latency measurement makes. */
    public val latencyProbeCount: Int = 5,

    /** How many endpoints latency is measured against; several, so one bad host is not an outage. */
    public val latencyEndpoints: List<String> = listOf(
        "1.1.1.1",
        "8.8.8.8",
        "9.9.9.9",
    ),

    /** The hostname resolved in the DNS check, and the address connected to without DNS. */
    public val dnsHost: String = "connectivitycheck.gstatic.com",

    /** An address reached without asking DNS anything, so "no names" and "no route" can be told apart. */
    public val rawIpAddress: String = "1.1.1.1",

    /** The port both of those are tried on. */
    public val probePort: Int = 443,

    /**
     * The whole check's budget, in milliseconds.
     *
     * Twenty seconds is the longest a person will hold a phone waiting for a diagnosis without
     * assuming it has hung. Steps stop being started once this is spent, and whatever was found
     * is reported with the steps that did not run named as such.
     */
    public val totalBudgetMillis: Long = 20_000L,

    /** How long a single socket connect may take, in milliseconds. */
    public val connectTimeoutMillis: Int = 4_000,

    /** How much to download when measuring throughput. A megabyte: enough to see past slow start. */
    public val throughputBytes: Int = 1_000_000,
) {
    public companion object {
        /** The configuration the app ships with; tests construct their own. */
        public val Default: DiagnoseConfig = DiagnoseConfig()
    }
}
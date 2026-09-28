package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget

/** A resolver the active network offered, and the port the engine queries it on. */
public data class ResolverAddress(
    public val host: String,
    public val port: Int = MeasurementBudget.DNS_PORT,
) {
    /** Renders as `host` when the port is the standard one, so logs stay readable. */
    override fun toString(): String =
        if (port == MeasurementBudget.DNS_PORT) host else "$host:$port"
}

/**
 * What the platform's own connectivity diagnostics saw.
 *
 * This is the OS's answer, not ours: the system already probes DNS, HTTP and HTTPS to decide
 * whether a network is usable, and it reports the result to apps that ask. Two things make it
 * worth collecting next to our own waterfall:
 *
 *  * It is independent evidence. If the platform says the network is validated and our probe
 *    sets are failing, the fault is ours or the target's, not the path's.
 *  * It carries a data-stall verdict, which is the platform's own word for "the radio looked
 *    connected but traffic was not moving" - the exact condition the plan's prediction model
 *    exists to catch.
 *
 * The raw counts are kept as the platform reported them. Decoding them into probe names would
 * mean depending on constants the platform has since deprecated, and a report that guesses is
 * worse than one that quotes.
 *
 * @property additionalInfo every key the platform chose to include, rendered as text, so a
 *   reader sees what was actually reported rather than only the fields this class knows about.
 */
public data class OsDiagnostics(
    public val reportTimestampMillis: Long,
    public val interfaceName: String?,
    public val mtu: Int?,
    public val dnsServers: List<String>,
    public val privateDnsActive: Boolean?,
    public val privateDnsServerName: String?,
    public val transports: List<String>,
    public val linkDownstreamBandwidthKbps: Int?,
    public val linkUpstreamBandwidthKbps: Int?,
    public val validationResult: Int?,
    public val probesAttemptedBitmask: Int?,
    public val probesSucceededBitmask: Int?,
    public val additionalInfo: Map<String, String>,
    public val dataStall: DataStall?,
) {
    /** A suspected data stall: connectivity present, traffic not progressing. */
    public data class DataStall(
        public val timestampMillis: Long,
        /** The platform's detection method, raw: it is versioned and not ours to reinterpret. */
        public val detectionMethod: Int?,
        public val details: Map<String, String>,
    )
}

/**
 * The platform half of the engine: what the device knows that a JVM does not.
 *
 * Two methods, because there are exactly two platform questions. The resolver is needed before
 * the first probe set runs and must not depend on the diagnostics report, which can take
 * seconds or never arrive.
 */
public interface OsDiagnosticsSource {

    /** The resolver of the active network, or null when no network is up. */
    public fun resolver(): ResolverAddress?

    /**
     * Asks the platform for its connectivity report, waiting at most [waitMillis].
     *
     * Returns null when the platform will not answer - no network, an API level without the
     * service, or a refused registration. Null is a finding in its own right and the report
     * distinguishes it from an empty answer.
     */
    public fun collect(waitMillis: Long): OsDiagnostics?
}

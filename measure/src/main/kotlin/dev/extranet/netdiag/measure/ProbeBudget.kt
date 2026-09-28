package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget

/**
 * The timeouts and the wall clock cap one run executes under.
 *
 * Every field defaults to the ledger, so production behaviour is the documented behaviour. It is
 * a parameter rather than a direct read of [MeasurementBudget] so that a test can shrink the
 * timeouts to milliseconds and a device run can tighten them without a second code path.
 */
public data class ProbeBudget(
    public val dnsTimeoutMillis: Int = MeasurementBudget.DNS_TIMEOUT_MILLIS,
    public val tcpTimeoutMillis: Int = MeasurementBudget.TCP_TIMEOUT_MILLIS,
    public val tlsTimeoutMillis: Int = MeasurementBudget.TLS_TIMEOUT_MILLIS,
    public val ttfbTimeoutMillis: Int = MeasurementBudget.TTFB_TIMEOUT_MILLIS,
    public val wallClockCapMillis: Long = MeasurementBudget.WALL_CLOCK_CAP_MILLIS,
) {
    init {
        require(dnsTimeoutMillis > 0) { "dns timeout must be positive: $dnsTimeoutMillis" }
        require(tcpTimeoutMillis > 0) { "tcp timeout must be positive: $tcpTimeoutMillis" }
        require(tlsTimeoutMillis > 0) { "tls timeout must be positive: $tlsTimeoutMillis" }
        require(ttfbTimeoutMillis > 0) { "ttfb timeout must be positive: $ttfbTimeoutMillis" }
        require(wallClockCapMillis > 0L) { "wall clock cap must be positive: $wallClockCapMillis" }
    }

    /** Budget for [layer]. */
    public fun timeoutFor(layer: Layer): Int = when (layer) {
        Layer.DNS -> dnsTimeoutMillis
        Layer.TCP -> tcpTimeoutMillis
        Layer.TLS -> tlsTimeoutMillis
        Layer.TTFB -> ttfbTimeoutMillis
    }

    /** Worst case for one set under this budget. */
    public fun worstCaseSetMillis(): Long =
        dnsTimeoutMillis.toLong() + tcpTimeoutMillis + tlsTimeoutMillis + ttfbTimeoutMillis
}

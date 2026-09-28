package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget

/**
 * Latency distribution of one stage over a run.
 *
 * Percentiles are nearest-rank, not interpolated. Interpolation would invent a millisecond that
 * no probe observed, and the numbers here are used to decide where a real fault is, which is not
 * a place to be smoothing data.
 */
public data class LatencyStats(
    public val count: Int,
    public val minMillis: Long,
    public val maxMillis: Long,
    public val p50Millis: Long,
    public val p95Millis: Long,
) {
    public companion object {

        /** Summarises [samples], or null when there is nothing to summarise. */
        public fun of(samples: List<Long>): LatencyStats? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            return LatencyStats(
                count = sorted.size,
                minMillis = sorted.first(),
                maxMillis = sorted.last(),
                p50Millis = percentile(sorted, MeasurementBudget.PERCENTILE_P50),
                p95Millis = percentile(sorted, MeasurementBudget.PERCENTILE_P95),
            )
        }

        /**
         * Nearest-rank percentile of an already-sorted series.
         *
         * The rank is `ceil(fraction x n)`, clamped to the series. So p95 of eight samples is
         * the eighth, and p50 of four is the second: the lower of the two middle values rather
         * than their average, because the average of two observed latencies is not an observed
         * latency.
         */
        public fun percentile(sorted: List<Long>, fraction: Double): Long {
            require(sorted.isNotEmpty()) { "percentile of an empty series is undefined" }
            require(fraction in 0.0..1.0) { "fraction must be in 0..1, was $fraction" }
            val rank = kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

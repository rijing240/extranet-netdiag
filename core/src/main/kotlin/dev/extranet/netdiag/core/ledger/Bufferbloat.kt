package dev.extranet.netdiag.core.ledger

/**
 * Bufferbloat measurement, the replacement for the retired Little's Law calculation.
 *
 *     Delta = loaded_p50_rtt - idle_p50_rtt
 *
 * Little's Law (L = lambda * W) is an identity, not a predictor, and its arrival-rate term
 * is not observable through `NetworkStatsManager`, which reports polled byte counters with
 * second-to-minute granularity rather than packet rates. The measurable, causal test is the
 * latency inflation a saturated link exhibits, which is what this ledger encodes.
 *
 * ## Test cost
 * Percentile stability needs roughly 30 samples per state at ~1 Hz, so a real measurement
 * takes 60-75 seconds and saturates the link. That is the most expensive test in the app:
 * saturating 10 Mbps for 20 s already costs 25 MB. Payload is therefore capped and the test
 * is user-initiated only.
 */
public object Bufferbloat {

    /** Samples required per state for a stable p50 estimate. */
    public const val SAMPLES_PER_STATE: Int = 30

    /** Nominal wall-clock duration of a full test, seconds. */
    public const val NOMINAL_TEST_SECONDS: Double = 75.0

    /** Hard ceiling on payload consumed by a single test, bytes (10 MB). */
    public const val MAX_TEST_BYTES: Long = 10_000_000L

    /** Saturation rate ceiling for the capped variant, bits per second. */
    public const val CAPPED_SATURATION_BPS: Long = 5_000_000L

    /** Latency inflation of a saturated link beyond this is a failure. */
    public const val SEVERE_DELTA_MS: Double = 400.0

    /** Latency inflation in milliseconds: loaded percentile minus idle percentile. */
    public fun deltaMilliseconds(idleP50Ms: Double, loadedP50Ms: Double): Double = loadedP50Ms - idleP50Ms

    /**
     * Grading, following the convention used by consumer bufferbloat tests.
     * A < 30 ms, B < 60 ms, C < 150 ms, D < 400 ms, F otherwise.
     */
    public fun gradeFor(deltaMs: Double): BufferbloatGrade {
        val d = kotlin.math.max(0.0, deltaMs)
        return when {
            d < 30.0 -> BufferbloatGrade.A
            d < 60.0 -> BufferbloatGrade.B
            d < 150.0 -> BufferbloatGrade.C
            d < 400.0 -> BufferbloatGrade.D
            else -> BufferbloatGrade.F
        }
    }

    /** Upper delta bound for a grade, milliseconds. [BufferbloatGrade.F] is unbounded. */
    public fun upperBoundMilliseconds(grade: BufferbloatGrade): Double = when (grade) {
        BufferbloatGrade.A -> 30.0
        BufferbloatGrade.B -> 60.0
        BufferbloatGrade.C -> 150.0
        BufferbloatGrade.D -> 400.0
        BufferbloatGrade.F -> Double.POSITIVE_INFINITY
    }

    /** Payload cost in bytes for saturating [bitsPerSecond] for [seconds]. */
    public fun payloadBytes(bitsPerSecond: Long, seconds: Double): Long =
        (bitsPerSecond.toDouble() / 8.0 * seconds).toLong()
}

/** Bufferbloat grades as used by consumer latency-under-load tests. */
public enum class BufferbloatGrade { A, B, C, D, F }

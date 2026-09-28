package dev.extranet.netdiag.core.ledger

/**
 * The probe engine's fixed decisions (S2, batch B1).
 *
 * These are budget and quality gates rather than physical constants, and they are here for the
 * same reason the physical ones are: a later batch must not re-choose them, and a test must be
 * able to fail when the engine silently stops honouring them.
 *
 * The interesting tension this encodes is between [SETS_PER_RUN] and [WALL_CLOCK_CAP_MILLIS].
 * B1's exit criterion asks for 100 probe sets, but a set has four stages that can each time out,
 * so the worst case for one set is [worstCaseSetMillis] and a run in which everything times out
 * can only finish [setsWithinCapWhenEveryLayerTimesOut] sets before the cap stops it. That is
 * deliberate: a run against a dead network must report that it was cut short rather than take
 * twenty minutes to prove nothing. [meetsExitCriterion] therefore refuses a truncated run.
 */
public object MeasurementBudget {

    /** Probe sets one run must complete to satisfy B1's exit criterion. */
    public const val SETS_PER_RUN: Int = 100

    /**
     * Failure rate above which a run has failed its own exit criterion.
     *
     * A set counts as failed when a stage was attempted and did not succeed, so this ceiling is
     * the engine's own health measure: 5% allows for sporadic packet loss, not for a stage that
     * is broken on every set.
     */
    public const val FAILURE_RATE_CEILING: Double = 0.05

    /** Typical wall clock for one complete set on a healthy network, milliseconds. */
    public const val TYPICAL_SET_MILLIS: Int = 1_000

    /**
     * Wall clock cap for one run: twice the typical budget for a full run, milliseconds (200,000).
     *
     * Twice rather than once, so a network that is merely slow still finishes all 100 sets.
     */
    public const val WALL_CLOCK_CAP_MILLIS: Long = SETS_PER_RUN * TYPICAL_SET_MILLIS * 2L

    /** Budget for the name-resolution stage, milliseconds. */
    public const val DNS_TIMEOUT_MILLIS: Int = 2_000

    /** Budget for the TCP handshake stage, milliseconds. */
    public const val TCP_TIMEOUT_MILLIS: Int = 3_000

    /** Budget for the TLS handshake stage, milliseconds. */
    public const val TLS_TIMEOUT_MILLIS: Int = 3_000

    /** Budget for the time-to-first-byte stage, milliseconds. */
    public const val TTFB_TIMEOUT_MILLIS: Int = 5_000

    /** How long to wait for the platform's own connectivity report, milliseconds. */
    public const val OS_DIAGNOSTICS_WAIT_MILLIS: Long = 3_000L

    /** Port a UDP DNS query is sent to. */
    public const val DNS_PORT: Int = 53

    /** Port the default probe targets use. */
    public const val HTTPS_PORT: Int = 443

    /** The lower percentile the waterfall reports. */
    public const val PERCENTILE_P50: Double = 0.50

    /** The upper percentile the waterfall reports, which is the one that predicts complaints. */
    public const val PERCENTILE_P95: Double = 0.95

    /** Worst case for one set, with every stage timing out: 13,000 ms. */
    public fun worstCaseSetMillis(): Int =
        DNS_TIMEOUT_MILLIS + TCP_TIMEOUT_MILLIS + TLS_TIMEOUT_MILLIS + TTFB_TIMEOUT_MILLIS

    /** Worst case for a whole run, with every stage of every set timing out: 1,300,000 ms. */
    public fun worstCaseRunMillis(): Long = worstCaseSetMillis().toLong() * SETS_PER_RUN

    /** Sets the wall clock cap allows when every stage times out: 15. */
    public fun setsWithinCapWhenEveryLayerTimesOut(): Int =
        (WALL_CLOCK_CAP_MILLIS / worstCaseSetMillis().toLong()).toInt()

    /**
     * Failure rate of a run, 1.0 when nothing was attempted.
     *
     * A run that produced no sets has not shown a low failure rate; it has shown nothing, and
     * answering 0.0 here would let an engine that never starts report perfect health.
     */
    public fun failureRate(setsAttempted: Int, failedSets: Int): Double =
        if (setsAttempted == 0) 1.0 else failedSets.toDouble() / setsAttempted

    /** True when a run completed every required set under the failure ceiling. */
    public fun meetsExitCriterion(setsAttempted: Int, failedSets: Int): Boolean =
        setsAttempted >= SETS_PER_RUN && failureRate(setsAttempted, failedSets) < FAILURE_RATE_CEILING
}

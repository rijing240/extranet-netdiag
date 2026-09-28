package dev.extranet.netdiag.core.ledger

import kotlin.math.ceil

/**
 * The sample-size gate that decides whether the drop predictor (B8/B9) is even trainable.
 *
 *     n = z^2 * p * (1 - p) / e^2
 *
 * At 95% confidence (z = 1.96), worst-case proportion (p = 0.5) and a +/-5 percentage point
 * margin, n = 3.8416 * 0.25 / 0.0025 = 384.16, which rounds up to **385 positive events**.
 * (The approved plan quotes 384, the truncated value; the ledger uses the ceiling because a
 * shortfall is the failure mode that matters.)
 *
 * ## Why this drives the whole roadmap
 * A network-loss event is rare. At a 2% base rate, 385 positives require 19,250 observed
 * minutes: **320 hours** on a single handset, but roughly **2 days** across a 200-user fleet.
 * That asymmetry is the reason the data-collecting consumer app (B1-B7) must ship publicly
 * long before the paid prediction SDK (B8-B10). Without the fleet, the model has no dataset.
 */
public object ModelGate {

    /** Two-sided z for 95% confidence. */
    public const val Z_95: Double = 1.96

    /** Two-sided z for 90% confidence, for a cheaper early read. */
    public const val Z_90: Double = 1.6448536269514722

    /** Default half-width of the confidence interval on precision. */
    public const val DEFAULT_MARGIN: Double = 0.05

    /** Worst-case proportion: maximises the required sample size. */
    public const val WORST_CASE_PROPORTION: Double = 0.5

    /** Assumed fraction of observed minutes containing a loss transition. */
    public const val DEFAULT_LOSS_BASE_RATE: Double = 0.02

    /** Minutes of observation per contributing user per day. */
    public const val DEFAULT_MINUTES_PER_USER_PER_DAY: Double = 60.0

    /** Fleet size assumed by the plan's two-day figure. */
    public const val PLANNED_FLEET_USERS: Int = 200

    /** Positive events required for the given confidence, margin and proportion. */
    public fun requiredPositiveSamples(
        z: Double = Z_95,
        margin: Double = DEFAULT_MARGIN,
        proportion: Double = WORST_CASE_PROPORTION,
    ): Int {
        require(margin > 0.0 && margin < 1.0) { "margin must be in (0,1), was $margin" }
        require(proportion > 0.0 && proportion < 1.0) { "proportion must be in (0,1), was $proportion" }
        return ceil(z * z * proportion * (1.0 - proportion) / (margin * margin)).toInt()
    }

    /** Minutes of observation needed to collect [positives] events at [baseRate]. */
    public fun requiredObservedMinutes(
        positives: Int,
        baseRate: Double = DEFAULT_LOSS_BASE_RATE,
    ): Double {
        require(baseRate > 0.0) { "baseRate must be positive, was $baseRate" }
        return positives / baseRate
    }

    /** Wall-clock hours a single handset must run to reach [positives]. */
    public fun singleDeviceHours(
        positives: Int = requiredPositiveSamples(),
        baseRate: Double = DEFAULT_LOSS_BASE_RATE,
    ): Double = requiredObservedMinutes(positives, baseRate) / 60.0

    /** Days a fleet of [users] needs to reach [positives]. */
    public fun fleetDays(
        positives: Int = requiredPositiveSamples(),
        users: Int = PLANNED_FLEET_USERS,
        minutesPerUserPerDay: Double = DEFAULT_MINUTES_PER_USER_PER_DAY,
        baseRate: Double = DEFAULT_LOSS_BASE_RATE,
    ): Double {
        require(users > 0) { "users must be positive, was $users" }
        require(minutesPerUserPerDay > 0.0) { "minutesPerUserPerDay must be positive" }
        return requiredObservedMinutes(positives, baseRate) / (users * minutesPerUserPerDay)
    }
}

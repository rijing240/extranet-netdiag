package dev.extranet.netdiag.core.ledger

import kotlin.math.max

/**
 * Timing-advance ranging for LTE/NR and GSM.
 *
 * ## Derivation (LTE/NR)
 * 3GPP defines the basic time unit as Ts = 1 / (15000 * 2048) s = 32.552083... ns.
 * One timing-advance command step is 16 * Ts = 520.8333... ns of *round trip*.
 *
 *     d = TA_steps * 16Ts * c / 2
 *       = 1 * 5.208333333e-7 s * 299792458 m/s / 2
 *       = 78.0709526 m per step
 *
 * The commonly quoted 78.125 m comes from using c = 3x10^8 exactly:
 *     16Ts * 3e8 / 2 = 78.125 m
 * The difference is 0.054 m per step (0.07%).
 *
 * ## Why this is a band, not a distance
 * - Android's [android.telephony.CellSignalStrengthLte.getTimingAdvance] reports **basic time
 *   units (Ts)**, not command steps. One command step is 16 basic units, so one basic unit is
 *   4.8794 m. A modem that reports the TA command value expresses it in multiples of 16.
 * - Quantisation is therefore at best half a command step: +/-39.036 m.
 * - Non-line-of-sight propagation makes the path longer than the geometric distance, so the
 *   error is biased *outward* by roughly +50 m to +150 m, not symmetric.
 *
 * Net accuracy is a few hundred metres at best, which is why this feeds "how far is the
 * serving cell" and approach/leave trend detection, never a map pin.
 */
public object TimingAdvance {

    /** LTE/NR subcarrier spacing, Hz. */
    public const val SUBCARRIER_SPACING_HZ: Double = 15_000.0

    /** LTE FFT size used to derive the basic time unit. */
    public const val FFT_SIZE: Int = 2_048

    /** Denominator of Ts: 15000 * 2048 = 30_720_000. */
    public const val BASIC_TIME_UNIT_DENOMINATOR: Double = 30_720_000.0

    /** One TA command step spans this many basic time units. */
    public const val COMMAND_STEP_BASIC_UNITS: Int = 16

    /** Ts, seconds. */
    public val BASIC_TIME_UNIT_SECONDS: Double = 1.0 / BASIC_TIME_UNIT_DENOMINATOR

    /** 16 * Ts, seconds. */
    public val COMMAND_STEP_SECONDS: Double = BASIC_TIME_UNIT_SECONDS * COMMAND_STEP_BASIC_UNITS

    /** True one-way metres per command step: 78.0709526. */
    public val COMMAND_STEP_METRES: Double = COMMAND_STEP_SECONDS * PhysicalConstants.SPEED_OF_LIGHT_MPS / 2.0

    /** The widely quoted rounded figure, for documentation and legacy comparison only. */
    public const val COMMAND_STEP_METRES_APPROX: Double = 78.125

    /** One-way metres per reported basic unit: 4.8794. */
    public val METRES_PER_BASIC_UNIT: Double = COMMAND_STEP_METRES / COMMAND_STEP_BASIC_UNITS

    /** Half a command step: 39.036 m. The tightest honest quantisation bound. */
    public val QUANTISATION_HALF_STEP_METRES: Double = COMMAND_STEP_METRES / 2.0

    /** `CellInfo.UNAVAILABLE`. Returned by Android when the modem does not report TA. */
    public const val UNAVAILABLE: Int = Int.MAX_VALUE

    // --- GSM -------------------------------------------------------------------------------

    /** GSM timing advance is quantised to one bit period: 48/13 us = 3.6923076... us. */
    public val GSM_BIT_SECONDS: Double = 48.0 / 13.0 * 1.0e-6

    /** One-way metres per GSM TA unit: ~553.46 m. Detection of 2G fallback only. */
    public val GSM_COMMAND_STEP_METRES: Double = GSM_BIT_SECONDS * PhysicalConstants.SPEED_OF_LIGHT_MPS / 2.0

    // --- Non-line-of-sight bias ------------------------------------------------------------

    /** Lower bound of the typical outward NLOS bias, metres. */
    public const val NLOS_BIAS_MIN_METRES: Double = 50.0

    /** Upper bound of the typical outward NLOS bias, metres. */
    public const val NLOS_BIAS_MAX_METRES: Double = 150.0

    /** True when the modem actually reported a timing advance. */
    public fun isAvailable(reportedValue: Int): Boolean = reportedValue != UNAVAILABLE

    /**
     * Converts a raw Android TA reading (basic time units) to a one-way distance in metres.
     * Prefer [rangeFromBasicUnits]; this point value exists for trend maths only.
     */
    public fun distanceMetresFromBasicUnits(basicUnits: Long): Double =
        basicUnits * METRES_PER_BASIC_UNIT

    /** Converts TA command steps (multiples of 16 basic units) to one-way metres. */
    public fun distanceMetresFromCommandSteps(steps: Long): Double = steps * COMMAND_STEP_METRES

    /**
     * Quantisation-only band around a raw TA reading. Use this when the caller has an
     * independent ground truth and wants the tightest defensible interval.
     */
    public fun rangeFromBasicUnits(basicUnits: Long): RangeBand {
        val centre = distanceMetresFromBasicUnits(basicUnits)
        val half = QUANTISATION_HALF_STEP_METRES
        return RangeBand(max(0.0, centre - half), centre + half)
    }

    /**
     * The band this project ships: quantisation plus the outward NLOS bias. Deliberately
     * asymmetric, because non-line-of-sight can only ever make the path longer than the
     * geometric distance.
     */
    public fun conservativeRangeFromBasicUnits(basicUnits: Long): RangeBand {
        val centre = distanceMetresFromBasicUnits(basicUnits)
        return RangeBand(
            minMetres = max(0.0, centre - NLOS_BIAS_MIN_METRES),
            maxMetres = centre + NLOS_BIAS_MAX_METRES,
        )
    }

    /** Quantisation-only band for GSM, whose step is ~553 m. */
    public fun gsmRange(commandSteps: Long): RangeBand {
        val centre = commandSteps * GSM_COMMAND_STEP_METRES
        val half = GSM_COMMAND_STEP_METRES / 2.0
        return RangeBand(max(0.0, centre - half), centre + half)
    }
}

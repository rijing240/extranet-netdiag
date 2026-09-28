package dev.extranet.netdiag.core.ledger

import kotlin.math.log2
import kotlin.math.pow

/**
 * Shannon-Hartley channel capacity.
 *
 *     C = B * log2(1 + 10^(SINR_dB / 10))
 *
 * ## Read this before using it as a headline number
 * This is the capacity of an **entire isolated channel**. A handset does not own the channel:
 * the serving sector's scheduler grants it a share of the resource blocks, shared with every
 * other active user on that sector. A gauge showing "you are using 4% of the available pipe"
 * will therefore read roughly 4% on a perfectly healthy network, every time, because the
 * denominator is not the user's to consume.
 *
 * The ledger therefore exposes this as a **relative index** only: compare the capacity figure
 * over time for the same cell, band and hour, and compare observed throughput against a
 * per-cell baseline learned from the Capacity Atlas. It must never be rendered as an absolute
 * "how much of your connection are you wasting" dial.
 *
 * The four reference anchors below are locked by unit test and are quoted in
 * `docs/calculation-ledger.md`.
 */
public object ShannonHartley {

    /** Reference bandwidth used for the documented anchors, Hz (20 MHz LTE). */
    public const val REFERENCE_BANDWIDTH_HZ: Double = 20_000_000.0

    /**
     * Reference 20 MHz anchor capacities, bits per second, keyed by SINR in dB.
     *
     * Derived from C = 20e6 * log2(1 + 10^(SINR/10)):
     * * -5 dB: 20e6 * log2(1.31622776601683794) = 20e6 * 0.39640916116 = 7,928,183.22
     * *  0 dB: 20e6 * log2(2)                    = 20e6 * 1.0            = 20,000,000.0 (exact)
     * * 10 dB: 20e6 * log2(11)                   = 20e6 * 3.45943161864  = 69,188,632.37
     * * 20 dB: 20e6 * log2(101)                  = 20e6 * 6.65821148275  = 133,164,229.66
     *
     * `tools/verify_ledger.py` recomputes these independently and fails the build if they drift.
     * The -5 dB anchor is deliberately carried to full precision: the plan's rounded "7.9 Mbps"
     * is 28 kbps away from the true value, which is enough to matter as a regression baseline.
     */
    public const val ANCHOR_SINR_MINUS_5_DB_BPS: Double = 7_928_183.2
    public const val ANCHOR_SINR_0_DB_BPS: Double = 20_000_000.0
    public const val ANCHOR_SINR_10_DB_BPS: Double = 69_188_632.4
    public const val ANCHOR_SINR_20_DB_BPS: Double = 133_164_229.7

    /** Channel capacity in bits per second for a given bandwidth and SINR. */
    public fun capacityBitsPerSecond(bandwidthHz: Double, sinrDb: Double): Double {
        require(bandwidthHz > 0.0) { "bandwidthHz must be positive, was $bandwidthHz" }
        val sinrLinear = 10.0.pow(sinrDb / 10.0)
        return bandwidthHz * log2(1.0 + sinrLinear)
    }

    /** Convenience for the documented 20 MHz anchors. */
    public fun referenceCapacityBitsPerSecond(sinrDb: Double): Double =
        capacityBitsPerSecond(REFERENCE_BANDWIDTH_HZ, sinrDb)

    /**
     * Observed-throughput / modelled-capacity, clamped to [0, 1] on the upper end.
     *
     * Named "relative" on purpose: the result is only meaningful against that cell's own
     * baseline history, never against an abstract maximum.
     */
    public fun relativeCapacityUsage(
        observedBitsPerSecond: Double,
        capacityBitsPerSecond: Double,
    ): Double {
        require(capacityBitsPerSecond > 0.0) {
            "capacityBitsPerSecond must be positive, was $capacityBitsPerSecond"
        }
        if (observedBitsPerSecond <= 0.0) return 0.0
        return (observedBitsPerSecond / capacityBitsPerSecond).coerceAtMost(1.0)
    }
}

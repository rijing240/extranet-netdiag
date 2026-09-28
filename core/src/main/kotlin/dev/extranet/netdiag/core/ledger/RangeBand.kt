package dev.extranet.netdiag.core.ledger

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A distance interval rather than a single number.
 *
 * Timing-advance ranging must never be rendered as a pin: the reported value is quantised to
 * a command step and is biased far by non-line-of-sight propagation. Modelling it as a band
 * makes the imprecision impossible to accidentally discard downstream.
 */
public data class RangeBand(
    public val minMetres: Double,
    public val maxMetres: Double,
) {
    init {
        require(minMetres <= maxMetres) {
            "RangeBand min ($minMetres) must not exceed max ($maxMetres)"
        }
    }

    public val centreMetres: Double get() = (minMetres + maxMetres) / 2.0

    public val spanMetres: Double get() = maxMetres - minMetres

    /** Widens the band symmetrically by [extraHalfWidthMetres] on both ends. */
    public fun widened(extraHalfWidthMetres: Double): RangeBand =
        RangeBand(minMetres - extraHalfWidthMetres, maxMetres + extraHalfWidthMetres)

    override fun toString(): String {
        val lo = (minMetres * 10).roundToLong() / 10.0
        val hi = (maxMetres * 10).roundToLong() / 10.0
        return "$lo..$hi m (span ${(spanMetres * 10).roundToLong() / 10.0} m)"
    }

    public companion object {
        /** A point estimate expressed as a zero-width band, for tests and degenerate inputs. */
        public fun point(metres: Double): RangeBand = RangeBand(metres, metres)

        /** Convenience: is [value] inside the band (inclusive)? */
        public fun contains(band: RangeBand, value: Double): Boolean =
            value >= band.minMetres && value <= band.maxMetres

        /** Absolute distance between the band edges and [value]; 0 when inside. */
        public fun distanceTo(band: RangeBand, value: Double): Double = when {
            value < band.minMetres -> band.minMetres - value
            value > band.maxMetres -> value - band.maxMetres
            else -> 0.0
        }

        /** True when the two bands overlap at all. */
        public fun overlaps(a: RangeBand, b: RangeBand): Boolean =
            abs(a.centreMetres - b.centreMetres) <= (a.spanMetres + b.spanMetres) / 2.0
    }
}

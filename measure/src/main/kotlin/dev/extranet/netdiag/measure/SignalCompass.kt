package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The signal compass: which direction the user faced when the signal was best.
 *
 * The physics is honest about what it can do. Android never reveals tower coordinates, so no
 * bearing can be computed from signal alone; what the compass computes instead is a *trend*:
 * aggregating sampled RSRP by the heading the user was facing, and reporting the sector whose
 * median beat the overall median by at least the ledger's threshold. That is a metal detector's
 * beep, not a map pin, and the [verdict] says so rather than dressing it up.
 *
 * Circular directions are handled by sector index, never by averaging angles: the mean of 350
 * and 10 degrees is 0, not 180, and the sector bucket makes that error impossible.
 */
public object SignalCompass {

    /** One sector's aggregate, for display and for tests. */
    public data class Sector(
        public val index: Int,
        public val centerDegrees: Double,
        public val sampleCount: Int,
        public val medianRsrpDbm: Double?,
        /** Degrees better than the all-around median, positive when this sector beats it. */
        public val improvementDb: Double? = null,
    )

    /**
     * The compass verdict for a session's worth of samples.
     *
     * @property bestSector the sector whose median RSRP beat the overall median by the
     *   threshold, or null when no direction was meaningfully better or too few samples exist.
     * @property overallMedianDbm the all-around median while the user was sampling.
     * @property sectorCount how many of the [TimelineBudget.COMPASS_SECTORS] saw any samples.
     */
    public data class Verdict(
        public val bestSector: Sector?,
        public val overallMedianDbm: Double?,
        public val sectorCount: Int,
        public val samplesWithHeading: Int,
    ) {
        /** What the UI is allowed to claim, in plain text. */
        public fun statement(): String = when {
            samplesWithHeading < TimelineBudget.COMPASS_MIN_SECTOR_SAMPLES ->
                "Not enough samples with a heading yet. Hold the phone and turn slowly."
            bestSector == null ->
                "No direction is meaningfully better so far. The difference is under noise."
            else ->
                "Signal was best facing ${bestSector.centerDegrees.toInt()} degrees: " +
                    "${format(bestSector.improvementDb ?: 0.0)} dB better than average."
        }

        private fun format(value: Double): String =
            if (value >= 0) String.format(java.util.Locale.ROOT, "%.1f", value)
            else value.toString()
    }

    /** Sector index for a compass heading, 0-based, 0 = east, increasing clockwise. */
    public fun sectorIndex(headingDegrees: Double): Int {
        val normalized = ((headingDegrees % 360.0) + 360.0) % 360.0
        val width = 360.0 / TimelineBudget.COMPASS_SECTORS
        return (normalized / width).toInt().coerceIn(0, TimelineBudget.COMPASS_SECTORS - 1)
    }

    /** Center angle of a sector, for display. */
    public fun sectorCenterDegrees(index: Int): Double {
        val width = 360.0 / TimelineBudget.COMPASS_SECTORS
        return index * width + width / 2.0
    }

    /**
     * Aggregates [samples] into a verdict.
     *
     * Only samples with both a heading and an RSRP count; every other sample is a gap in the
     * compass's evidence, not a zero.
     */
    public fun verdict(samples: List<RadioSample>): Verdict {
        val usable = samples.mapNotNull { sample ->
            val heading = sample.headingDegrees
            val rsrp = sample.rsrpDbm
            if (heading == null || rsrp == null) null else sectorIndex(heading) to rsrp
        }
        if (usable.isEmpty()) {
            return Verdict(null, null, 0, 0)
        }

        val overallMedian = median(usable.map { it.second }.sorted())
        val bySector = usable.groupBy({ it.first }, { it.second })

        val sectors = bySector.map { (index, values) ->
            Sector(
                index = index,
                centerDegrees = sectorCenterDegrees(index),
                sampleCount = values.size,
                medianRsrpDbm = median(values.sorted()),
            )
        }

        val best = sectors
            .filter { it.sampleCount >= TimelineBudget.COMPASS_MIN_SECTOR_SAMPLES }
            .map { sector ->
                val improvement = overallMedian?.let { (it - (sector.medianRsrpDbm ?: it)) }
                // RSRP is negative dBm: higher (closer to 0) is better, so median minus
                // overall is positive exactly when the sector beats the average.
                sector.copy(improvementDb = improvement)
            }
            .filter { (it.improvementDb ?: 0.0) >= TimelineBudget.COMPASS_IMPROVEMENT_THRESHOLD_DB }
            .maxByOrNull { it.improvementDb ?: 0.0 }

        return Verdict(
            bestSector = best,
            overallMedianDbm = overallMedian,
            sectorCount = bySector.size,
            samplesWithHeading = usable.size,
        )
    }

    /** Median of an already-sorted list; the nearest-rank rule the waterfall also uses. */
    private fun median(sorted: List<Int>): Double? {
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid].toDouble()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /** Unit vector for drawing the needle, or null when there is nothing to point at. */
    public fun needle(verdict: Verdict): Pair<Double, Double>? {
        val best = verdict.bestSector ?: return null
        val radians = best.centerDegrees * PI / 180.0
        return cos(radians) to sin(radians)
    }
}

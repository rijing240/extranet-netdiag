package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.math.floor

/**
 * Counts how many times the phone has been all the way round.
 *
 * The radar says "keep turning" until the circle is measured, and says "try another spot" when
 * the readings disagree. Neither of those knows whether the user has already done a lap and a
 * half, or is on their first slow shuffle in the same forty degrees. That distinction is the
 * difference between advice that helps and advice that sends someone in circles: after a whole
 * lap with nothing to show, more of the same turning is worth trying once, and after that it is
 * not - the answer is that this room has no direction in it.
 *
 * A lap is counted from turning, not from time and not from how far the signal moved, because
 * turning is the only one of those the phone actually measures. Each new heading contributes the
 * shortest turn from the last one, so 359 to 1 degrees counts as two degrees and not as 358.
 *
 * Two properties matter more than precision here.
 *
 * **Turning back undoes a lap.** The total is a net figure, so circling once, overshooting, and
 * coming back leaves the count where it started rather than inflating it. Someone who is lost is
 * exactly the person who overshoots.
 *
 * **A gap is not a turn.** When the compass goes quiet the phone may have turned anywhere in the
 * silence. Counting the jump across it would invent a turn nobody measured - the same reason the
 * radar's own heading history refuses to draw a straight line over one - so the chain restarts
 * from whatever is now and claims nothing about the interval.
 *
 * What this does not do is refuse to count at all while the phone is still. It cannot: a phone
 * lying still reports a heading that wanders by a few tenths of a degree, and there is no
 * per-sample size that separates that from a slow deliberate turn, because a slow turn moves the
 * same tiny amount between two samples fifty times a second. The wandering adds up as a random
 * walk of a few degrees over a scan, which is nowhere near the 360 that would invent a lap.
 */
public class LapCounter(
    /** Silence longer than this between two headings breaks the chain; see [HeadingHistory]. */
    private val maxGapMillis: Long = HeadingHistory.MAX_GAP_MILLIS,
) {

    init {
        require(maxGapMillis > 0L) { "a gap must be a positive time, got $maxGapMillis" }
    }

    private var totalDegrees = 0.0
    private var lastDegrees: Double? = null
    private var lastMillis = 0L

    /**
     * Records one heading and returns the number of whole laps now counted.
     *
     * A heading that does not move forward in time is ignored, as is a NaN: both mean the
     * compass said nothing useful, which is not the same as the phone having stood still.
     */
    public fun append(timeMillis: Long, headingDegrees: Double): Int {
        if (headingDegrees.isNaN()) return laps()

        val previous = lastDegrees
        // Strictly forward in time, and close enough that the turn between the two is a real
        // measurement rather than an assumption about what happened during the silence.
        if (previous != null && timeMillis > lastMillis && timeMillis - lastMillis <= maxGapMillis) {
            totalDegrees += RadarTracker.turnDegrees(previous, headingDegrees)
        }
        lastDegrees = RadarTracker.normalize(headingDegrees)
        lastMillis = timeMillis
        return laps()
    }

    /**
     * Whole laps turned in one direction. Zero until the phone has been all the way round once.
     *
     * The slack absorbs the arithmetic, not real distance: a circle walked in exact steps adds
     * to 359.99999999999994 degrees, and a counter that reports no lap for a lap the user
     * demonstrably completed is worse than one that is a fraction of a degree generous.
     */
    public fun laps(): Int {
        if (totalDegrees <= 0.0) return 0
        return floor((totalDegrees + LAP_SLACK_DEGREES) / 360.0).toInt()
    }

    /** Degrees turned into the lap now being walked, 0 up to 360. */
    public fun degreesIntoLap(): Double {
        val into = totalDegrees % 360.0
        return if (into < 0.0) into + 360.0 else into
    }

    /** Forgets every lap, as though the phone had never turned. */
    public fun reset() {
        totalDegrees = 0.0
        lastDegrees = null
        lastMillis = 0L
    }

    public companion object {
        /**
         * The arithmetic slack on a whole lap, in degrees.
         *
         * A millionth of a degree is about three million times finer than any heading this
         * filter can produce, and enough to absorb the rounding of a few hundred additions.
         */
        public const val LAP_SLACK_DEGREES: Double = 1e-6
    }
}
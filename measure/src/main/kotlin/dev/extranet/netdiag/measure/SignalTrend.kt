package dev.extranet.netdiag.measure

/**
 * The warmer / colder trend the spec commits to: is the signal getting better or worse where
 * the user just walked?
 *
 * Radio readings jitter sample to sample, so a trend compares two halves of a smoothed window:
 * the median of the newest half against the median of the oldest half, with a threshold that
 * keeps one-second noise from reading as movement. It never claims a direction in space - only
 * that where you are now is better or worse than where you were - which is all the physics
 * allows without a magnetometer. On a phone like the A03, whose sensor list is an accelerometer
 * and nothing else, this is the instrument the signal screen can honestly run.
 */
public class SignalTrend(
    private val windowSize: Int = 10,
    private val thresholdDb: Double = 3.0,
) {

    /** What the trend is doing right now. */
    public enum class Direction { WARMER, COLDER, STEADY }

    /** One evaluation: the direction and the number behind it. */
    public data class Reading(
        public val direction: Direction,
        /** Median of the newer half minus median of the older half, in dB. Positive is better. */
        public val deltaDb: Double,
    )

    private val window = ArrayDeque<Int>()

    /** Adds one reading; nulls are gaps in the evidence, not zeros, and are skipped. */
    public fun append(rsrpDbm: Int?) {
        if (rsrpDbm == null) return
        window.addLast(rsrpDbm)
        while (window.size > windowSize) window.removeFirst()
    }

    /**
     * The trend over the window, or null until the window is full - a trend from three
     * samples is noise wearing a conclusion.
     */
    public fun evaluate(): Reading? {
        if (window.size < windowSize) return null
        val values = window.toList()
        val half = windowSize / 2
        val older = values.subList(0, half).sorted()
        val newer = values.subList(values.size - half, values.size).sorted()
        val delta = median(newer) - median(older)
        val direction = when {
            delta >= thresholdDb -> Direction.WARMER
            delta <= -thresholdDb -> Direction.COLDER
            else -> Direction.STEADY
        }
        return Reading(direction, delta)
    }

    /** Clears the window; called when a new session starts so old walks do not bleed in. */
    public fun reset(): Unit = window.clear()

    private fun median(sorted: List<Int>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid].toDouble()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

package dev.extranet.netdiag.measure

import kotlin.math.abs

/**
 * A short memory of which way the phone faced, so a reading can be matched to the heading it
 * actually belongs to.
 *
 * The radio does not report a strength at the instant it is measured. A cell's reference-signal
 * power is averaged over a few hundred milliseconds, smoothed again by the modem, and only then
 * handed to the app, so a reading that arrives "now" describes where the phone was pointing a
 * little while ago. While the phone turns at 20 degrees a second, a 0.7 second delay is a
 * 14 degree error - and every reading would be filed under the wrong direction, all of them
 * wrong the same way. The history lets the radar look back to the moment a reading describes
 * instead of using the heading of the moment it arrived.
 *
 * Headings are recorded as the sensor delivers them (about fifty a second) and read back by
 * circular interpolation: the heading between 359 and 1 degrees is 0, never 180.
 */
public class HeadingHistory(private val capacity: Int = 600) {

    init {
        require(capacity >= 4) { "a history needs room for at least four headings, got $capacity" }
    }

    /**
     * The heading over a short window.
     *
     * @property centerDegrees the heading at the middle of the window.
     * @property widthDegrees how far the phone swept across the window, never negative.
     * @property turnRateDegPerSec signed: positive is clockwise.
     */
    public data class Arc(
        public val centerDegrees: Double,
        public val widthDegrees: Double,
        public val turnRateDegPerSec: Double,
    )

    private val lock = Any()
    private val times = LongArray(capacity)
    private val headings = DoubleArray(capacity)
    private var next = 0
    private var count = 0

    /** Records one heading; a stamp that does not move forward in time is ignored. */
    public fun record(timeMillis: Long, headingDegrees: Double) {
        if (headingDegrees.isNaN()) return
        synchronized(lock) {
            if (count > 0 && timeMillis <= times[slot(count - 1)]) return
            times[next] = timeMillis
            headings[next] = RadarTracker.normalize(headingDegrees)
            next = (next + 1) % capacity
            if (count < capacity) count++
        }
    }

    /** Forgets every heading. */
    public fun clear() {
        synchronized(lock) {
            next = 0
            count = 0
        }
    }

    /** The time of the newest heading, or null when nothing has been recorded. */
    public fun latestMillis(): Long? = synchronized(lock) {
        if (count == 0) null else times[slot(count - 1)]
    }

    /**
     * The heading at [timeMillis], interpolated between the two headings either side of it.
     *
     * Null when the time falls outside what has been recorded, or when the two headings around
     * it are more than [MAX_GAP_MILLIS] apart: a stalled compass is a gap in the evidence, and
     * drawing a straight line across it would invent a turn nobody measured.
     */
    public fun headingAt(timeMillis: Long): Double? = synchronized(lock) { headingAtLocked(timeMillis) }

    /**
     * The heading across the window of [windowMillis] centred on [centerMillis], or null when
     * any part of it is missing from the history.
     */
    public fun arcAround(centerMillis: Long, windowMillis: Long): Arc? = synchronized(lock) {
        val half = windowMillis / 2
        val start = headingAtLocked(centerMillis - half) ?: return null
        val middle = headingAtLocked(centerMillis) ?: return null
        val end = headingAtLocked(centerMillis + half) ?: return null
        val seconds = windowMillis / 1_000.0
        Arc(
            centerDegrees = middle,
            widthDegrees = abs(RadarTracker.turnDegrees(start, middle)) +
                abs(RadarTracker.turnDegrees(middle, end)),
            turnRateDegPerSec = if (seconds > 0.0) RadarTracker.turnDegrees(start, end) / seconds else 0.0,
        )
    }

    private fun slot(logicalIndex: Int): Int = (next - count + logicalIndex + capacity) % capacity

    private fun headingAtLocked(timeMillis: Long): Double? {
        if (count == 0) return null
        if (timeMillis < times[slot(0)] || timeMillis > times[slot(count - 1)]) return null

        // Binary search for the newest heading at or before the requested time.
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (times[slot(mid)] <= timeMillis) low = mid else high = mid - 1
        }
        val t0 = times[slot(low)]
        val h0 = headings[slot(low)]
        if (t0 == timeMillis || low == count - 1) return h0

        val t1 = times[slot(low + 1)]
        val gap = t1 - t0
        if (gap > MAX_GAP_MILLIS) return null
        val fraction = (timeMillis - t0).toDouble() / gap
        return RadarTracker.normalize(h0 + fraction * RadarTracker.turnDegrees(h0, headings[slot(low + 1)]))
    }

    public companion object {
        /**
         * Two headings further apart than this are not neighbours: the compass went quiet.
         *
         * Four hundred milliseconds is deliberately forgiving. A phone asked for a heading at game
         * rate is usually given one fifty or a hundred times a second, but some are given far
         * less often, and a compass that reports every third of a second is still a working
         * compass. Treating its samples as a gap would throw away readings that are perfectly
         * good, and the radar would show an empty circle on a phone whose compass is fine.
         */
        public const val MAX_GAP_MILLIS: Long = 400L
    }
}

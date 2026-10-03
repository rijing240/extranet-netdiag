package dev.extranet.netdiag.measure

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the walker has been, as a trail of timed positions, so a radio reading can be filed
 * against the place it was measured.
 *
 * The Room Map's whole problem is the same one the radar has, in two dimensions: the radio does
 * not report a strength at the instant it is measured. A reading that arrives "now" describes
 * where the phone was a moment ago - the cell's reference power is averaged over a few hundred
 * milliseconds before the modem hands it over, and Wi-Fi RSSI is read from a polled value that
 * has already been sitting in the driver. Someone walking at a metre a second moves most of a
 * metre in that time, which is the difference between the router's side of the room and the
 * doorway. So positions are recorded as they happen and read back at the moment a reading
 * describes, exactly as [HeadingHistory] does for headings.
 *
 * Position is advanced one **step** at a time, and only by a step. The obvious alternative -
 * integrating the accelerometer twice - is the standard way to build a drift of several metres
 * in under a minute, because the constant error in an accelerometer's bias becomes a growing
 * velocity and then a growing position. A step is a discrete, countable event that does not
 * integrate anything, and a fixed step length is wrong by a few centimetres per step whether the
 * user walks quickly or slowly. That is a much smaller lie, and it is a lie that stays put.
 *
 * The cost of that choice is that the trail is *approximate* and gets steadily more so: every
 * turn is measured a little wrong, every step length is a little wrong, and the errors
 * accumulate. The screen says so rather than drawing a floor plan.
 *
 * Positions are meters in the walker's own frame: the origin is where they tapped Start, and the
 * axes are whatever direction they were facing when the first step was taken. That frame is not
 * north and is not the room, which is why nothing here is ever labelled with a compass point.
 */
public class PositionHistory(private val capacity: Int = CAPACITY) {

    init {
        require(capacity >= 4) { "a trail needs room for at least four positions, got $capacity" }
    }

    /** One place the walker stood, and which way they faced there. */
    public data class Fix(
        public val xMeters: Double,
        public val yMeters: Double,
        /** The walker's facing in their own frame, degrees clockwise from the first step. */
        public val headingDegrees: Double,
    )

    private val lock = Any()
    private val times = LongArray(capacity)
    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val headings = DoubleArray(capacity)
    private var next = 0
    private var count = 0

    /** How many positions are remembered. */
    public val size: Int
        get() = synchronized(lock) { count }

    /**
     * Starts the trail at the origin, facing [headingDegrees].
     *
     * The first position is recorded explicitly rather than left to the first step, because the
     * user is standing somewhere before they take one, and a reading taken while they stood still
     * belongs to the origin.
     */
    public fun start(timeMillis: Long, headingDegrees: Double): Fix = synchronized(lock) {
        next = 0
        count = 0
        val fix = Fix(0.0, 0.0, RadarTracker.normalize(headingDegrees))
        recordLocked(timeMillis, fix)
        fix
    }

    /**
     * Advances one step along [headingDegrees] and records the new position.
     *
     * Returns null when no trail has been started, because a step taken before the origin is
     * known has nowhere to go. A heading that is not a number is treated the same way: the walker
     * may know where they are without knowing which way they turned, and inventing a direction
     * would put the next dot somewhere they have never been.
     */
    public fun step(
        timeMillis: Long,
        headingDegrees: Double,
        stepLengthMeters: Double = DEFAULT_STEP_METERS,
    ): Fix? = synchronized(lock) {
        if (count == 0) return null
        if (headingDegrees.isNaN() || stepLengthMeters <= 0.0 || stepLengthMeters.isNaN()) return null
        val last = count - 1
        val radians = Math.toRadians(headingDegrees)
        val fix = Fix(
            xMeters = xs[slot(last)] + stepLengthMeters * sin(radians),
            yMeters = ys[slot(last)] + stepLengthMeters * cos(radians),
            headingDegrees = RadarTracker.normalize(headingDegrees),
        )
        recordLocked(timeMillis, fix)
        fix
    }

    /** The newest position, or null when nothing has been recorded. */
    public fun latest(): Fix? = synchronized(lock) {
        if (count == 0) null else fixAt(count - 1)
    }

    /** The time of the newest position, or null when nothing has been recorded. */
    public fun latestMillis(): Long? = synchronized(lock) {
        if (count == 0) null else times[slot(count - 1)]
    }

    /**
     * Where the walker was at [timeMillis], interpolated between the two positions either side of
     * it, or null when it cannot be said.
     *
     * Null for a time outside what has been recorded, and - more importantly - for a time that
     * falls inside a stretch longer than [MAX_GAP_MILLIS] with no step in it. Someone who has
     * stood still for five seconds has not been walking in a straight line across the room, and
     * drawing one between the two ends of that pause would put every reading taken during it
     * somewhere they never stood. "I do not know" is a usable answer; the caller files the reading
     * at the last known place and marks it as doubtful.
     */
    public fun at(timeMillis: Long): Fix? = synchronized(lock) {
        if (count == 0) return null
        if (timeMillis < times[slot(0)] || timeMillis > times[slot(count - 1)]) return null

        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (times[slot(mid)] <= timeMillis) low = mid else high = mid - 1
        }
        if (low == count - 1) return fixAt(low)

        val t0 = times[slot(low)]
        if (t0 == timeMillis) return fixAt(low)
        val t1 = times[slot(low + 1)]
        if (t1 - t0 > MAX_GAP_MILLIS) return null

        val fraction = (timeMillis - t0).toDouble() / (t1 - t0)
        val before = fixAt(low)
        return Fix(
            xMeters = before.xMeters + fraction * (xs[slot(low + 1)] - before.xMeters),
            yMeters = before.yMeters + fraction * (ys[slot(low + 1)] - before.yMeters),
            // A heading is a direction, not a quantity, so it is carried across the gap rather
            // than averaged through it: between two nearby headings the short way is right, and
            // between two far-apart ones the short way is at least never the opposite.
            headingDegrees = RadarTracker.normalize(
                before.headingDegrees + fraction * RadarTracker.turnDegrees(before.headingDegrees, headings[slot(low + 1)]),
            ),
        )
    }

    /** Forgets the whole trail, as though the walk had never started. */
    public fun clear() {
        synchronized(lock) {
            next = 0
            count = 0
        }
    }

    /**
     * The whole trail, oldest first, for drawing.
     *
     * Copied out rather than exposed in place: the sensor thread keeps writing while the Canvas
     * reads, and a frame drawn from a buffer that changed halfway through would show a trail that
     * never existed.
     */
    public fun trail(): List<Pair<Long, Fix>> = synchronized(lock) {
        val out = ArrayList<Pair<Long, Fix>>(count)
        for (i in 0 until count) out.add(times[slot(i)] to fixAt(i))
        out
    }

    private fun recordLocked(timeMillis: Long, fix: Fix) {
        if (count > 0 && timeMillis <= times[slot(count - 1)]) return
        times[next] = timeMillis
        xs[next] = fix.xMeters
        ys[next] = fix.yMeters
        headings[next] = fix.headingDegrees
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    private fun slot(logicalIndex: Int): Int = (next - count + logicalIndex + capacity) % capacity

    private fun fixAt(logicalIndex: Int): Fix {
        val i = slot(logicalIndex)
        return Fix(xs[i], ys[i], headings[i])
    }

    public companion object {
        /**
         * How long a pause between two positions may last before the trail refuses to guess.
         *
         * A person standing still takes no steps, and the longer they stand the less the phone
         * knows about whether they shuffled sideways. At a normal walking pace a step lands about
         * every half second, so a second and a half is three missed steps - long enough that a
         * slow walker is not treated as stopped, short enough that a real pause is.
         */
        public const val MAX_GAP_MILLIS: Long = 1_500L

        /**
         * The default step length, in meters.
         *
         * Three quarters of a metre is the usual figure for an adult on flat ground, and it is
         * wrong by perhaps ten centimetres either way depending on height and pace. It is kept
         * here as one number rather than learned from the sensors, because every scheme that
         * estimates step length from an accelerometer does so with an error larger than the thing
         * it is trying to correct.
         */
        public const val DEFAULT_STEP_METERS: Double = 0.7

        /** Positions kept for drawing. See `RoomMapModel.MAX_DOTS`, which caps what is drawn. */
        public const val CAPACITY: Int = 600
    }
}
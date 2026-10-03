package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The Room Map in words: which way, how far, and what the walk found.
 *
 * Direction is given in the walker's own frame - ahead, behind, left, right - and never as a
 * compass point unless the phone has a magnetometer it can trust. That is not a stylistic choice:
 * the map is drawn in the frame the walker started in, so "toward the top-right of the picture"
 * and "ahead and to your right" are the same instruction and both are checkable by the person
 * holding the phone. "North-east" is neither, unless the phone really knows where north is.
 *
 * The words are all relative to the direction the user faced when they tapped Start, which is the
 * one direction they can still see when they read the result.
 */
public object RoomMapWords {

    /** Eight directions, clockwise from straight ahead. */
    private val AHEAD_POINTS = listOf(
        "straight ahead",
        "ahead and to the right",
        "to your right",
        "behind you and to the right",
        "behind you",
        "behind you and to the left",
        "to your left",
        "ahead and to the left",
    )

    /** Within this many meters of the origin, a spot is described as near where the user started. */
    public const val NEAR_METERS: Double = 1.5

    /**
     * Which way a bearing points, in the walker's frame.
     *
     * [bearingDegrees] is clockwise from the direction the user faced at Start, so zero is
     * straight ahead and 90 is to their right.
     */
    public fun direction(bearingDegrees: Double): String {
        val index = ((RadarTracker.normalize(bearingDegrees) / 45.0).roundToInt()) % AHEAD_POINTS.size
        return AHEAD_POINTS[index]
    }

    /** How far a spot is, in words and to a sensible precision. */
    public fun distance(meters: Double): String = when {
        meters < NEAR_METERS -> "near where you started"
        meters < 10.0 -> "about ${(meters).roundToInt()} m away"
        else -> "about ${(meters / 5.0).roundToInt() * 5} m away"
    }

    /**
     * The one-line caption under the live map.
     *
     * It says how much has been measured and, once there is enough to compare, which way the
     * stronger readings lie. Before that it says plainly that there is nothing to compare yet,
     * because an empty map with no caption reads as a broken map.
     */
    public fun caption(snapshot: RoomMapModel.Snapshot, labelsHidden: Boolean): String {
        val count = snapshot.dots.size
        return when {
            count == 0 -> "Walk slowly around the room to start the map"
            count < RoomMapModel.MIN_DOTS_FOR_COLOUR ->
                "$count readings · keep walking"
            snapshot.neutral ->
                "$count readings · signal is about the same everywhere"
            snapshot.best == null -> "$count readings · keep walking"
            else -> {
                val bearing = bearingOf(snapshot.best)
                "$count readings · stronger ${direction(bearing)}"
            }
        }.let { line ->
            // Only mention the frame when the phone cannot name one. Saying it every time would
            // turn a working compass into a disclaimer nobody reads.
            if (labelsHidden) "$line · relative to where you started" else line
        }
    }

    /**
     * The result, as the sentence the user reads when the walk ends.
     *
     * The note about drift is part of the result rather than a footnote, because the number of
     * steps taken is the user's own evidence for how much to trust it.
     */
    public fun summary(snapshot: RoomMapModel.Snapshot, steps: Int): String {
        if (snapshot.dots.size < RoomMapModel.MIN_DOTS_FOR_COLOUR) {
            return "Not enough of the room was walked to compare one place with another. " +
                "Try again and take a few more steps."
        }
        if (snapshot.neutral) {
            return "Signal was about the same everywhere you walked: no place was better than " +
                "another by more than a rounding error. Positions are approximate."
        }
        val best = snapshot.best
        val weakest = snapshot.weakest
        if (best == null || weakest == null) {
            return "Not enough of the room was walked to compare one place with another."
        }
        val bestWhere = direction(bearingOf(best))
        val weakestWhere = direction(bearingOf(weakest))
        return buildString {
            append("Best signal: ")
            append(bestWhere)
            append(", ")
            append(distance(hypot(best.dot.xMeters, best.dot.yMeters)))
            append(". Weakest: ")
            append(weakestWhere)
            append(", ")
            append(distance(hypot(weakest.dot.xMeters, weakest.dot.yMeters)))
            append(". Walked about ")
            append(steps)
            append(" steps; positions are approximate and get less accurate the longer you walk.")
        }
    }

    /**
     * The bearing in the walker's frame at which a dot lies.
     *
     * Stored positions are already in the frame the map is drawn in - its up is the way the user
     * faced at Start - so this is the same rotation the layout applies to the picture, stated as
     * an angle. Atan2 with x first and y second gives an angle clockwise from up, which is the
     * convention every other bearing in this app uses.
     */
    private fun bearingOf(placed: RoomMapModel.Placed): Double =
        RadarTracker.normalize(Math.toDegrees(atan2(placed.dot.xMeters, placed.dot.yMeters)))

    /** True when the trail has gone far enough that its own drift is worth mentioning. */
    public fun driftWorthMentioning(snapshot: RoomMapModel.Snapshot): Boolean {
        val bounds = snapshot.bounds ?: return false
        val span = maxOf(abs(bounds.maxX - bounds.minX), abs(bounds.maxY - bounds.minY))
        return span >= DRIFT_SPAN_METERS
    }

    /** Past roughly a room's width, the accumulated drift is a real part of the picture. */
    public const val DRIFT_SPAN_METERS: Double = 8.0
}
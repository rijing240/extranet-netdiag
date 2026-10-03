package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Which way the phone faces, read out of a rotation matrix.
 *
 * A rotation matrix from the platform says where the phone's three axes point in the world: the
 * first row is how much of each axis points east, the second how much points north, and the third
 * how much points up. Which of those three axes stands for "the way I am facing" depends on how
 * the phone is being held, and getting that choice wrong is not a rounding error - it is a hundred
 * and eighty degrees, the answer pointing at the user's back.
 *
 * Two poses matter, because they are the two ways a person holds a phone to scan a room:
 *
 * - **Flat**, lying in the hand or on a table, screen up. The axis to read is the top of the
 *   phone, its +Y: pointing the phone at something means pointing its top edge at it.
 * - **Upright**, held up in front of the chest, screen tilted back towards the face. The top of
 *   the phone now points at the ceiling, so it says nothing about direction; the axis to read is
 *   the **back of the phone**, its -Z, which is the way the user is looking through the phone.
 *
 * The trap is that the phone's +Z - out of the screen - points at the user's *face* in this pose,
 * the exact opposite of the way they are facing. An implementation that reads +Z because it is
 * the screen's normal, or because a remap of the matrix happens to hand it back, reports the
 * direction the user's own face points: their heading plus 180 degrees. Every compass word, every
 * "turn right", and the north badge on the dial are then the wrong way round, while everything
 * measured as a *difference* between two headings - which is what the radar actually fits - stays
 * perfectly good. That is why this is one function with its own tests at known poses, and not a
 * line buried in the sensor listener where a sign cannot be checked.
 *
 * The two poses agree where they must: a phone tilted back towards the face, top edge away from
 * the user, has its top and its back pointing the same way along the ground. So a user raising
 * the phone from flat to upright does not see the dial spin half a turn as it crosses
 * [FLAT_THRESHOLD], and a scan does not lose its bearing to a lurch.
 *
 * Headings are degrees clockwise from north in the label frame the matrix is expressed in - true
 * north when the caller has corrected for declination, magnetic north otherwise - 0 up to 360.
 */
public object HeadingAxes {

    /**
     * The tilt at which a phone counts as upright rather than flat: the up component of the
     * screen's own axis, so 0.6 is about 53 degrees from horizontal.
     *
     * Beyond this the top of the phone is too steep to project onto the ground with any
     * resolution - at exactly vertical its projection is a point and its direction is undefined -
     * and the back of the phone takes over, which is the axis that is horizontal in that pose.
     */
    public const val FLAT_THRESHOLD: Double = 0.6

    /** True when the phone is held flat enough for its top edge to be the way it faces. */
    public fun isFlat(matrix: FloatArray): Boolean = abs(matrix[8].toDouble()) >= FLAT_THRESHOLD

    /**
     * The bearing the phone faces, in degrees clockwise from north, 0 up to 360.
     *
     * [matrix] is the nine values of a rotation matrix as the platform delivers them: elements
     * 0 to 2 are the east components of the device's X, Y and Z axes, 3 to 5 the north components,
     * 6 to 8 the up components. The bearing is the direction of the chosen axis projected onto the
     * ground, which is why the up components are never read here: the phone's tilt cannot change
     * the direction it faces, and reading a vertical axis is exactly how that goes wrong.
     */
    public fun facingDegrees(matrix: FloatArray): Double {
        require(matrix.size >= 9) { "a rotation matrix has nine values, got ${matrix.size}" }
        return if (isFlat(matrix)) {
            bearingOf(east = matrix[1], north = matrix[4])
        } else {
            // The back of the phone: the screen's own axis, reversed. See the class notes for what
            // reading it the other way round costs.
            bearingOf(east = -matrix[2], north = -matrix[5])
        }
    }

    /** The bearing of a horizontal vector, or north when the vector is too short to have one. */
    private fun bearingOf(east: Float, north: Float): Double {
        val eastward = east.toDouble()
        val northward = north.toDouble()
        // A vertical axis has no direction along the ground. That cannot happen for the axis this
        // object selects - a flat phone's top is horizontal by definition of flat, and an upright
        // phone's back is horizontal by the same argument - but a phone held at ninety degrees to
        // gravity's idea of down, or a matrix that is not a rotation at all, could still get here,
        // and north is the least surprising answer.
        if (abs(eastward) < 1e-6 && abs(northward) < 1e-6) return 0.0
        return RadarTracker.normalize(Math.toDegrees(atan2(eastward, northward)))
    }
}

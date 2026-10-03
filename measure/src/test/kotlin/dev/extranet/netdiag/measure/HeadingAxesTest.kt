package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which way the phone is said to face, at poses that can be built by hand.
 *
 * The tests are written as poses rather than as numbers because that is the only way to check a
 * convention. A pose is three unit vectors - the phone's right, its top, and the direction out of
 * its screen - each given as east, north and up. The rotation matrix is those three written out,
 * and the expected heading is read off the pose in words: "the user is facing east", "the top of
 * the phone points north".
 *
 * The one that matters most is [uprightWithTheScreenTowardsTheFaceReadsTheWayTheUserFaces]. The
 * phone's screen axis points at the user in that pose, so a heading built from it is the user's
 * own direction plus a hundred and eighty degrees, and everything the dial says is then backwards
 * - while the radar's relative measurements stay correct, which is exactly what makes the mistake
 * so hard to notice from the outside.
 */
class HeadingAxesTest {

    private data class Axis(val east: Double, val north: Double, val up: Double)

    /** The rotation matrix for a pose: the phone's right, top and screen axes in the world. */
    private fun matrixOf(right: Axis, top: Axis, out: Axis): FloatArray = floatArrayOf(
        right.east.toFloat(), top.east.toFloat(), out.east.toFloat(),
        right.north.toFloat(), top.north.toFloat(), out.north.toFloat(),
        right.up.toFloat(), top.up.toFloat(), out.up.toFloat(),
    )

    /** Phone flat, screen up, its top edge pointing [heading]; right is derived to keep it solid. */
    private fun flatPose(heading: Double): FloatArray {
        val radians = Math.toRadians(heading)
        val top = Axis(east = sin(radians), north = cos(radians), up = 0.0)
        val out = Axis(east = 0.0, north = 0.0, up = 1.0)
        // Right is top crossed into out, so the three axes stay a right-handed frame like a real
        // phone's.
        val right = Axis(
            east = top.north * out.up - top.up * out.north,
            north = top.up * out.east - top.east * out.up,
            up = top.east * out.north - top.north * out.east,
        )
        return matrixOf(right, top, out)
    }

    /**
     * Phone upright, screen tilted back towards the user's face, the user facing [heading].
     *
     * [fromTheGround] is how far the phone has been raised from flat: zero is a phone lying down
     * with its top away from the user, ninety is held straight up. The screen tips back towards
     * the face as the phone comes up, which is how it is actually held.
     */
    private fun uprightPose(heading: Double, fromTheGround: Double = 90.0): FloatArray {
        val headingRadians = Math.toRadians(heading)
        // Past ninety the phone is tipped forward, the screen leaning away from the user; the
        // geometry is the same, so no special case is needed for it.
        val tilt = Math.toRadians(fromTheGround)
        // The top of the phone, leaning up and away from the user.
        val top = Axis(
            east = cos(tilt) * sin(headingRadians),
            north = cos(tilt) * cos(headingRadians),
            up = sin(tilt),
        )
        // Out of the screen: back towards the user's face, which is the reverse of the way the
        // user faces, tilted up as the phone comes off the ground.
        val out = Axis(
            east = -sin(tilt) * sin(headingRadians),
            north = -sin(tilt) * cos(headingRadians),
            up = cos(tilt),
        )
        val right = Axis(
            east = top.north * out.up - top.up * out.north,
            north = top.up * out.east - top.east * out.up,
            up = top.east * out.north - top.north * out.east,
        )
        return matrixOf(right, top, out)
    }

    @Test
    fun flatWithTheTopPointingNorthReadsNorth() {
        assertEquals(0.0, HeadingAxes.facingDegrees(flatPose(0.0)), absoluteTolerance = 0.01)
        assertTrue(HeadingAxes.isFlat(flatPose(0.0)))
    }

    @Test
    fun flatAndTurnedToTheRightReadsLargerBearings() {
        // Putting the phone's top edge on east is a turn to the right from north, and the heading
        // has to grow with it, or every "turn right" the screen says would be backwards.
        assertEquals(90.0, HeadingAxes.facingDegrees(flatPose(90.0)), absoluteTolerance = 0.01)
        assertEquals(270.0, HeadingAxes.facingDegrees(flatPose(270.0)), absoluteTolerance = 0.01)
        assertTrue(HeadingAxes.isFlat(flatPose(90.0)))
    }

    @Test
    fun uprightWithTheScreenTowardsTheFaceReadsTheWayTheUserFaces() {
        // The user faces east and holds the phone up in front of them, screen towards their face.
        // The phone's screen axis points west, back at them, and reading that axis - which is what
        // a heading built from the screen's own normal does - would report 270: due west, the
        // exact reverse of the truth. The back of the phone points east and is the axis that
        // counts.
        val pose = uprightPose(heading = 90.0)
        assertFalse(HeadingAxes.isFlat(pose))
        assertEquals(90.0, HeadingAxes.facingDegrees(pose), absoluteTolerance = 0.01)
        // And the screen axis really does say the opposite, so the test above is not passing by
        // accident of the pose being symmetric.
        val screenAxis = RadarTracker.normalize(
            Math.toDegrees(atan2(pose[2].toDouble(), pose[5].toDouble())),
        )
        assertEquals(270.0, screenAxis, absoluteTolerance = 0.01)
    }

    @Test
    fun uprightFacingEveryDirectionReadsThatDirection() {
        for (heading in 0 until 360 step 15) {
            val read = HeadingAxes.facingDegrees(uprightPose(heading = heading.toDouble()))
            assertTrue(
                abs(RadarTracker.turnDegrees(read, heading.toDouble())) < 0.05,
                "a user facing $heading was read as $read",
            )
        }
    }

    @Test
    fun raisingThePhoneFromFlatToUprightDoesNotSpinTheDial() {
        // The pose a user actually adopts: the phone comes up off the table in front of them
        // without the user turning. Crossing the flat threshold must not move the heading, or the
        // dial lurches half a turn for no reason and a scan in progress loses its bearing.
        val poses = listOf(0.0, 30.0, 50.0, 53.0, 54.0, 60.0, 75.0, 90.0)
        val headings = poses.map { HeadingAxes.facingDegrees(uprightPose(heading = 43.0, fromTheGround = it)) }
        val first = headings.first()
        for ((index, heading) in headings.withIndex()) {
            assertTrue(
                abs(RadarTracker.turnDegrees(first, heading)) < 1.0,
                "at ${poses[index]} degrees from the ground the heading was $heading, not $first",
            )
            assertTrue(
                abs(RadarTracker.turnDegrees(heading, 43.0)) < 1.0,
                "at ${poses[index]} degrees from the ground the heading was $heading, not 43",
            )
        }
        // And the threshold really is crossed inside that sweep, so the test is testing the
        // switch rather than one branch of it.
        assertTrue(poses.any { HeadingAxes.isFlat(uprightPose(heading = 43.0, fromTheGround = it)) })
        assertTrue(poses.any { !HeadingAxes.isFlat(uprightPose(heading = 43.0, fromTheGround = it)) })
    }

    @Test
    fun aPhoneHeldPastVerticalStillReadsSensibly() {
        // Held slightly past upright - the screen tipped forward, the way it is when the user
        // leans over it - and the back of the phone is still the axis that points where they look.
        val read = HeadingAxes.facingDegrees(uprightPose(heading = 200.0, fromTheGround = 100.0))
        assertTrue(abs(RadarTracker.turnDegrees(read, 200.0)) < 10.0, "read $read")
    }

    @Test
    fun turningRightAlwaysIncreasesTheHeadingHoweverThePhoneIsHeld() {
        // The radar fits a signal against heading, so a convention that reverses the *direction* of
        // turn would mirror every answer. Both poses have to agree on which way is clockwise.
        val flat = listOf(0.0, 40.0, 80.0).map { HeadingAxes.facingDegrees(flatPose(it)) }
        val upright = listOf(0.0, 40.0, 80.0).map { HeadingAxes.facingDegrees(uprightPose(heading = it)) }
        for (sequence in listOf(flat, upright)) {
            val steps = sequence.zipWithNext { first, second -> RadarTracker.turnDegrees(first, second) }
            assertTrue(steps.all { it > 0.0 }, "a turn to the right read as $steps")
        }
    }

    @Test
    fun aMatrixThatIsNotARotationIsRefusedRatherThanGuessedAt() {
        var refused = false
        try {
            HeadingAxes.facingDegrees(floatArrayOf(1f, 0f, 0f))
        } catch (expected: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }
}

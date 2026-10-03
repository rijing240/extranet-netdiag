package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionHistoryTest {

    @Test
    fun theTrailStartsWhereTheUserTappedStart() {
        val trail = PositionHistory()

        val fix = trail.start(1_000L, headingDegrees = 0.0)

        assertEquals(0.0, fix.xMeters, absoluteTolerance = 0.001)
        assertEquals(0.0, fix.yMeters, absoluteTolerance = 0.001)
    }

    /** Walking north is along +y, and walking east is along +x. */
    @Test
    fun aStepMovesTheWalkersStepLengthInTheDirectionTheyFace() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)

        trail.step(500L, headingDegrees = 0.0, stepLengthMeters = 1.0)
        val north = trail.latest()!!
        assertEquals(0.0, north.xMeters, absoluteTolerance = 0.001)
        assertEquals(1.0, north.yMeters, absoluteTolerance = 0.001)

        trail.clear()
        trail.start(0L, headingDegrees = 90.0)
        trail.step(500L, headingDegrees = 90.0, stepLengthMeters = 1.0)
        val east = trail.latest()!!
        assertEquals(1.0, east.xMeters, absoluteTolerance = 0.001)
        assertEquals(0.0, east.yMeters, absoluteTolerance = 0.001)
    }

    /** Four steps of half a metre along each side of a square must land where they started. */
    @Test
    fun stepsInAClosedSquareComeBackToTheStart() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)
        var time = 100L
        for (heading in listOf(0.0, 90.0, 180.0, 270.0)) {
            repeat(4) {
                trail.step(time, headingDegrees = heading, stepLengthMeters = 0.5)
                time += 100L
            }
        }

        val end = trail.latest()!!
        assertEquals(0.0, end.xMeters, absoluteTolerance = 0.001)
        assertEquals(0.0, end.yMeters, absoluteTolerance = 0.001)
    }

    @Test
    fun aTimeBetweenTwoStepsIsInterpolated() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)
        trail.step(1_000L, headingDegrees = 0.0, stepLengthMeters = 2.0)

        val middle = trail.at(500L)!!

        assertEquals(1.0, middle.yMeters, absoluteTolerance = 0.001)
    }

    /** Standing still for longer than the gap is not a walked straight line. */
    @Test
    fun aPauseLongerThanTheGapCannotBeGuessedAt() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)
        trail.step(20_000L, headingDegrees = 90.0, stepLengthMeters = 5.0)

        val inside = trail.at(10_000L)

        assertNull(inside)
        assertEquals(PositionHistory.MAX_GAP_MILLIS, 1_500L)
    }

    @Test
    fun aPauseInsideTheGapIsStillInterpolated() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)
        trail.step(1_000L, headingDegrees = 0.0, stepLengthMeters = 2.0)

        assertNotNull(trail.at(1_000L - 100L))
    }

    @Test
    fun aTimeBeforeTheTrailOrAfterItHasNoAnswer() {
        val trail = PositionHistory()
        trail.start(1_000L, headingDegrees = 0.0)

        assertNull(trail.at(999L))
        assertNull(trail.at(1_001L))
    }

    @Test
    fun aStepBeforeTheOriginHasNowhereToGo() {
        val trail = PositionHistory()

        assertNull(trail.step(0L, headingDegrees = 0.0))
        assertNull(trail.latest())
    }

    @Test
    fun aHeadingThatIsNotANumberDoesNotMoveTheWalker() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)

        assertNull(trail.step(500L, headingDegrees = Double.NaN))

        val latest = trail.latest()!!
        assertEquals(0.0, latest.xMeters, absoluteTolerance = 0.001)
        assertEquals(0.0, latest.yMeters, absoluteTolerance = 0.001)
    }

    /** A long walk keeps only the newest positions, and keeps them in order. */
    @Test
    fun onlyTheNewestPositionsAreKept() {
        val trail = PositionHistory(capacity = 8)
        trail.start(0L, headingDegrees = 0.0)
        for (i in 1..50) trail.step(i * 500L, headingDegrees = 0.0)

        assertEquals(8, trail.size)
        assertEquals(50 * 500L, trail.latestMillis())
    }

    /** The trail is read while the sensors write; the copy must be a consistent snapshot. */
    @Test
    fun theTrailIsReturnedOldestFirst() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)
        trail.step(500L, headingDegrees = 0.0)
        trail.step(1_000L, headingDegrees = 0.0)

        val trailPoints = trail.trail()

        assertEquals(3, trailPoints.size)
        assertEquals(0L, trailPoints.first().first)
        assertEquals(1_000L, trailPoints.last().first)
        assertTrue(trailPoints[1].second.yMeters < trailPoints[2].second.yMeters)
    }

    /** Roughly a four-metre walk north: the case the Room Map is actually built for. */
    @Test
    fun aWalkAcrossARoomLandsAboutWhereTheStepsAddUp() {
        val trail = PositionHistory()
        trail.start(0L, headingDegrees = 0.0)
        var time = 500L
        repeat(6) {
            trail.step(time, headingDegrees = 0.0)
            time += 500L
        }

        val end = trail.latest()!!
        assertEquals(6 * PositionHistory.DEFAULT_STEP_METERS, end.yMeters, absoluteTolerance = 0.001)
        assertEquals(0.0, end.xMeters, absoluteTolerance = 0.001)
    }
}
package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.random.Random

class LapCounterTest {

    /** Feeds a turn at the rate a rotation vector really delivers, about fifty a second. */
    private fun turn(counter: LapCounter, fromDegrees: Double, toDegrees: Double, stepMillis: Long = 20L) {
        val steps = 400
        var time = 0L
        for (i in 0..steps) {
            val heading = fromDegrees + (toDegrees - fromDegrees) * i / steps
            counter.append(time, heading)
            time += stepMillis
        }
    }

    @Test
    fun aFullCircleIsOneLap() {
        val counter = LapCounter()

        turn(counter, 0.0, 360.0)

        assertEquals(1, counter.laps())
    }

    @Test
    fun twoCirclesAreTwoLaps() {
        val counter = LapCounter()

        turn(counter, 0.0, 360.0)
        turn(counter, 0.0, 360.0)

        assertEquals(2, counter.laps())
    }

    @Test
    fun aPartCircleIsNoLapAtAll() {
        val counter = LapCounter()

        turn(counter, 0.0, 200.0)

        assertEquals(0, counter.laps())
        assertEquals(200.0, counter.degreesIntoLap(), absoluteTolerance = 3.0)
    }

    /** A phone lying still reports a heading that wanders. Thousands of samples must not add up. */
    @Test
    fun aPhoneThatIsNotTurningNeverFinishesALap() {
        val counter = LapCounter()
        val random = Random(20261002)
        var time = 0L

        repeat(20_000) {
            counter.append(time, 120.0 + random.nextDouble(-0.4, 0.4))
            time += 20L
        }

        assertEquals(0, counter.laps())
    }

    /** The compass going quiet is not a turn, however far the phone moved while it did. */
    @Test
    fun aGapInTheCompassIsNotCountedAsTurning() {
        val counter = LapCounter()

        counter.append(0L, 0.0)
        counter.append(20L, 5.0)
        // Five seconds of silence, during which the user completes the circle.
        counter.append(5_000L, 360.0)

        assertEquals(0, counter.laps())
    }

    @Test
    fun turningBackUndoesALapRatherThanAddingOne() {
        val counter = LapCounter()

        turn(counter, 0.0, 360.0)
        assertEquals(1, counter.laps())

        turn(counter, 0.0, -360.0)
        assertEquals(0, counter.laps())
    }

    @Test
    fun resetForgetsEveryLap() {
        val counter = LapCounter()
        turn(counter, 0.0, 360.0)

        counter.reset()

        assertEquals(0, counter.laps())
        assertEquals(0.0, counter.degreesIntoLap(), absoluteTolerance = 0.001)
    }

    @Test
    fun aHeadingThatDoesNotMoveForwardInTimeIsIgnored() {
        val counter = LapCounter()
        counter.append(1_000L, 0.0)

        counter.append(1_000L, 90.0)
        counter.append(1_000L, 180.0)

        assertEquals(0, counter.laps())
    }

    /** 359 to 1 is two degrees the short way, not 358 the long way. */
    @Test
    fun turningAcrossNorthCountsTheShortWay() {
        val counter = LapCounter()

        turn(counter, 340.0, 380.0)

        assertEquals(40.0, counter.degreesIntoLap(), absoluteTolerance = 3.0)
    }
}
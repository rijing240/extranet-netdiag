package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the heading memory the radar looks readings up in: interpolation takes the short way
 * round the circle, a stalled compass is a gap rather than a straight line, and the arc over a
 * window reports where the phone was, how far it swept and which way it was turning.
 */
class HeadingHistoryTest {

    /** A phone turning at [degPerSec] from [start], with a heading every 20 ms for [seconds]. */
    private fun turning(degPerSec: Double, start: Double = 0.0, seconds: Double = 3.0): HeadingHistory {
        val history = HeadingHistory()
        var t = 0L
        while (t <= (seconds * 1000).toLong()) {
            history.record(t, start + degPerSec * t / 1000.0)
            t += 20L
        }
        return history
    }

    @Test
    fun interpolationCrossesNorthTheShortWay() {
        val history = HeadingHistory()
        history.record(0L, 350.0)
        history.record(100L, 10.0)
        // Halfway between 350 and 10 is 0, not 180.
        assertEquals(0.0, assertNotNull(history.headingAt(50L)), 1e-9)
        assertEquals(355.0, assertNotNull(history.headingAt(25L)), 1e-9)
    }

    @Test
    fun aTimeOutsideTheHistoryHasNoHeading() {
        val history = HeadingHistory()
        history.record(1_000L, 90.0)
        history.record(1_020L, 91.0)
        assertNull(history.headingAt(999L))
        assertNull(history.headingAt(1_021L))
        assertEquals(90.0, assertNotNull(history.headingAt(1_000L)), 1e-9)
    }

    @Test
    fun aStalledCompassIsAGapNotAStraightLine() {
        val history = HeadingHistory()
        history.record(0L, 10.0)
        history.record(1_000L, 200.0)
        assertNull(history.headingAt(500L))
    }

    @Test
    fun anArcReportsCentreWidthAndRate() {
        val history = turning(degPerSec = 30.0)
        val arc = assertNotNull(history.arcAround(centerMillis = 1_500L, windowMillis = 800L))
        // At 30 degrees a second the phone faced 45 degrees at 1.5 s, and swept 24 degrees in 0.8 s.
        assertEquals(45.0, arc.centerDegrees, 1e-6)
        assertEquals(24.0, arc.widthDegrees, 1e-6)
        assertEquals(30.0, arc.turnRateDegPerSec, 1e-6)
    }

    @Test
    fun anAnticlockwiseTurnHasANegativeRate() {
        val history = turning(degPerSec = -45.0, start = 100.0)
        val arc = assertNotNull(history.arcAround(centerMillis = 1_500L, windowMillis = 800L))
        assertEquals(-45.0, arc.turnRateDegPerSec, 1e-6)
        assertEquals(36.0, arc.widthDegrees, 1e-6)
    }

    @Test
    fun anArcAcrossNorthKeepsItsWidth() {
        // Turning clockwise through north: 350 -> 10 degrees is a 20 degree sweep, not 340.
        val history = HeadingHistory()
        var t = 0L
        while (t <= 2_000L) {
            history.record(t, 340.0 + 30.0 * t / 1000.0)
            t += 20L
        }
        val arc = assertNotNull(history.arcAround(centerMillis = 1_000L, windowMillis = 800L))
        assertEquals(10.0, arc.centerDegrees, 1e-6)
        assertEquals(24.0, arc.widthDegrees, 1e-6)
    }

    @Test
    fun anArcNeedsTheWholeWindowToBeRecorded() {
        val history = turning(degPerSec = 30.0, seconds = 1.0)
        // The window's end (1.3 s) is later than anything recorded, so nothing is invented.
        assertNull(history.arcAround(centerMillis = 900L, windowMillis = 800L))
    }

    @Test
    fun aWindowRunningPastTheNewestHeadingHasNoArc() {
        // A reading describes a moment the radio measured a moment ago, so the sweep belongs to
        // a window that has usually already closed. That is the common case, not an edge case,
        // and the radar answers it with the single heading it can still resolve rather than by
        // discarding the reading.
        val history = turning(degPerSec = 30.0, seconds = 1.0)
        assertNull(history.arcAround(centerMillis = 900L, windowMillis = 800L))
        assertEquals(24.0, assertNotNull(history.headingAt(800L)), 1e-6)
    }

    @Test
    fun aCompassAtFiveHertzStillFillsTheWholeWindow() {
        // Far slower than a phone asked for game rate is given, but a working compass. Its
        // headings interpolate, so the exact arc is still measured.
        val history = HeadingHistory()
        var t = 0L
        while (t <= 3_000L) {
            history.record(t, 30.0 * t / 1000.0)
            t += 200L
        }
        val arc = assertNotNull(history.arcAround(centerMillis = 2_000L, windowMillis = 800L))
        assertEquals(60.0, arc.centerDegrees, 1e-6)
        assertEquals(24.0, arc.widthDegrees, 1e-6)
    }

    @Test
    fun aCompassReportingTwiceASecondKeepsItsHeadingsEvenWhereTheWindowIsAGap() {
        // Half a second apart is not a neighbour pair, so the moment between two reports has no
        // heading. Every heading the compass did report is still there, which is what the radar
        // falls back on rather than losing the reading.
        val history = HeadingHistory()
        var t = 0L
        while (t <= 4_000L) {
            history.record(t, 30.0 * t / 1000.0)
            t += 500L
        }
        assertNull(history.headingAt(2_250L))
        assertEquals(60.0, assertNotNull(history.headingAt(2_000L)), 1e-6)
        assertEquals(75.0, assertNotNull(history.headingAt(2_500L)), 1e-6)
    }

    @Test
    fun aStampThatDoesNotMoveForwardIsIgnored() {
        val history = HeadingHistory()
        history.record(100L, 10.0)
        history.record(100L, 99.0)
        history.record(50L, 99.0)
        assertEquals(100L, history.latestMillis())
        assertEquals(10.0, assertNotNull(history.headingAt(100L)), 1e-9)
    }

    @Test
    fun theOldestHeadingsAreDroppedWhenTheHistoryIsFull() {
        val history = HeadingHistory(capacity = 8)
        for (i in 0 until 20) history.record(i * 20L, i.toDouble())
        assertNull(history.headingAt(0L))
        assertTrue(history.headingAt(19 * 20L) != null)
        assertEquals(12.0, assertNotNull(history.headingAt(12 * 20L)), 1e-9)
        history.clear()
        assertNull(history.latestMillis())
    }
}

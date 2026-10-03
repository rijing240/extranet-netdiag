package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the walking companion: no advice before it has learned anything, "go back N steps" when a
 * clearly better spot was passed, "you are at the best" when here matches it, and the rule that
 * a missing reading is a gap rather than a weak signal.
 */
class WalkTrackerTest {

    private fun WalkTracker.feed(dbm: Int, fromStep: Int, count: Int) {
        for (i in 0 until count) append(dbm, fromStep + i)
    }

    @Test
    fun adviceWaitsUntilItHasLearnedTheArea() {
        val walk = WalkTracker()
        walk.feed(-90, fromStep = 0, count = 3)
        assertEquals(WalkTracker.AdviceKind.LEARNING, walk.snapshot().advice.kind)
    }

    @Test
    fun passingABetterSpotSendsTheWalkerBackTheRightNumberOfSteps() {
        val walk = WalkTracker()
        walk.feed(-80, fromStep = 0, count = 6)
        // The median of three lags one reading behind, so the spot still reads -80 at step 6.
        walk.feed(-95, fromStep = 6, count = 15) // then a long walk into a weak area, step 20 now
        val advice = walk.snapshot().advice
        assertEquals(WalkTracker.AdviceKind.GO_BACK, advice.kind)
        assertEquals(14, advice.stepsBack)
        assertTrue((advice.gainDb ?: 0.0) >= 3.0)
    }

    @Test
    fun beingAtTheBestSpotSaysSo() {
        val walk = WalkTracker()
        walk.feed(-100, fromStep = 0, count = 3)
        walk.feed(-85, fromStep = 3, count = 6)
        val advice = walk.snapshot().advice
        assertEquals(WalkTracker.AdviceKind.AT_BEST, advice.kind)
        assertNull(advice.stepsBack)
    }

    @Test
    fun aSmallDipIsNotWorthWalkingBackFor() {
        val walk = WalkTracker()
        walk.feed(-85, fromStep = 0, count = 6)
        walk.feed(-87, fromStep = 6, count = 3) // two dB down: jitter, not a reason to turn around
        assertEquals(WalkTracker.AdviceKind.KEEP_EXPLORING, walk.snapshot().advice.kind)
    }

    @Test
    fun oneNoisyReadingDoesNotCrownABestSpot() {
        val walk = WalkTracker()
        walk.feed(-95, fromStep = 0, count = 6)
        walk.append(-60, 6) // a single spike; the median of three ignores it
        assertTrue((walk.snapshot().best?.dbm ?: 0.0) <= -90.0)
    }

    @Test
    fun aMissingReadingIsSkippedNotCountedAsWeak() {
        val walk = WalkTracker()
        walk.feed(-85, fromStep = 0, count = 6)
        walk.append(null, 6)
        assertEquals(6, walk.snapshot().history.size)
        assertEquals(WalkTracker.AdviceKind.AT_BEST, walk.snapshot().advice.kind)
    }

    @Test
    fun resetClearsTheWalk() {
        val walk = WalkTracker()
        walk.feed(-85, fromStep = 0, count = 6)
        walk.reset()
        val snapshot = walk.snapshot()
        assertNull(snapshot.best)
        assertTrue(snapshot.history.isEmpty())
    }
}

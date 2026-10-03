package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the warmer/colder trend: the median-halves rule, the threshold, the full-window rule,
 * and the null handling that keeps a missing reading from counting as a movement.
 */
class SignalTrendTest {

    private fun trend() = SignalTrend(windowSize = 10, thresholdDb = 3.0)

    private fun fill(t: SignalTrend, vararg values: Int) {
        for (v in values) t.append(v)
    }

    @Test
    fun windowNotFullMeansNoTrend() {
        val t = trend()
        fill(t, -100, -100, -100, -100)
        assertNull(t.evaluate(), "three samples are noise, not a trend")
        // Nine of ten is still not a trend.
        fill(t, -100, -100, -100, -100, -100)
        assertNull(t.evaluate())
    }

    @Test
    fun steadySignalReadsSteady() {
        val t = trend()
        fill(t, -100, -101, -100, -100, -101, -100, -101, -100, -100, -101)
        val reading = t.evaluate()!!
        assertEquals(SignalTrend.Direction.STEADY, reading.direction)
        assertTrue(kotlin.math.abs(reading.deltaDb) < 3.0)
    }

    @Test
    fun improvingSignalReadsWarmer() {
        val t = trend()
        // Older half around -105, newer half around -95: a ten dB climb.
        fill(t, -105, -106, -104, -105, -105, -95, -96, -94, -95, -95)
        val reading = t.evaluate()!!
        assertEquals(SignalTrend.Direction.WARMER, reading.direction)
        assertEquals(10.0, reading.deltaDb, 0.001)
    }

    @Test
    fun worseningSignalReadsColder() {
        val t = trend()
        fill(t, -95, -96, -94, -95, -95, -105, -106, -104, -105, -105)
        val reading = t.evaluate()!!
        assertEquals(SignalTrend.Direction.COLDER, reading.direction)
        assertEquals(-10.0, reading.deltaDb, 0.001)
    }

    @Test
    fun smallDriftUnderTheThresholdIsStillSteady() {
        val t = trend()
        // One dB of drift is jitter on any radio; the threshold exists so it never reads as movement.
        fill(t, -100, -101, -100, -101, -100, -99, -100, -99, -100, -99)
        assertEquals(SignalTrend.Direction.STEADY, t.evaluate()!!.direction)
    }

    @Test
    fun aMissingReadingIsSkippedNeverCountedAsAWorseSignal() {
        val t = trend()
        fill(t, -100, -100, -100, -100, -100)
        t.append(null) // The radio blinked; it did not get worse.
        fill(t, -90, -90, -90, -90, -90)
        // Five cold and five warm usable readings make a full window; a null counted as a
        // sample would leave nine and no trend at all.
        val reading = t.evaluate()!!
        assertEquals(SignalTrend.Direction.WARMER, reading.direction)
    }

    @Test
    fun theWindowKeepsOnlyTheNewestReadings() {
        val t = trend()
        // Ten cold samples, then six warm ones: the window keeps the last ten, so the older
        // half is mostly cold and the newer half all warm. If the window never slid, the
        // whole window would still be cold and the trend would read steady.
        fill(t, -110, -110, -110, -110, -110, -110, -110, -110, -110, -110)
        fill(t, -90, -90, -90, -90, -90, -90)
        assertEquals(SignalTrend.Direction.WARMER, t.evaluate()!!.direction)
    }

    @Test
    fun resetClearsTheOldWalk() {
        val t = trend()
        fill(t, -90, -90, -90, -90, -90, -90, -90, -90, -90, -90)
        t.reset()
        assertNull(t.evaluate(), "a reset window must not carry the last session's trend")
    }
}

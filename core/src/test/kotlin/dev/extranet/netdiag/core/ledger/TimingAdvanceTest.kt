package dev.extranet.netdiag.core.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the timing-advance derivation. If any of these fail, every downstream distance,
 * trend and range band is wrong, so they are asserted to a documented tolerance rather than
 * compared loosely.
 */
class TimingAdvanceTest {

    @Test
    fun `basic time unit is 1 over 15000 times 2048`() {
        assertEquals(30_720_000.0, TimingAdvance.BASIC_TIME_UNIT_DENOMINATOR, 1e-9)
        assertEquals(32.5520833333e-9, TimingAdvance.BASIC_TIME_UNIT_SECONDS, 1e-18)
    }

    @Test
    fun `one command step is sixteen basic units of round trip`() {
        assertEquals(16, TimingAdvance.COMMAND_STEP_BASIC_UNITS)
        assertEquals(520.8333333333e-9, TimingAdvance.COMMAND_STEP_SECONDS, 1e-18)
    }

    @Test
    fun `command step is 78 point 071 metres with the exact speed of light`() {
        assertEquals(78.071, TimingAdvance.COMMAND_STEP_METRES, 0.001)
    }

    @Test
    fun `command step is 78 point 125 metres with the rounded speed of light`() {
        val roundTrip = TimingAdvance.COMMAND_STEP_SECONDS * PhysicalConstants.ROUNDED_SPEED_OF_LIGHT_MPS
        assertEquals(78.125, roundTrip / 2.0, 1e-9)
    }

    @Test
    fun `documented approximate constant matches the derivation`() {
        // Guards the documented 78.125 anchor against drifting from the exact 78.071 value.
        assertEquals(TimingAdvance.COMMAND_STEP_METRES_APPROX, TimingAdvance.COMMAND_STEP_METRES, 0.06)
    }

    @Test
    fun `one basic unit is 4 point 8794 metres`() {
        assertEquals(4.8794, TimingAdvance.METRES_PER_BASIC_UNIT, 0.0001)
    }

    @Test
    fun `quantisation half step is half a command step`() {
        assertEquals(TimingAdvance.COMMAND_STEP_METRES / 2.0, TimingAdvance.QUANTISATION_HALF_STEP_METRES, 1e-12)
        assertEquals(39.036, TimingAdvance.QUANTISATION_HALF_STEP_METRES, 0.001)
    }

    @Test
    fun `distance from basic units scales linearly`() {
        assertEquals(0.0, TimingAdvance.distanceMetresFromBasicUnits(0), 1e-12)
        assertEquals(78.071, TimingAdvance.distanceMetresFromBasicUnits(16), 0.001)
        assertEquals(780.71, TimingAdvance.distanceMetresFromBasicUnits(160), 0.01)
    }

    @Test
    fun `distance from command steps equals the step constant`() {
        assertEquals(14 * 78.071, TimingAdvance.distanceMetresFromCommandSteps(14), 0.02)
    }

    @Test
    fun `gsm step is 553 point 5 metres`() {
        assertEquals(553.5, TimingAdvance.GSM_COMMAND_STEP_METRES, 0.1)
    }

    @Test
    fun `unavailable sentinel is not treated as a distance`() {
        assertFalse(TimingAdvance.isAvailable(TimingAdvance.UNAVAILABLE))
        assertTrue(TimingAdvance.isAvailable(0))
        assertTrue(TimingAdvance.isAvailable(16))
    }

    @Test
    fun `quantisation band brackets the point estimate`() {
        val band = TimingAdvance.rangeFromBasicUnits(16)
        assertEquals(78.071, band.centreMetres, 0.001)
        assertEquals(78.071, band.spanMetres, 0.01)
        assertTrue(RangeBand.contains(band, 78.0))
        assertFalse(RangeBand.contains(band, 200.0))
    }

    @Test
    fun `conservative band is biased outward by the nlos range`() {
        val band = TimingAdvance.conservativeRangeFromBasicUnits(16)
        // Asymmetric on purpose: non-line-of-sight can only lengthen the path.
        assertEquals(16 * TimingAdvance.METRES_PER_BASIC_UNIT - TimingAdvance.NLOS_BIAS_MIN_METRES, band.minMetres, 0.001)
        assertEquals(16 * TimingAdvance.METRES_PER_BASIC_UNIT + TimingAdvance.NLOS_BIAS_MAX_METRES, band.maxMetres, 0.001)
        assertTrue(band.spanMetres > 200.0, "conservative band should be at least 200 m wide")
    }

    @Test
    fun `conservative band never goes negative near the tower`() {
        val band = TimingAdvance.conservativeRangeFromBasicUnits(0)
        assertEquals(0.0, band.minMetres, 1e-12)
        assertEquals(150.0, band.maxMetres, 1e-9)
    }

    @Test
    fun `gsm band is half a gsm step either side`() {
        val band = TimingAdvance.gsmRange(2)
        assertEquals(2 * 553.46, band.centreMetres, 0.05)
        assertEquals(553.46, band.spanMetres, 0.05)
    }

    @Test
    fun `range band rejects inverted bounds`() {
        assertFailsWith<IllegalArgumentException> { RangeBand(10.0, 5.0) }
    }

    @Test
    fun `widening grows both ends`() {
        val band = RangeBand.point(100.0).widened(25.0)
        assertEquals(75.0, band.minMetres, 1e-12)
        assertEquals(125.0, band.maxMetres, 1e-12)
    }

    @Test
    fun `range band geometry helpers behave`() {
        val a = RangeBand(0.0, 100.0)
        val b = RangeBand(90.0, 200.0)
        val c = RangeBand(500.0, 600.0)
        assertTrue(RangeBand.overlaps(a, b))
        assertFalse(RangeBand.overlaps(a, c))
        assertEquals(50.0, RangeBand.distanceTo(a, 150.0), 1e-12)
        assertEquals(0.0, RangeBand.distanceTo(a, 50.0), 1e-12)
    }
}

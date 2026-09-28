package dev.extranet.netdiag.core.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the radio-physics anchors quoted in the approved plan. Each anchor is asserted against
 * the published constant so a future refactor cannot silently change a headline number.
 */
class ChannelMathTest {

    // --- Shannon-Hartley -------------------------------------------------------------------

    @Test
    fun `0 dB sinr gives exactly the bandwidth`() {
        // log2(1 + 1) == 1, so this must be exact, not merely close.
        assertEquals(20_000_000.0, ShannonHartley.referenceCapacityBitsPerSecond(0.0), 1e-6)
    }

    @Test
    fun `10 dB sinr on 20 MHz gives 69 point 19 Mbps`() {
        assertEquals(
            ShannonHartley.ANCHOR_SINR_10_DB_BPS,
            ShannonHartley.referenceCapacityBitsPerSecond(10.0),
            100.0,
        )
        assertEquals(69.19, ShannonHartley.referenceCapacityBitsPerSecond(10.0) / 1e6, 0.01)
    }

    @Test
    fun `20 dB sinr on 20 MHz gives 133 point 2 Mbps`() {
        assertEquals(
            ShannonHartley.ANCHOR_SINR_20_DB_BPS,
            ShannonHartley.referenceCapacityBitsPerSecond(20.0),
            100.0,
        )
        assertEquals(133.16, ShannonHartley.referenceCapacityBitsPerSecond(20.0) / 1e6, 0.01)
    }

    @Test
    fun `minus 5 dB sinr on 20 MHz gives 7 point 9 Mbps`() {
        assertEquals(
            ShannonHartley.ANCHOR_SINR_MINUS_5_DB_BPS,
            ShannonHartley.referenceCapacityBitsPerSecond(-5.0),
            100.0,
        )
        assertEquals(7.92, ShannonHartley.referenceCapacityBitsPerSecond(-5.0) / 1e6, 0.01)
    }

    @Test
    fun `capacity scales linearly with bandwidth`() {
        val single = ShannonHartley.capacityBitsPerSecond(10_000_000.0, 10.0)
        val double = ShannonHartley.capacityBitsPerSecond(20_000_000.0, 10.0)
        assertEquals(2.0, double / single, 1e-9)
    }

    @Test
    fun `capacity is monotonic in sinr`() {
        val values = listOf(-10.0, -5.0, 0.0, 10.0, 20.0, 30.0)
            .map { ShannonHartley.capacityBitsPerSecond(20_000_000.0, it) }
        for (i in 1 until values.size) {
            assertTrue(values[i] > values[i - 1], "capacity must increase with SINR at index $i")
        }
    }

    @Test
    fun `relative usage is clamped and never exceeds one`() {
        assertEquals(0.5, ShannonHartley.relativeCapacityUsage(10_000_000.0, 20_000_000.0), 1e-9)
        assertEquals(1.0, ShannonHartley.relativeCapacityUsage(80_000_000.0, 20_000_000.0), 1e-9)
        assertEquals(0.0, ShannonHartley.relativeCapacityUsage(0.0, 20_000_000.0), 1e-9)
        assertEquals(0.0, ShannonHartley.relativeCapacityUsage(-5.0, 20_000_000.0), 1e-9)
    }

    @Test
    fun `capacity rejects non positive bandwidth and zero denominator`() {
        assertFailsWith<IllegalArgumentException> {
            ShannonHartley.capacityBitsPerSecond(0.0, 10.0)
        }
        assertFailsWith<IllegalArgumentException> {
            ShannonHartley.relativeCapacityUsage(1.0, 0.0)
        }
    }

    // --- Doppler ---------------------------------------------------------------------------

    @Test
    fun `gps l1 wavelength is 0 point 1903 metres`() {
        assertEquals(0.190294, Doppler.L1_WAVELENGTH_METRES, 1e-6)
    }

    @Test
    fun `1 Hz of doppler noise is 0 point 19 metres per second on l1`() {
        assertEquals(0.190294, Doppler.L1_VELOCITY_SIGMA_MPS, 1e-6)
        assertEquals(0.19, Doppler.L1_VELOCITY_SIGMA_MPS, 0.001)
    }

    @Test
    fun `velocity sigma scales with doppler noise`() {
        assertEquals(
            2.0 * Doppler.L1_VELOCITY_SIGMA_MPS,
            Doppler.velocitySigmaMetresPerSecond(2.0),
            1e-9,
        )
        assertEquals(
            0.5 * Doppler.L1_VELOCITY_SIGMA_MPS,
            Doppler.velocitySigmaMetresPerSecond(Doppler.DOPPLER_SIGMA_HZ_MIN),
            1e-9,
        )
    }

    @Test
    fun `worst case velocity sigma stays inside a metre per second`() {
        val worst = Doppler.velocitySigmaMetresPerSecond(Doppler.DOPPLER_SIGMA_HZ_MAX)
        assertEquals(0.380589, worst, 1e-5)
        assertTrue(worst < 0.5, "worst-case L1 velocity noise should stay under 0.5 m/s")
    }

    @Test
    fun `n78 wavelength is much shorter than l1`() {
        assertEquals(0.085655, Doppler.NR_N78_WAVELENGTH_METRES, 1e-6)
        assertTrue(Doppler.NR_N78_WAVELENGTH_METRES < Doppler.L1_WAVELENGTH_METRES)
    }

    @Test
    fun `velocity solve needs four satellites for four unknowns`() {
        assertEquals(4, Doppler.VELOCITY_SOLVE_UNKNOWNS)
        assertEquals(Doppler.VELOCITY_SOLVE_UNKNOWNS, Doppler.VELOCITY_SOLVE_MIN_SATELLITES)
    }

    // --- Wi-Fi RTT -------------------------------------------------------------------------

    @Test
    fun `20 ns round trip is 2 point 998 metres`() {
        assertEquals(2.99792458, WifiRtt.distanceMetres(20.0), 1e-8)
        assertEquals(3.00, WifiRtt.distanceMetres(20.0), 0.01)
    }

    @Test
    fun `rtt distance is linear in round trip time`() {
        assertEquals(
            WifiRtt.distanceMetres(40.0),
            2.0 * WifiRtt.distanceMetres(20.0),
            1e-9,
        )
    }

    @Test
    fun `rtt band reflects the documented accuracy`() {
        val band = WifiRtt.rangeMetres(20.0)
        assertEquals(2.99792458, band.centreMetres, 1e-8)
        assertEquals(2.0, band.spanMetres, 1e-9)
    }

    @Test
    fun `rtt band never goes negative for a zero round trip`() {
        val band = WifiRtt.rangeMetres(0.0)
        assertEquals(0.0, band.minMetres, 1e-12)
        assertEquals(1.0, band.maxMetres, 1e-9)
    }
}

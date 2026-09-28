package dev.extranet.netdiag.core.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pins the sample-size gate, the byte budgets and the bufferbloat grading table. */
class BudgetAndGateTest {

    // --- ModelGate: the gate that orders the roadmap ---------------------------------------

    @Test
    fun `385 positive events are required for a 5 point margin at 95 percent`() {
        // ceil(1.96^2 * 0.5 * 0.5 / 0.05^2) = ceil(384.16)
        assertEquals(385, ModelGate.requiredPositiveSamples())
    }

    @Test
    fun `required samples scale with confidence and margin`() {
        assertEquals(271, ModelGate.requiredPositiveSamples(z = ModelGate.Z_90))
        assertEquals(97, ModelGate.requiredPositiveSamples(margin = 0.10))
        assertEquals(1537, ModelGate.requiredPositiveSamples(margin = 0.025))
    }

    @Test
    fun `a single device needs 320 hours`() {
        assertEquals(19_250.0, ModelGate.requiredObservedMinutes(385), 1e-9)
        assertEquals(320.833, ModelGate.singleDeviceHours(), 0.001)
    }

    @Test
    fun `a 200 user fleet needs under two days`() {
        assertEquals(1.604, ModelGate.fleetDays(), 0.001)
    }

    @Test
    fun `one user is exactly the single-device figure in days`() {
        assertEquals(
            ModelGate.singleDeviceHours() / 24.0,
            ModelGate.fleetDays(users = 1),
            1e-9,
        )
    }

    @Test
    fun `the fleet shortcut is at least one hundred times faster than one device`() {
        val single = ModelGate.fleetDays(users = 1)
        val fleet = ModelGate.fleetDays()
        assertTrue(fleet > 0.0)
        assertTrue(single / fleet >= 100.0, "fleet advantage was only ${single / fleet}x")
    }

    @Test
    fun `gate rejects degenerate parameters`() {
        assertFailsWith<IllegalArgumentException> { ModelGate.requiredPositiveSamples(margin = 0.0) }
        assertFailsWith<IllegalArgumentException> { ModelGate.requiredPositiveSamples(margin = 1.0) }
        assertFailsWith<IllegalArgumentException> { ModelGate.requiredPositiveSamples(proportion = 0.0) }
        assertFailsWith<IllegalArgumentException> { ModelGate.fleetDays(users = 0) }
        assertFailsWith<IllegalArgumentException> { ModelGate.requiredObservedMinutes(10, baseRate = 0.0) }
    }

    // --- ByteBudget ------------------------------------------------------------------------

    @Test
    fun `radio timeline costs 93 point 6 kilobytes per hour`() {
        assertEquals(93_600L, ByteBudget.radioBytesPerHour())
    }

    @Test
    fun `a gnss burst costs 43 point 2 kilobytes`() {
        assertEquals(43_200L, ByteBudget.gnssBurstBytes())
    }

    @Test
    fun `fleet upload is 7 point 5 gigabytes per month`() {
        assertEquals(7_500_000_000L, ByteBudget.fleetBytesPerMonth())
    }

    @Test
    fun `per-sample storage would be a billion rows per month`() {
        // The plan quotes 1.08e10 here; the derivation is 1.08e9. Either way it is fatal.
        assertEquals(1_080_000_000L, ByteBudget.fleetRowsIfPerSamplePerMonth())
        assertEquals(108_000L, ByteBudget.fleetRowsIfPerSamplePerMonth(dailyActiveUsers = 1))
    }

    @Test
    fun `session summaries are three orders of magnitude smaller than per-sample rows`() {
        assertEquals(300_000L, ByteBudget.fleetRowsIfSessionSummaryPerMonth())
        assertEquals(
            3_600L,
            ByteBudget.fleetRowsIfPerSamplePerMonth() / ByteBudget.fleetRowsIfSessionSummaryPerMonth(),
        )
    }

    @Test
    fun `background sampling keeps drain under one percent per hour`() {
        assertEquals(30.0, BatteryBudget.BACKGROUND_SAMPLE_INTERVAL_SECONDS, 1e-9)
        assertTrue(BatteryBudget.BACKGROUND_PERCENT_PER_HOUR_MAX <= 1.0)
        assertTrue(
            BatteryBudget.ACTIVE_SESSION_PERCENT_PER_HOUR_MIN <
                BatteryBudget.ACTIVE_SESSION_PERCENT_PER_HOUR_MAX,
        )
    }

    // --- Bufferbloat -----------------------------------------------------------------------

    @Test
    fun `delta is loaded minus idle`() {
        assertEquals(70.0, Bufferbloat.deltaMilliseconds(idleP50Ms = 50.0, loadedP50Ms = 120.0), 1e-9)
        assertEquals(-10.0, Bufferbloat.deltaMilliseconds(idleP50Ms = 50.0, loadedP50Ms = 40.0), 1e-9)
    }

    @Test
    fun `grade boundaries are the documented table`() {
        assertEquals(BufferbloatGrade.A, Bufferbloat.gradeFor(0.0))
        assertEquals(BufferbloatGrade.A, Bufferbloat.gradeFor(29.999))
        assertEquals(BufferbloatGrade.B, Bufferbloat.gradeFor(30.0))
        assertEquals(BufferbloatGrade.B, Bufferbloat.gradeFor(59.999))
        assertEquals(BufferbloatGrade.C, Bufferbloat.gradeFor(60.0))
        assertEquals(BufferbloatGrade.C, Bufferbloat.gradeFor(149.999))
        assertEquals(BufferbloatGrade.D, Bufferbloat.gradeFor(150.0))
        assertEquals(BufferbloatGrade.D, Bufferbloat.gradeFor(399.999))
        assertEquals(BufferbloatGrade.F, Bufferbloat.gradeFor(400.0))
        assertEquals(BufferbloatGrade.F, Bufferbloat.gradeFor(5_000.0))
    }

    @Test
    fun `negative delta grades as the best case rather than crashing`() {
        assertEquals(BufferbloatGrade.A, Bufferbloat.gradeFor(-1_000.0))
    }

    @Test
    fun `upper bounds match the boundaries`() {
        assertEquals(30.0, Bufferbloat.upperBoundMilliseconds(BufferbloatGrade.A), 1e-9)
        assertEquals(60.0, Bufferbloat.upperBoundMilliseconds(BufferbloatGrade.B), 1e-9)
        assertEquals(150.0, Bufferbloat.upperBoundMilliseconds(BufferbloatGrade.C), 1e-9)
        assertEquals(400.0, Bufferbloat.upperBoundMilliseconds(BufferbloatGrade.D), 1e-9)
        assertEquals(
            Double.POSITIVE_INFINITY,
            Bufferbloat.upperBoundMilliseconds(BufferbloatGrade.F),
            0.0,
        )
    }

    @Test
    fun `capped saturation keeps a test inside the payload ceiling`() {
        val payload = Bufferbloat.payloadBytes(Bufferbloat.CAPPED_SATURATION_BPS, 15.0)
        assertEquals(9_375_000L, payload)
        assertTrue(payload <= Bufferbloat.MAX_TEST_BYTES, "capped test exceeded the 10 MB ceiling")
    }

    @Test
    fun `an uncapped 20 Mbps test would blow the ceiling in 4 seconds`() {
        val fourSeconds = Bufferbloat.payloadBytes(20_000_000L, 4.0)
        assertEquals(10_000_000L, fourSeconds)
        assertTrue(fourSeconds >= Bufferbloat.MAX_TEST_BYTES)
    }

    @Test
    fun `percentile stability needs thirty samples per state`() {
        assertEquals(30, Bufferbloat.SAMPLES_PER_STATE)
        assertTrue(Bufferbloat.NOMINAL_TEST_SECONDS >= 2.0 * Bufferbloat.SAMPLES_PER_STATE - 5.0)
    }
}

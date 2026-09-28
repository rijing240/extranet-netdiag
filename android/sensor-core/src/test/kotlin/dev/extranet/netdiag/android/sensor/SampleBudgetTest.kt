package dev.extranet.netdiag.android.sensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the X2 sampling policy, which is the only thing standing between the app and the
 * Android battery police. */
class SampleBudgetTest {

    @Test
    fun `a foreground session samples at the 1 Hz cap`() {
        assertEquals(1_000L, SampleBudget.intervalMillis(foreground = true, moving = true))
        assertEquals(1_000L, SampleBudget.intervalMillis(foreground = true, moving = false))
    }

    @Test
    fun `background sampling slows to one in five seconds while moving`() {
        assertEquals(5_000L, SampleBudget.intervalMillis(foreground = false, moving = true))
    }

    @Test
    fun `a stationary background app samples once every thirty seconds`() {
        assertEquals(30_000L, SampleBudget.intervalMillis(foreground = false, moving = false))
    }

    @Test
    fun `thermal throttling overrides every other state`() {
        val severe = SampleBudget.THERMAL_STATUS_SEVERE
        assertEquals(60_000L, SampleBudget.intervalMillis(foreground = true, moving = true, thermalStatus = severe))
        assertEquals(60_000L, SampleBudget.intervalMillis(foreground = false, moving = true, thermalStatus = severe))
        assertEquals(60_000L, SampleBudget.intervalMillis(foreground = false, moving = false, thermalStatus = severe + 2))
    }

    @Test
    fun `thermal thresholds are inclusive of severe`() {
        assertFalse(SampleBudget.isThermallyThrottled(0))
        assertFalse(SampleBudget.isThermallyThrottled(SampleBudget.THERMAL_STATUS_SEVERE - 1))
        assertTrue(SampleBudget.isThermallyThrottled(SampleBudget.THERMAL_STATUS_SEVERE))
        assertTrue(SampleBudget.isThermallyThrottled(6))
    }

    @Test
    fun `the background gate requires movement and a cool device`() {
        assertTrue(SampleBudget.shouldSampleInBackground(moving = true, thermalStatus = 0))
        assertFalse(SampleBudget.shouldSampleInBackground(moving = false, thermalStatus = 0))
        assertFalse(SampleBudget.shouldSampleInBackground(moving = true, thermalStatus = 5))
    }

    @Test
    fun `the idle interval matches the documented thirty second budget`() {
        // Cross-check against the ledger constant rather than duplicating the number.
        assertEquals(
            (dev.extranet.netdiag.core.ledger.BatteryBudget.BACKGROUND_SAMPLE_INTERVAL_SECONDS * 1000).toLong(),
            SampleBudget.IDLE_INTERVAL_MILLIS,
        )
    }

    @Test
    fun `gnss burst cap matches the byte budget`() {
        assertEquals(
            dev.extranet.netdiag.core.ledger.ByteBudget.GNSS_BURST_SECONDS * 1000L,
            SampleBudget.GNSS_BURST_MILLIS,
        )
    }

    @Test
    fun `active sampling is capped at the ledger's one hertz`() {
        assertEquals(
            (1_000L / dev.extranet.netdiag.core.ledger.ByteBudget.RADIO_SAMPLES_PER_SECOND),
            SampleBudget.ACTIVE_INTERVAL_MILLIS,
        )
    }

    @Test
    fun `idle sampling stays inside the one percent battery ceiling`() {
        // 120 samples/hour is the stated background budget and must be well under the
        // 1% per hour ceiling implied by the ledger.
        assertTrue(SampleBudget.samplesPerHour(SampleBudget.IDLE_INTERVAL_MILLIS) <= 120.0)
    }

    @Test
    fun `samples per hour is the reciprocal of the interval`() {
        assertEquals(3_600.0, SampleBudget.samplesPerHour(1_000L), 1e-9)
        assertEquals(120.0, SampleBudget.samplesPerHour(30_000L), 1e-9)
    }

    @Test
    fun `a non positive interval is rejected`() {
        assertFailsWith<IllegalArgumentException> { SampleBudget.samplesPerHour(0L) }
        assertFailsWith<IllegalArgumentException> { SampleBudget.samplesPerHour(-1L) }
    }
}

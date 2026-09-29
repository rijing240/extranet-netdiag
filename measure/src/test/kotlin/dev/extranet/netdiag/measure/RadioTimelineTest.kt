package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.ByteBudget
import dev.extranet.netdiag.core.ledger.TimelineBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the timeline ring: capacity, rotation, field accounting, and the TA sentinel.
 *
 * The ledger promises 26 bytes per sample and a ring that never grows; these tests are how
 * the promise stays true while features land around it.
 */
class RadioTimelineTest {

    private fun sample(epoch: Long, rsrp: Int? = -95, heading: Double? = null, event: String? = null) =
        RadioSample(
            epochMillis = epoch,
            networkType = "LTE",
            rsrpDbm = rsrp,
            rsrqDb = -10,
            rssnrDb = 20,
            rssiDbm = -85,
            timingAdvance = if (rsrp == null) RadioSample.TIMING_ADVANCE_UNKNOWN else 12,
            level = 4,
            headingDegrees = heading,
            networkEvent = event,
        )

    @Test
    fun theRingHoldsExactlyItsCapacityAndRotates() {
        val ring = RadioTimeline()
        repeat(TimelineBudget.RING_CAPACITY_SAMPLES + 100) { ring.append(sample(it.toLong())) }

        assertEquals(TimelineBudget.RING_CAPACITY_SAMPLES, ring.size())
        assertEquals(100, ring.droppedCount())

        val snapshot = ring.snapshot()
        // The oldest 100 are gone; the first survivor is epoch 100.
        assertEquals(100L, snapshot.first().epochMillis)
        assertEquals((TimelineBudget.RING_CAPACITY_SAMPLES + 99).toLong(), snapshot.last().epochMillis)
    }

    @Test
    fun snapshotsAreIndependentOfLaterAppends() {
        val ring = RadioTimeline()
        ring.append(sample(1))
        val before = ring.snapshot()
        ring.append(sample(2))

        assertEquals(1, before.size)
        assertEquals(2, ring.size())
    }

    @Test
    fun theTaSentinelIsNeverCountedAsAMeasurement() {
        val unknown = RadioSample(
            epochMillis = 1, networkType = "LTE",
            rsrpDbm = -95, rsrqDb = -10, rssnrDb = 20, rssiDbm = -85,
            timingAdvance = RadioSample.TIMING_ADVANCE_UNKNOWN,
            level = 4, headingDegrees = null, networkEvent = null,
        )
        assertTrue(unknown.timingAdvanceUnknown)
        assertNull(unknown.taDistanceMeters)

        val known = sample(2)
        assertFalse(known.timingAdvanceUnknown)
        assertEquals(12 * RadioSample.TA_STEP_METERS, known.taDistanceMeters!!, 0.001)
    }

    @Test
    fun aNullFieldIsAGapNotAZero() {
        val blind = RadioSample(
            epochMillis = 1, networkType = null,
            rsrpDbm = null, rsrqDb = null, rssnrDb = null, rssiDbm = null,
            timingAdvance = null, level = null, headingDegrees = null, networkEvent = null,
        )
        assertNull(blind.rsrpDbm)
        assertNull(blind.taDistanceMeters)
    }

    @Test
    fun theSummaryCountsWhatThePlatformActuallySaid() {
        val ring = RadioTimeline()
        ring.append(sample(1, heading = 10.0, event = "wifi_lost"))
        ring.append(sample(2, rsrp = null))
        ring.append(
            RadioSample(
                epochMillis = 3, networkType = null,
                rsrpDbm = null, rsrqDb = null, rssnrDb = null, rssiDbm = null,
                timingAdvance = RadioSample.TIMING_ADVANCE_UNKNOWN,
                level = null, headingDegrees = null, networkEvent = null,
            ),
        )

        val summary = ring.summary()
        assertEquals(3, summary.samples)
        assertEquals(2, summary.withRsrp)
        assertEquals(1, summary.withHeading)
        assertEquals(1, summary.withTimingAdvance)
        assertEquals(1, summary.withEvents)
        assertTrue(summary.rsrpCoverage > 0.66 && summary.rsrpCoverage < 0.67)
    }

    @Test
    fun anEmptySummaryIsAllZeroesRatherThanAnError() {
        val summary = RadioTimeline().summary()
        assertEquals(0, summary.samples)
        assertEquals(0.0, summary.rsrpCoverage)
    }

    @Test
    fun clearKeepsTheDropCounterBecauseItIsSessionHistory() {
        val ring = RadioTimeline()
        repeat(TimelineBudget.RING_CAPACITY_SAMPLES + 5) { ring.append(sample(it.toLong())) }
        ring.clear()
        assertEquals(0, ring.size())
        assertEquals(5, ring.droppedCount())
    }

    @Test
    fun theLedgerSampleBudgetStillHolds() {
        assertEquals(ByteBudget.RADIO_SAMPLE_BYTES, TimelineBudget.SAMPLE_BYTES)
        assertEquals(ByteBudget.RADIO_SAMPLES_PER_SECOND, 1)
    }
}

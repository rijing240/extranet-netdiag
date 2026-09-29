package dev.extranet.netdiag.core.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins B2's fixed decisions so no feature re-chooses them silently. */
class TimelineBudgetTest {

    @Test
    fun theRingMatchesThePlanSBatteryAndStorageEnvelope() {
        // 2 hours at 1 Hz, 26 bytes per sample.
        assertEquals(7_200, TimelineBudget.RING_CAPACITY_SAMPLES)
        assertEquals(ByteBudget.RADIO_SAMPLE_BYTES, TimelineBudget.SAMPLE_BYTES)
        assertEquals(187_200L, TimelineBudget.ringBytes())
    }

    @Test
    fun compassSectorsAre16AndTheThresholdIsAboveMultipathNoise() {
        assertEquals(16, TimelineBudget.COMPASS_SECTORS)
        assertEquals(22.5, 360.0 / TimelineBudget.COMPASS_SECTORS, 0.0001)
        assertTrue(TimelineBudget.COMPASS_IMPROVEMENT_THRESHOLD_DB >= 2.0)
        assertTrue(TimelineBudget.COMPASS_MIN_SECTOR_SAMPLES >= 5)
    }

    @Test
    fun throughputVerdictBandsSpanTheUsableRange() {
        assertEquals("POOR", TimelineBudget.throughputVerdict(0.0))
        assertEquals("MARGINAL", TimelineBudget.throughputVerdict(TimelineBudget.THROUGHPUT_POOR_BYTES_PER_SECOND))
        assertEquals("GOOD", TimelineBudget.throughputVerdict(TimelineBudget.THROUGHPUT_GOOD_BYTES_PER_SECOND))
        assertTrue(TimelineBudget.THROUGHPUT_POOR_BYTES_PER_SECOND < TimelineBudget.THROUGHPUT_GOOD_BYTES_PER_SECOND)
    }

    @Test
    fun theThroughputPayloadIsOneMegabyteAndBoundedInTime() {
        assertEquals(1_000_000L, TimelineBudget.THROUGHPUT_PAYLOAD_BYTES)
        assertTrue(TimelineBudget.THROUGHPUT_TIMEOUT_MILLIS in 1..20_000)
    }

    @Test
    fun twoHopRoundsResistSinglePacketLoss() {
        assertTrue(TimelineBudget.TWO_HOP_ROUNDS >= 3)
        assertTrue(TimelineBudget.GATEWAY_PROBE_TIMEOUT_MILLIS < TimelineBudget.INTERNET_PROBE_TIMEOUT_MILLIS)
    }

    @Test
    fun csvColumnsCoverEverySampleFieldInOrder() {
        assertEquals(
            listOf(
                "epochMillis", "networkType", "rsrpDbm", "rsrqDb", "rssnrDb", "rssiDbm",
                "timingAdvance", "taDistanceMeters", "level", "headingDegrees", "networkEvent",
            ),
            TimelineBudget.CSV_COLUMNS,
        )
    }
}

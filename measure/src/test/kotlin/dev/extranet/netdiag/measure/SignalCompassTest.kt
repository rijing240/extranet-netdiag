package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the compass math: sector wrapping, threshold honesty, and the verdict's refusals.
 *
 * The compass can only be as good as its honesty: a verdict that points somewhere when the
 * data does not support it is worse than no verdict. These tests pin both the arithmetic and
 * the refusals.
 */
class SignalCompassTest {

    private fun sample(heading: Double?, rsrp: Int?): RadioSample = RadioSample(
        epochMillis = 0, networkType = "LTE",
        rsrpDbm = rsrp, rsrqDb = null, rssnrDb = null, rssiDbm = null,
        timingAdvance = null, level = null, headingDegrees = heading,
        networkEvent = null,
    )

    @Test
    fun sectorIndexWrapsWithoutAmbiguity() {
        assertEquals(0, SignalCompass.sectorIndex(0.0))
        assertEquals(0, SignalCompass.sectorIndex(22.0))
        assertEquals(1, SignalCompass.sectorIndex(22.6))
        assertEquals(15, SignalCompass.sectorIndex(359.0))
        assertEquals(0, SignalCompass.sectorIndex(360.0))
        // -1 degrees is 359 degrees: 1 degree counterclockwise of north, sector 15.
        assertEquals(15, SignalCompass.sectorIndex(-1.0))
        assertEquals(15, SignalCompass.sectorIndex(-22.5))
    }

    @Test
    fun aSectorIsNamedOnlyWhenItBeatsTheMedianByTheThreshold() {
        // East (sector 0) samples are 5 dB better than the others; the threshold is 2 dB.
        val samples = buildList {
            repeat(8) { add(sample(0.0, -80)) } // east, strong
            repeat(8) { add(sample(90.0, -90)) } // south, baseline
            repeat(8) { add(sample(180.0, -91)) }
            repeat(8) { add(sample(270.0, -89)) }
        }

        val verdict = SignalCompass.verdict(samples)
        assertEquals(4, verdict.sectorCount)
        assertTrue(verdict.bestSector != null, "a 10 dB advantage must be called")
        assertEquals(0, verdict.bestSector!!.index)
        assertTrue(verdict.bestSector!!.improvementDb!! >= TimelineBudget.COMPASS_IMPROVEMENT_THRESHOLD_DB)
        assertTrue(verdict.statement().contains("degrees"))
    }

    @Test
    fun uniformSignalYieldsNoDirectionBecauseNoneIsBetter() {
        val samples = (0 until 64).map { sample(it * 5.625, -90) }
        val verdict = SignalCompass.verdict(samples)
        assertNull(verdict.bestSector)
        assertTrue(verdict.statement().startsWith("No direction"))
    }

    @Test
    fun aMarginalDifferenceIsCalledAsNoise() {
        // 0.8 dB better than the median is under the 2 dB threshold: noise, not a wall.
        val samples = buildList {
            repeat(8) { add(sample(0.0, -89)) }
            repeat(24) { add(sample(90.0 + it * 5, -90)) }
        }
        assertNull(SignalCompass.verdict(samples).bestSector)
    }

    @Test
    fun tooFewSamplesIsRefusedRatherThanGuessed() {
        val samples = buildList {
            repeat(TimelineBudget.COMPASS_MIN_SECTOR_SAMPLES - 1) { add(sample(0.0, -70)) }
            repeat(8) { add(sample(90.0, -95)) }
        }
        val verdict = SignalCompass.verdict(samples)
        // 12 usable samples exist overall but the strong sector has only 4 of the 5 needed.
        assertNull(verdict.bestSector)
        assertTrue(verdict.statement().startsWith("No direction"))
    }

    @Test
    fun noHeadingsAtAllAsksForThem() {
        val verdict = SignalCompass.verdict(listOf(sample(null, -90), sample(10.0, null)))
        assertEquals(0, verdict.samplesWithHeading)
        assertNull(verdict.overallMedianDbm)
        assertTrue(verdict.statement().startsWith("Not enough"))
    }

    @Test
    fun samplesWithoutRsrpOrHeadingAreEvidenceGapsNotZeroes() {
        val verdict = SignalCompass.verdict(
            listOf(sample(null, null), sample(0.0, null), sample(null, -90)),
        )
        assertEquals(0, verdict.samplesWithHeading)
        assertEquals(0, verdict.sectorCount)
    }

    @Test
    fun oppositeHeadingsLandInOppositeSectorsNotTheSameAverage() {
        // The averaging-angle bug would put 350 and 10 at 180; sectors make that impossible.
        val a = SignalCompass.sectorIndex(350.0)
        val b = SignalCompass.sectorIndex(10.0)
        assertTrue(a != b)
        assertEquals(15, a)
        assertEquals(0, b)
    }

    @Test
    fun theNeedlePointsAtTheBestSectorCenter() {
        val samples = buildList {
            repeat(8) { add(sample(270.0, -70)) }
            repeat(8) { add(sample(90.0, -95)) }
        }
        val verdict = SignalCompass.verdict(samples)
        val needle = SignalCompass.needle(verdict)
        assertTrue(needle != null)
        // 270 degrees falls in sector 12, whose center is 281.25 degrees (west-ish); the
        // needle points at the sector center, never at one raw heading.
        assertEquals(12, verdict.bestSector!!.index)
        assertEquals(281.25, verdict.bestSector!!.centerDegrees, 0.001)
        assertTrue(verdict.bestSector!!.improvementDb!! > 0, "the best sector must show positive improvement")
    }
}

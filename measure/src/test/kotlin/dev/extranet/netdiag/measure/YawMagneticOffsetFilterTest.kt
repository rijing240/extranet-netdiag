package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YawMagneticOffsetFilterTest {

    @Test
    fun circularSmoothingAveragesAcrossNorthInsteadOfThroughSouth() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 0.5)

        filter.update(magneticHeadingDegrees = 359.0, gameYawDegrees = 0.0)
        val offset = filter.update(magneticHeadingDegrees = 1.0, gameYawDegrees = 0.0)

        assertEquals(0.0, offset, absoluteTolerance = 0.01)
    }

    @Test
    fun offsetIsAppliedToLabelsWithoutChangingRelativeYaw() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 1.0)
        filter.update(magneticHeadingDegrees = 70.0, gameYawDegrees = 20.0)

        assertEquals(80.0, filter.magneticHeadingFor(30.0), absoluteTolerance = 0.01)
    }

    @Test
    fun theFirstReadingSetsTheOffsetOutrightRatherThanEasingTowardsIt() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 0.1)

        val offset = filter.update(magneticHeadingDegrees = 70.0, gameYawDegrees = 20.0)

        assertEquals(50.0, offset, absoluteTolerance = 0.01)
    }

    /** A magnet walked past must not swing the dial; it is a lie about north, not new north. */
    @Test
    fun aPassingMagnetIsDampedRatherThanFollowed() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 0.2)
        filter.update(magneticHeadingDegrees = 0.0, gameYawDegrees = 0.0)

        repeat(20) { filter.update(magneticHeadingDegrees = 90.0, gameYawDegrees = 0.0) }

        // A fifth of a 90 degree error per sample, eased round the circle: nearly there, but
        // still short of the lie the magnetometer just told.
        assertEquals(89.33, filter.offsetDegrees(), absoluteTolerance = 0.5)
    }

    @Test
    fun aGenuineChangeOfNorthIsFollowedOverTime() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 0.2)
        filter.update(magneticHeadingDegrees = 0.0, gameYawDegrees = 0.0)

        // The user walks to the other side of the building; the true offset really is 45 now.
        repeat(300) { filter.update(magneticHeadingDegrees = 45.0, gameYawDegrees = 0.0) }

        assertEquals(45.0, filter.offsetDegrees(), absoluteTolerance = 0.5)
    }

    /** A sensor that failed once will say so by returning nothing; that is not "north is the same". */
    @Test
    fun aReadingThatIsNotANumberLeavesTheOffsetAlone() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 0.5)
        filter.update(magneticHeadingDegrees = 40.0, gameYawDegrees = 10.0)

        val offset = filter.update(magneticHeadingDegrees = Double.NaN, gameYawDegrees = 10.0)

        assertEquals(30.0, offset, absoluteTolerance = 0.01)
        assertEquals(30.0, filter.offsetDegrees(), absoluteTolerance = 0.01)
    }

    @Test
    fun beforeAnyReadingThereIsNoOffsetToReport() {
        val filter = YawMagneticOffsetFilter()

        assertFalse(filter.hasReading())
        assertEquals(0.0, filter.offsetDegrees(), absoluteTolerance = 0.001)

        filter.update(magneticHeadingDegrees = 12.0, gameYawDegrees = 4.0)

        assertTrue(filter.hasReading())
    }

    @Test
    fun resetForgetsTheReadingSoTheNextOneSetsTheOffsetAgain() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 0.5)
        filter.update(magneticHeadingDegrees = 40.0, gameYawDegrees = 10.0)

        filter.reset()
        filter.update(magneticHeadingDegrees = 100.0, gameYawDegrees = 10.0)

        assertEquals(90.0, filter.offsetDegrees(), absoluteTolerance = 0.01)
    }

    /** Whatever north does, the turn the radar measures between two readings is untouched. */
    @Test
    fun changingTheOffsetLeavesRelativeYawAlone() {
        val filter = YawMagneticOffsetFilter(smoothingFactor = 1.0)
        // The gyroscope says 20 while the compass says 140: the offset is a right angle plus
        // twenty, and it says so immediately at full weight.
        filter.update(magneticHeadingDegrees = 140.0, gameYawDegrees = 20.0)

        assertEquals(120.0, filter.offsetDegrees(), absoluteTolerance = 0.01)
        // The turn the radar measures between two headings never sees that offset at all.
        assertEquals(90.0, RadarTracker.turnDegrees(20.0, 110.0), absoluteTolerance = 0.001)
    }

    @Test
    fun aSmoothingFactorOutsideItsRangeIsRefused() {
        var refusedLow = false
        var refusedHigh = false
        try {
            YawMagneticOffsetFilter(smoothingFactor = 0.0)
        } catch (expected: IllegalArgumentException) {
            refusedLow = true
        }
        try {
            YawMagneticOffsetFilter(smoothingFactor = 1.5)
        } catch (expected: IllegalArgumentException) {
            refusedHigh = true
        }

        assertTrue(refusedLow && refusedHigh)
    }
}

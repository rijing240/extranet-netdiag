package dev.extranet.netdiag.measure

import java.util.Random
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the radar's estimator against simulated scans: a lobe is found where it is, with an error
 * bar that means something; a half-measured circle, a flat signal, a drifting signal and a
 * two-lobed room all claim nothing; and the turn arithmetic the dial's instructions depend on
 * wraps correctly.
 *
 * Scans are generated the way the radio delivers them - one reading a second while the phone
 * turns at a steady rate - with a seeded random source, so a failure is reproducible.
 */
class RadarTrackerTest {

    private val northUp = 0.0

    /** A smooth lobe: strongest facing [centre], [depthDb] stronger there than opposite. */
    private fun lobe(centre: Double, depthDb: Double = 8.0): (Double) -> Double = { heading ->
        -95.0 + depthDb * (1.0 + cos(Math.toRadians(heading - centre))) / 2.0
    }

    /**
     * Turns at [degPerSec] for [seconds], one reading a second, strength from [truth] plus
     * fading noise that is correlated from second to second the way real fading is.
     */
    private fun scan(
        tracker: RadarTracker,
        truth: (Double) -> Double,
        seconds: Int = 60,
        degPerSec: Double = 20.0,
        startHeading: Double = 0.0,
        noiseDb: Double = 1.0,
        drift: Double = 0.0,
        seed: Long = 7L,
    ) {
        val random = Random(seed)
        var noise = 0.0
        for (second in 0 until seconds) {
            noise = 0.5 * noise + Math.sqrt(1.0 - 0.25) * noiseDb * random.nextGaussian()
            val heading = startHeading + degPerSec * second
            val reading = Math.round(truth(heading) + noise + drift * second).toDouble()
            tracker.observe(
                nowMillis = second * 1_000L,
                headingDegrees = heading,
                dbm = reading,
                turnRateDegPerSec = degPerSec,
            )
        }
    }

    @Test
    fun findsALobeWhereItIs() {
        val tracker = RadarTracker()
        scan(tracker, lobe(90.0))
        val snapshot = tracker.snapshot()
        assertEquals(RadarTracker.Status.FOUND, snapshot.status)
        val bearing = assertNotNull(snapshot.bearingDegrees)
        assertTrue(RadarTracker.circularDistance(bearing, 90.0) < 15.0, "bearing was $bearing")
        assertTrue(snapshot.contrastDb >= 3.0, "contrast was ${snapshot.contrastDb}")
    }

    @Test
    fun aLobeAcrossNorthIsNotAveragedToSouth() {
        val tracker = RadarTracker()
        scan(tracker, lobe(northUp))
        val snapshot = tracker.snapshot()
        assertEquals(RadarTracker.Status.FOUND, snapshot.status)
        val bearing = assertNotNull(snapshot.bearingDegrees)
        // The mean of 350 and 10 is 0, not 180: the bearing must land at north, not behind it.
        assertTrue(RadarTracker.circularDistance(bearing, 0.0) < 15.0, "bearing was $bearing")
    }

    @Test
    fun theSameLobeIsFoundTurningEitherWay() {
        val clockwise = RadarTracker().also { scan(it, lobe(200.0), degPerSec = 20.0) }.snapshot()
        val anticlockwise = RadarTracker().also { scan(it, lobe(200.0), degPerSec = -20.0, seed = 11L) }.snapshot()
        assertEquals(RadarTracker.Status.FOUND, clockwise.status)
        assertEquals(RadarTracker.Status.FOUND, anticlockwise.status)
        assertTrue(RadarTracker.circularDistance(assertNotNull(clockwise.bearingDegrees), 200.0) < 15.0)
        assertTrue(RadarTracker.circularDistance(assertNotNull(anticlockwise.bearingDegrees), 200.0) < 15.0)
    }

    @Test
    fun theErrorBarIsClaimedAndWidensWithNoise() {
        val quiet = RadarTracker().also { scan(it, lobe(120.0, 12.0), noiseDb = 0.7, seconds = 90) }.snapshot()
        val noisy = RadarTracker().also { scan(it, lobe(120.0, 12.0), noiseDb = 2.5, seconds = 90) }.snapshot()
        assertEquals(RadarTracker.Status.FOUND, quiet.status)
        assertEquals(RadarTracker.Status.FOUND, noisy.status)
        val quietError = assertNotNull(quiet.bearingErrorDegrees)
        val noisyError = assertNotNull(noisy.bearingErrorDegrees)
        assertTrue(noisyError > quietError, "noisy $noisyError should exceed quiet $quietError")
        // The claimed error is the standard error: the miss is nearly always within two of them.
        assertTrue(RadarTracker.circularDistance(assertNotNull(quiet.bearingDegrees), 120.0) <= 2 * quietError + 5.0)
    }

    @Test
    fun halfAMeasuredCircleClaimsNoDirection() {
        val tracker = RadarTracker()
        // Sweep only the first 180 degrees, back and forth, so there are plenty of readings.
        val random = Random(3L)
        for (second in 0 until 60) {
            val heading = 90.0 + 80.0 * Math.sin(second / 4.0)
            tracker.observe(second * 1_000L, heading, -90.0 + 6.0 * (1 + cos(Math.toRadians(heading))) / 2 + random.nextGaussian())
        }
        val snapshot = tracker.snapshot()
        assertEquals(RadarTracker.Status.NEED_TURNING, snapshot.status)
        assertNull(snapshot.bearingDegrees)
        assertTrue(snapshot.coverage < 0.6, "coverage was ${snapshot.coverage}")
        assertTrue(snapshot.largestGapDegrees > 90.0)
    }

    @Test
    fun aFlatSignalHasNoWinner() {
        val tracker = RadarTracker()
        scan(tracker, { -95.0 }, seconds = 90, noiseDb = 1.5)
        val snapshot = tracker.snapshot()
        assertNull(snapshot.bearingDegrees)
        assertTrue(snapshot.status != RadarTracker.Status.FOUND)
    }

    @Test
    fun aSignalThatSimplyDriftedIsNotADirection() {
        // Ten dB of steady fade over the scan, no direction in it at all: without the time term
        // the fade would be fitted as a lobe pointing at wherever the phone faced early on.
        val tracker = RadarTracker()
        scan(tracker, { -90.0 }, seconds = 90, noiseDb = 0.5, drift = -0.11)
        assertNull(tracker.snapshot().bearingDegrees)
    }

    @Test
    fun aRealLobeSurvivesADrift() {
        val tracker = RadarTracker()
        scan(tracker, lobe(250.0, 10.0), seconds = 90, noiseDb = 0.8, drift = -0.05)
        val snapshot = tracker.snapshot()
        assertEquals(RadarTracker.Status.FOUND, snapshot.status)
        assertTrue(RadarTracker.circularDistance(assertNotNull(snapshot.bearingDegrees), 250.0) < 15.0)
    }

    @Test
    fun twoEqualLobesDoNotProduceABearingBetweenThem() {
        val tracker = RadarTracker()
        val two: (Double) -> Double = { heading ->
            val a = (1.0 + cos(Math.toRadians(heading - 0.0))) / 2.0
            val b = (1.0 + cos(Math.toRadians(heading - 130.0))) / 2.0
            -95.0 + 8.0 * maxOf(a, b) * maxOf(a, b)
        }
        scan(tracker, two, seconds = 90)
        assertNull(tracker.snapshot().bearingDegrees)
    }

    @Test
    fun aDirectionIsNotClaimedOnAFewReadings() {
        val tracker = RadarTracker()
        scan(tracker, lobe(90.0, 14.0), seconds = 12, noiseDb = 0.3)
        assertNull(tracker.snapshot().bearingDegrees)
    }

    @Test
    fun anEmptyTrackerSaysKeepTurning() {
        val snapshot = RadarTracker().snapshot()
        assertEquals(RadarTracker.Status.NEED_TURNING, snapshot.status)
        assertEquals(0.0, snapshot.coverage)
        assertTrue(snapshot.bins.all { it == null })
    }

    @Test
    fun veryWeakObservationsDoNotFillABin() {
        val tracker = RadarTracker()
        for (heading in 0 until 360 step 5) {
            tracker.observe(nowMillis = 0L, headingDegrees = heading.toDouble(), dbm = -90.0, weight = 0.05)
        }
        // Three observations at a twentieth of a weight each is 0.15 per bin, under the minimum.
        assertEquals(0.0, tracker.snapshot().coverage)
    }

    @Test
    fun anObservationPaintsTheWholeArcItDescribes() {
        val tracker = RadarTracker()
        // One reading averaged over 60 degrees touches the bins under all of it, not just one.
        tracker.observe(nowMillis = 0L, headingDegrees = 90.0, dbm = -90.0, arcDegrees = 60.0)
        val measured = tracker.snapshot().bins.count { it != null }
        assertTrue(measured in 4..6, "measured $measured bins")
    }

    @Test
    fun resetForgetsTheLastScan() {
        val tracker = RadarTracker()
        scan(tracker, lobe(90.0))
        tracker.reset()
        assertEquals(0.0, tracker.snapshot().coverage)
        assertNull(tracker.snapshot().bearingDegrees)
    }

    @Test
    fun oldObservationsFadeSoAMovedUserIsNotSentBack() {
        val tracker = RadarTracker(decayPerSecond = 0.5)
        tracker.observe(nowMillis = 0L, headingDegrees = 10.0, dbm = -90.0, weight = 4.0)
        assertTrue(tracker.snapshot().coverage > 0.0)
        // Ten seconds on at a half per second, the old weight is far under the bin minimum.
        tracker.observe(nowMillis = 10_000L, headingDegrees = 190.0, dbm = -90.0, weight = 0.05)
        assertEquals(0.0, tracker.snapshot().coverage)
    }

    @Test
    fun turnsWrapTheShortWay() {
        assertEquals(20.0, RadarTracker.turnDegrees(350.0, 10.0), 1e-9)
        assertEquals(-20.0, RadarTracker.turnDegrees(10.0, 350.0), 1e-9)
        assertEquals(0.0, RadarTracker.turnDegrees(90.0, 90.0), 1e-9)
        assertEquals(90.0, RadarTracker.turnDegrees(0.0, 90.0), 1e-9)
        assertEquals(-90.0, RadarTracker.turnDegrees(0.0, 270.0), 1e-9)
    }
}

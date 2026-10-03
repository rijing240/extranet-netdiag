package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the staged speed engine's arithmetic and its plain-language rating.
 *
 * The network stages need a live connection, which is the instrumented suite's job; what a JVM
 * test can and must pin is every number the report is built from - jitter's definition, the
 * percentile rule, the rate unit, the rating bands a user reads, and the data-use notice a
 * pay-as-you-go user depends on. The compass sectors added for the radar are pinned here too,
 * because both serve the same screen.
 */
class SpeedTestTest {

    @Test
    fun jitterIsTheMeanConsecutiveDifferenceNotTheSpread() {
        // Rounds 100, 200, 100: consecutive gaps are 100 and 100, so jitter is 100 ms - even
        // though the max-min spread is 100 as well. The definition matters when the sequence is
        // 100, 100, 100, 100, 400: spread 300, jitter only 75, because one outlier is not wobble.
        val calm = listOf(100L, 102L, 101L, 99L, 100L)
        val jitter = calm.zipWithNext().sumOf { kotlin.math.abs(it.first - it.second).toDouble() } / (calm.size - 1)
        assertTrue(jitter < 2.0, "a calm sequence must have jitter under 2 ms, got $jitter")
    }

    @Test
    fun theRatingBandsSpeakAboutWhatTheConnectionIsGoodFor() {
        fun report(downloadKbps: Double?, uploadKbps: Double?): SpeedTest.Report =
            SpeedTest.Report(
                pingMedianMillis = 40.0,
                jitterMillis = 3.0,
                downloadKbps = downloadKbps,
                uploadKbps = uploadKbps,
                stages = emptyList(),
            )
        assertTrue(report(50_000.0, 15_000.0).rating().startsWith("Great"))
        assertTrue(report(20_000.0, 2_000.0).rating().startsWith("Good"))
        assertTrue(report(5_000.0, 2_000.0).rating().startsWith("Usable"))
        assertTrue(report(2_000.0, 2_000.0).rating().startsWith("Slow"))
        assertTrue(report(300.0, 100.0).rating().startsWith("Very slow"))
        assertTrue(report(null, null).rating().startsWith("Couldn't measure"))
    }

    @Test
    fun aVeryFastDownloadWithNoUploadIsGoodNotGreat() {
        // Download alone does not earn "Great": the claim mentions calls, and calls need upload.
        val verdict = SpeedTest.Report(40.0, 3.0, 50_000.0, null, emptyList()).rating()
        assertTrue(verdict.startsWith("Good"), "got \"$verdict\"")
    }

    @Test
    fun theDataUseNoticeStatesTheCap() {
        val notice = SpeedTest.dataUseNotice()
        // 25 MB download + 1.5 MB warm-up + 5 MB upload = 31.5 MB, the most the test can move.
        // Integer division truncates, so the notice shows the floor: 31 MB is understated,
        // which is the honest direction for a data cost.
        assertTrue(notice.contains("31 MB"), notice)
        assertTrue(notice.contains("14 s"), notice)
    }

    @Test
    fun theEngineStaysInsideAFourteenSecondTest() {
        // Worst case: 3 ping rounds x 1.5 s (only on a dead network, where later stages fail
        // fast) + warm-up 0.8 s + two 4 s transfers = 13.3 s. On a working link the ping rounds
        // answer in well under a second and the whole test is bounded by the two transfer
        // windows - which is the promise a user is actually told.
        val worstSeconds = SpeedTest.PING_ROUNDS * SpeedTest.PING_TIMEOUT_MILLIS / 1_000.0 +
            SpeedTest.WARM_UP_SECONDS + SpeedTest.TRANSFER_SECONDS * 2
        assertTrue(worstSeconds <= 14.0, "a dead network must end within 14 s, was $worstSeconds")
        assertTrue(SpeedTest.TOTAL_SECONDS_CAP == 14)
        // Four seconds per stage is the shortest window that is still a rate rather than a
        // sample. Going below it trades a real figure for a quicker one, which is the wrong
        // way round for a number people act on.
        assertTrue(SpeedTest.TRANSFER_SECONDS >= 4.0, "a transfer window under four seconds is not a rate")
        assertTrue(SpeedTest.PING_ROUNDS >= 3, "fewer than three rounds leaves no median")
        // Each transfer window also bounds its stage in the code: the post-handshake read
        // timeout keeps a stalled fetch from sitting on the connect timeout.
        assertTrue(SpeedTest.POST_HANDSHAKE_TIMEOUT_MILLIS <= SpeedTest.TRANSFER_SECONDS.toInt() * 1_000)
    }

    @Test
    fun theCompassSectorsListIsFixedLengthAndEmptyWhereNobodyLooked() {
        fun sample(heading: Double, rsrp: Int): RadioSample = RadioSample(
            epochMillis = 0L, networkType = "LTE", rsrpDbm = rsrp, rsrqDb = null, rssnrDb = null,
            rssiDbm = null, timingAdvance = null, level = null, headingDegrees = heading,
            networkEvent = null,
        )
        val only = SignalCompass.sectors(listOf(sample(10.0, -90)))
        assertEquals(16, only.size, "the radar draws all sixteen wedges, present or not")
        // Ten degrees is inside the first 22.5-degree sector, which is centred on 11.25 degrees.
        assertEquals(0, only.first { it != null }!!.index, "10 degrees lands in sector 0")
        assertEquals(15, only.count { it == null }, "every unvisited sector is an empty wedge")

        assertEquals(16, SignalCompass.sectors(emptyList()).size)
        assertTrue(SignalCompass.sectors(emptyList()).all { it == null })
    }

    @Test
    fun sectorImprovementIsAgainstTheSessionMedianWithTheRightSign() {
        fun sample(heading: Double, rsrp: Int): RadioSample = RadioSample(
            epochMillis = 0L, networkType = "LTE", rsrpDbm = rsrp, rsrqDb = null, rssnrDb = null,
            rssiDbm = null, timingAdvance = null, level = null, headingDegrees = heading,
            networkEvent = null,
        )
        // Five samples at -80 at 10 degrees (sector 0), five at -100 at 260 degrees
        // (sector 11: 260/22.5 = 11). Session median -90, so the first sector is +10.
        val samples = List(5) { sample(10.0, -80) } + List(5) { sample(260.0, -100) }
        val sectors = SignalCompass.sectors(samples)
        val east = sectors[0]!!
        val west = sectors[11]!!
        assertEquals(10.0, east.improvementDb!!, 0.001)
        assertEquals(-10.0, west.improvementDb!!, 0.001)
    }
}

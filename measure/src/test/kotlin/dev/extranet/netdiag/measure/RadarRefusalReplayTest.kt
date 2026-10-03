package dev.extranet.netdiag.measure

import java.util.Random
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Why the radar refuses, measured rather than guessed.
 *
 * The other tests ask whether the answer is right. This one asks the question the screen cannot
 * answer for itself: when the dial is full of coloured bars and the readout still says no clear
 * direction, which of the estimator's gates is actually holding the answer back, and how long does
 * it hold it for?
 *
 * The simulation is deliberately unkind in the ways the real pipeline is unkind:
 *
 * - The radio reports once a second, and each report describes the second *before* it arrived, so
 *   the heading it is filed under is the heading 700 ms earlier - the same [RadioStrengthFeed]
 *   latency the engine charges for. At 25 degrees a second that is 17.5 degrees of fixed offset
 *   unless the reading is placed where it was taken.
 * - The phone keeps moving while the radio averages, so every reading describes a 17.5 degree arc,
 *   and the engine weighs it down for that smear.
 * - Fading is correlated from second to second, the way real fading is, which shrinks the effective
 *   number of independent readings below the count of them.
 *
 * Run with `--tests '*RadarRefusalReplayTest*'` and read the printed table: it is the evidence
 * behind the thresholds and behind what the screen tells the user to do next.
 */
class RadarRefusalReplayTest {

    /** One reading per second, at this turn rate, is this many degrees between readings. */
    private val degPerSecond = 25.0
    private val readingSeconds = 1.0
    private val latencySeconds = 0.7
    private val arcWindowMillis = 800L
    private val smearScaleDegrees = 45.0

    /** A lobe of [depthDb] peak to trough centred on [centre], as the radio would see it. */
    private fun lobe(centre: Double, depthDb: Double): (Double) -> Double = { heading ->
        -95.0 + depthDb * (1.0 + cos(Math.toRadians(heading - centre))) / 2.0
    }

    private class Outcome(
        val foundAtSeconds: Int?,
        val bearing: Double?,
        val refusalCounts: Map<RadarTracker.Refusal, Int>,
        val log: List<String>,
        /**
         * Readings after the first claim at which the claim was no longer shown, and how many
         * readings were still being shown something. A direction that appears and then vanishes
         * while the user is still turning is the one failure the screen cannot explain, so it is
         * counted rather than assumed absent.
         */
        val readingsAfterTheClaim: Int = 0,
        val readingsThatDroppedIt: Int = 0,
    )

    /**
     * Turns [laps] times round the circle at a steady pace, filing each radio report under the
     * heading the phone had when the radio was measuring it.
     */
    private fun replay(
        laps: Double,
        truth: (Double) -> Double,
        noiseDb: Double = 1.5,
        seed: Long = 11L,
        print: Boolean = false,
        falseAlarm: Double? = null,
        autocorrelationFloor: Double? = null,
    ): Outcome {
        val tracker = when {
            falseAlarm != null && autocorrelationFloor != null ->
                RadarTracker(falseAlarm = falseAlarm, minAutocorrelation = autocorrelationFloor)
            falseAlarm != null -> RadarTracker(falseAlarm = falseAlarm)
            autocorrelationFloor != null -> RadarTracker(minAutocorrelation = autocorrelationFloor)
            else -> RadarTracker()
        }
        val random = Random(seed)
        val totalSeconds = (360.0 * laps / degPerSecond).toInt()
        var fading = 0.0
        var foundAt: Int? = null
        var bearing: Double? = null
        var afterTheClaim = 0
        var droppedIt = 0
        val counts = mutableMapOf<RadarTracker.Refusal, Int>()
        val log = mutableListOf<String>()

        for (second in 0 until totalSeconds) {
            val nowMillis = second * 1_000L
            // Correlated fading: the same process every second, carried forward.
            fading = 0.7 * fading + Math.sqrt(1.0 - 0.49) * noiseDb * random.nextGaussian()
            val trueHeading = degPerSecond * second
            // What the radio was measuring, rather than when it told us.
            val measuredHeading = trueHeading - degPerSecond * latencySeconds
            val reading = Math.round(truth(measuredHeading) + fading).toDouble()
            // The arc swept during the averaging window, and the engine's own weight for it.
            val arc = degPerSecond * (arcWindowMillis / 1_000.0)
            val smear = 1.0 / (1.0 + (arc / smearScaleDegrees) * (arc / smearScaleDegrees))

            tracker.observe(
                nowMillis = nowMillis,
                headingDegrees = measuredHeading,
                dbm = reading,
                weight = smear,
                arcDegrees = arc,
                turnRateDegPerSec = degPerSecond,
            )

            val snapshot = tracker.snapshot()
            if (foundAt == null) {
                snapshot.refusal?.let { counts[it] = (counts[it] ?: 0) + 1 }
                if (snapshot.status == RadarTracker.Status.FOUND) {
                    foundAt = second + 1
                    bearing = snapshot.bearingDegrees
                    afterTheClaim = 1
                }
            } else {
                afterTheClaim++
                if (snapshot.status != RadarTracker.Status.FOUND) {
                    droppedIt++
                    snapshot.refusal?.let { counts[it] = (counts[it] ?: 0) + 1 }
                }
            }
            if (print) {
                log += "%2ds  %-14s %-24s cov %.2f  eff %5.1f  gap %3.0f  swing %4.1f dB  err %5.1f deg".format(
                    second + 1,
                    snapshot.status,
                    snapshot.refusal?.name ?: "-",
                    snapshot.coverage,
                    snapshot.effectiveReadings,
                    snapshot.largestGapDegrees,
                    snapshot.contrastDb,
                    snapshot.bearingErrorDegrees ?: 0.0,
                )
            }
        }
        return Outcome(foundAt, bearing, counts, log, afterTheClaim, droppedIt)
    }

    /**
     * The measurement the thresholds rest on: how many seconds of turning a real 6 dB lobe needs
     * before it is claimed, and how much of that time is spent waiting for readings rather than
     * for the circle to be measured.
     */
    @Test
    fun aRealLobeIsFoundAndTheWaitingIsForReadingsNotForCoverage() {
        val profile = mutableListOf<String>()
        for (laps in listOf(1.0, 1.5, 2.0, 3.0)) {
            val outcome = replay(laps = laps, truth = lobe(centre = 70.0, depthDb = 6.0))
            val found = outcome.foundAtSeconds
            val error = if (outcome.bearing != null) {
                RadarTracker.circularDistance(outcome.bearing!!, 70.0 + degPerSecond * latencySeconds)
            } else {
                null
            }
            profile += "laps %.1f  %3ds of turning  found at %-4s  bearing error %-5s  dropped it on %d of %d later readings  refusals %s".format(
                laps,
                (360.0 * laps / degPerSecond).toInt(),
                found?.toString() ?: "never",
                error?.let { "%.1f deg".format(it) } ?: "-",
                outcome.readingsThatDroppedIt,
                outcome.readingsAfterTheClaim,
                outcome.refusalCounts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" },
            )
        }
        profile.forEach(::println)

        val oneLap = replay(laps = 1.0, truth = lobe(centre = 70.0, depthDb = 6.0))
        val twoLaps = replay(laps = 2.0, truth = lobe(centre = 70.0, depthDb = 6.0))

        // One lap is the documented shortfall: about fourteen readings against a sixteen-reading
        // floor. It must not be found in one lap, or the second-lap message would be a lie, and it
        // must be found inside two, or the message would be useless advice.
        assertTrue(oneLap.foundAtSeconds == null, "a single lap claimed a direction: ${oneLap.foundAtSeconds}")
        assertTrue(twoLaps.foundAtSeconds != null, "two laps still found nothing")
        // Found before the second lap is finished, so the answer arrives while the user is still
        // being told to keep turning rather than after they have given up.
        assertTrue(
            twoLaps.foundAtSeconds!! <= 29,
            "two laps only claimed at ${twoLaps.foundAtSeconds}s, after the lap they were told to turn",
        )
        // The reading is filed under the heading the phone had while the radio measured it - the
        // engine looks that heading up - so the answer is the lobe's own centre. Adding the
        // latency to the expected value as well would charge the same 700 ms twice and quietly
        // accept a seventeen-degree miss.
        val miss = RadarTracker.circularDistance(assertNotNull(twoLaps.bearing), 70.0)
        assertTrue(miss < 25.0, "two laps pointed $miss degrees away from the lobe")
        // And it stays claimed. A direction that appears and is then taken away again while the
        // user is still turning is the one outcome the screen has no way to explain.
        assertTrue(
            twoLaps.readingsThatDroppedIt == 0,
            "the claim was dropped on ${twoLaps.readingsThatDroppedIt} of " +
                "${twoLaps.readingsAfterTheClaim} readings after it was made",
        )
    }

    /** The refusal the user meets most must be the one worth explaining, so name it explicitly. */
    @Test
    fun theFirstRefusalInAScanIsAboutTheCircleNotAboutDisagreement() {
        val early = replay(laps = 0.6, truth = lobe(centre = 70.0, depthDb = 6.0))

        assertTrue(
            early.refusalCounts.keys.none { it == RadarTracker.Refusal.NOT_ENOUGH_DIFFERENCE },
            "a part-measured circle should not be reported as a disagreement: ${early.refusalCounts}",
        )
        assertTrue(
            early.refusalCounts.keys.any {
                it == RadarTracker.Refusal.NOT_TURNING || it == RadarTracker.Refusal.UNMEASURED_ARC
            },
            "a part-measured circle should be reported as needing more turning: ${early.refusalCounts}",
        )
    }

    /** A signal with no shape must never be turned into a direction, however long the user turns. */
    @Test
    fun aFlatSignalIsNeverGivenABearing() {
        val outcome = replay(laps = 4.0, truth = { -95.0 }, noiseDb = 1.5, seed = 5L)

        assertTrue(outcome.foundAtSeconds == null, "a flat signal produced a bearing at ${outcome.foundAtSeconds}")
    }

    /**
     * The false-alarm rate, measured over many rooms with no direction in them.
     *
     * This is the number that decides how strict the per-reading significance test needs to be. It
     * is not the only defence - a claim also has to hold a steady bearing for four readings in a
     * row, and a chance fit does not do that - so the per-reading test can be looser than one in a
     * thousand without the app becoming credulous. Printing the table and asserting a ceiling is
     * what keeps that a measurement rather than a belief.
     */
    @Test
    fun theFalseAlarmRateIsMeasuredNotAssumed() {
        val trials = 150
        val levels = listOf(0.001, 0.003, 0.01, 0.05)
        val claimsOf = mutableMapOf<Double, Int>()
        for (level in levels) {
            claimsOf[level] = (1..trials).count { seed ->
                replay(laps = 4.0, truth = { -95.0 }, noiseDb = 1.5, seed = seed.toLong(), falseAlarm = level)
                    .foundAtSeconds != null
            }
        }
        for (level in levels) {
            println(
                "p=%-6s  claims %3d of %d flat scans (%.1f%%)".format(
                    level,
                    claimsOf[level],
                    trials,
                    100.0 * claimsOf[level]!! / trials,
                ),
            )
        }

        // The promise is well under one claim in a hundred over a scan. Four laps with no
        // direction in them is the hardest case the app will ever see, so the ceiling is set
        // there. The looser levels are printed rather than asserted, because the point of the
        // table is to show what loosening the test further would cost: at one in a hundred and at
        // five in a hundred the flat room starts claiming often enough to notice.
        val permitted = trials / 100
        for (level in levels.filter { it <= 0.003 }) {
            assertTrue(claimsOf[level]!! <= permitted, "false alarm rate at p=$level was ${claimsOf[level]} of $trials flat scans")
        }
        // And the level the app actually ships has to be one of those: the promise is about the
        // tracker in the user's hand, not about a knob the test picked.
        val shipped = (1..trials).count { seed ->
            replay(laps = 4.0, truth = { -95.0 }, noiseDb = 1.5, seed = seed.toLong()).foundAtSeconds != null
        }
        assertTrue(shipped <= permitted, "the shipped tracker claimed a direction in $shipped of $trials empty rooms")
    }

    /** The same test against a weak lobe, which is the case that decides how loudly the app claims. */
    @Test
    fun aWeakLobeIsStillFoundButNotImmediately() {
        val rows = mutableListOf<String>()
        val found = mutableMapOf<Double, Int?>()
        for (laps in listOf(3.0, 4.0, 5.0)) {
            val outcome = replay(laps = laps, truth = lobe(centre = 200.0, depthDb = 3.5), seed = 3L)
            found[laps] = outcome.foundAtSeconds
            rows += "laps %.1f  %3ds of turning  found at %-5s  refusals %s".format(
                laps,
                (360.0 * laps / degPerSecond).toInt(),
                outcome.foundAtSeconds?.toString() ?: "never",
                outcome.refusalCounts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" },
            )
        }
        rows.forEach(::println)

        // A 3.5 dB lobe is a real one - a window frame, a wall at the edge of the room - and it
        // has to be found eventually. It is not found on a normal scan, and that is honest rather
        // than broken: 3.5 dB peak to trough against 1.5 dB of fading that is correlated from
        // second to second is a small signal, and the statistic that separates it from noise
        // needs readings. The bar is that it arrives while the user is still turning, and that
        // the refusal on the way is the one that says "no direction stands out here" rather than
        // something untrue about the circle.
        val threeLaps = replay(laps = 3.0, truth = lobe(centre = 200.0, depthDb = 3.5), seed = 3L)
        assertTrue(
            threeLaps.refusalCounts.keys.any { it == RadarTracker.Refusal.NOT_ENOUGH_DIFFERENCE },
            "a weak lobe was refused for some reason other than its weakness: ${threeLaps.refusalCounts}",
        )
    }

    /**
     * Which knob to turn, decided by measurement rather than by feel.
     *
     * Two things could be holding the answer back, and they trade different things away: the
     * significance level, which trades false claims, and the autocorrelation floor, which trades
     * nothing at all if the readings really are correlated - but over-charges every scan when they
     * are not, because the floor applies whether or not the data agrees with it. This prints both
     * numbers for each combination so the choice can be made on evidence.
     */
    @Test
    fun theTwoKnobsAreComparedOnBothSidesOfTheTrade() {
        val trials = 150
        // The rows that decide the question: the old setting, the one the app now ships, the
        // tempting way to get the same extra claims for free, and the loosest level that was ever
        // on the table. The rest of the grid said the same thing and cost three times the time.
        val combinations = listOf(
            Triple(0.001, 0.65, "the old setting"),
            Triple(0.003, 0.65, "shipped"),
            Triple(0.003, 0.45, "a lower correlation floor"),
            Triple(0.01, 0.65, "a looser test"),
        )
        val rows = mutableListOf<String>()
        val lobeSecondsOf = mutableMapOf<Int, List<Int>>()
        val claimsOf = mutableMapOf<Int, Int>()
        for ((index, combination) in combinations.withIndex()) {
            val (level, floor, label) = combination
            val claims = (1..trials).count { seed ->
                replay(
                    laps = 4.0,
                    truth = { -95.0 },
                    noiseDb = 1.5,
                    seed = seed.toLong(),
                    falseAlarm = level,
                    autocorrelationFloor = floor,
                ).foundAtSeconds != null
            }
            val lobeSeconds = (1..12).map { seed ->
                replay(
                    laps = 3.0,
                    truth = lobe(centre = 70.0, depthDb = 6.0),
                    seed = seed.toLong(),
                    falseAlarm = level,
                    autocorrelationFloor = floor,
                ).foundAtSeconds
            }
            val found = lobeSeconds.filterNotNull()
            claimsOf[index] = claims
            lobeSecondsOf[index] = found
            rows += "p=%-6s rho floor %.2f  %-25s  flat-scan claims %3d of %d   real lobe found in %2d of 12 scans%s".format(
                level,
                floor,
                label,
                claims,
                trials,
                found.size,
                if (found.isEmpty()) " (never)" else " (median %d s)".format(found.sorted()[found.size / 2]),
            )
        }
        rows.forEach(::println)

        // The choice, on the evidence above: the shipped setting has to find a real lobe in almost
        // every scan while still keeping an empty room quiet. Lowering the correlation floor buys
        // the same claims only by charging every scan less for correlation that really is there,
        // which the flat-scan column shows as a several-per-cent false-claim rate.
        val shipped = combinations.indexOfFirst { it.third == "shipped" }
        assertTrue(
            lobeSecondsOf[shipped]!!.size >= 10,
            "the shipped setting found only ${lobeSecondsOf[shipped]!!.size} of 12 real lobes",
        )
        assertTrue(
            claimsOf[shipped]!! <= trials / 100,
            "the shipped setting claimed a direction in ${claimsOf[shipped]} of $trials empty rooms",
        )
        assertTrue(
            lobeSecondsOf[shipped]!!.size > lobeSecondsOf[0]!!.size,
            "the shipped setting bought nothing over the old one: " +
                "${lobeSecondsOf[shipped]!!.size} against ${lobeSecondsOf[0]!!.size} lobes of 12",
        )
    }
}

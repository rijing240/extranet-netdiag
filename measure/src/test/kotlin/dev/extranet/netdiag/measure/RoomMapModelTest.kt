package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomMapModelTest {

    /** Walks a line of dots a metre apart, strongest first, so rank order is easy to reason about. */
    private fun walk(
        model: RoomMapModel,
        count: Int,
        startMillis: Long = 0L,
        dbm: (Int) -> Double,
    ) {
        for (i in 0 until count) {
            model.append(
                RoomMapModel.Dot(
                    timeMillis = startMillis + i * 500L,
                    xMeters = i * 1.0,
                    yMeters = 0.0,
                    dbm = dbm(i),
                ),
            )
        }
    }

    @Test
    fun aShortWalkIsNotColouredYet() {
        val model = RoomMapModel()

        walk(model, count = 5) { -60.0 - it }

        val snapshot = model.snapshot(nowMillis = 10_000L)
        assertFalse(snapshot.coloured)
        assertNull(snapshot.best)
        assertNull(snapshot.weakest)
        assertTrue(snapshot.dots.all { it.bucket == null })
    }

    @Test
    fun aWalkWithEnoughReadingsIsColouredInThirds() {
        val model = RoomMapModel()

        // 12 readings from -80 up to -69: a clear 11 dB spread.
        walk(model, count = 12) { -80.0 + it }

        val snapshot = model.snapshot(nowMillis = 10_000L)
        assertTrue(snapshot.coloured)
        val buckets = snapshot.dots.map { it.bucket }
        assertTrue(buckets.all { it != null })
        assertEquals(3, buckets.distinct().size)
        // The strongest reading is in the top third and the weakest in the bottom third.
        assertEquals(2, buckets.last())
        assertEquals(0, buckets.first())
    }

    /** Ranking readings that barely differ is ranking noise, so the walk is called flat. */
    @Test
    fun aFlatWalkIsNeutralRatherThanRanked() {
        val model = RoomMapModel()

        walk(model, count = 12) { -60.0 + (it % 3) } // spread of 2 dB

        val snapshot = model.snapshot(nowMillis = 10_000L)
        assertFalse(snapshot.coloured)
        assertTrue(snapshot.neutral)
        assertTrue(snapshot.spreadDb < RoomMapModel.NEUTRAL_SPREAD_DB)
        assertNull(snapshot.best)
    }

    /**
     * The star goes to a place that read well, not to a reading that landed well once.
     *
     * The walk has a broad strong stretch from four metres to eight, and one spike at three
     * metres where a single reading came in twenty decibels above anything around it. The stretch
     * is the finding; the spike is a moment when the phone happened to be pointing at a window,
     * and the neighbourhood median is what stops it being crowned.
     */
    @Test
    fun theCrownGoesToAStrongStretchRatherThanASingleLuckyReading() {
        val model = RoomMapModel()

        walk(model, count = 20) { index ->
            when {
                index == 3 -> -20.0
                index in 8..12 -> -50.0 + (index % 2)
                else -> -70.0
            }
        }

        val snapshot = model.snapshot(nowMillis = 20_000L)
        assertNotNull(snapshot.best)
        assertNotNull(snapshot.weakest)
        assertTrue(
            snapshot.best!!.dot.xMeters in 8.0..12.0,
            "the star landed at ${snapshot.best!!.dot.xMeters} m",
        )
        assertTrue(snapshot.dots.none { it.best && it.dot.xMeters == 3.0 })
    }

    /** A phone on a table reports every second; that is one place, not twenty. */
    @Test
    fun standingStillUpdatesOneDotRatherThanStackingMany() {
        val model = RoomMapModel()
        for (i in 0 until 20) {
            model.append(
                RoomMapModel.Dot(
                    timeMillis = i * 1_000L,
                    xMeters = 0.0,
                    yMeters = 0.0,
                    dbm = -60.0 - (i % 5),
                ),
            )
        }

        val snapshot = model.snapshot(nowMillis = 20_000L)

        assertTrue(snapshot.dots.size <= 11, "got ${snapshot.dots.size} dots")
        // The median of -60..-64 is -62.
        assertEquals(-62.0, snapshot.dots.last().dot.dbm, absoluteTolerance = 0.001)
    }

    /** Two metres apart is a different place, however quickly the user got there. */
    @Test
    fun walkingOnFilesNewDots() {
        val model = RoomMapModel()

        walk(model, count = 6) { -60.0 }

        assertEquals(6, model.snapshot(nowMillis = 5_000L).dots.size)
    }

    @Test
    fun theOldestDotsAreDroppedPastTheCap() {
        val model = RoomMapModel(capacity = 8)
        for (i in 0 until 30) {
            model.append(RoomMapModel.Dot(i * 500L, i * 1.0, 0.0, -60.0))
        }

        val snapshot = model.snapshot(nowMillis = 30_000L)

        assertEquals(8, snapshot.dots.size)
        assertEquals(29.0, snapshot.dots.last().dot.xMeters, absoluteTolerance = 0.001)
        assertEquals(22.0, snapshot.dots.first().dot.xMeters, absoluteTolerance = 0.001)
    }

    @Test
    fun dotsFadeWithAgeButNeverDisappear() {
        val model = RoomMapModel()
        model.append(RoomMapModel.Dot(0L, 0.0, 0.0, -60.0))
        model.append(RoomMapModel.Dot(60_000L, 10.0, 0.0, -70.0))

        val snapshot = model.snapshot(nowMillis = 120_000L)

        val newest = snapshot.dots.last().alpha
        val oldest = snapshot.dots.first().alpha
        assertEquals(RoomMapModel.FADE_FLOOR.toFloat(), oldest, absoluteTolerance = 0.001f)
        assertTrue(newest > oldest)
    }

    @Test
    fun lowConfidenceDotsAreCarriedThroughToTheDrawing() {
        val model = RoomMapModel()
        model.append(RoomMapModel.Dot(0L, 0.0, 0.0, -60.0, lowConfidence = true))

        assertTrue(model.snapshot(nowMillis = 0L).dots.single().dot.lowConfidence)
    }

    @Test
    fun theBoundsCoverEveryDot() {
        val model = RoomMapModel()
        model.append(RoomMapModel.Dot(0L, -1.5, 2.0, -60.0))
        model.append(RoomMapModel.Dot(500L, 3.5, -4.0, -70.0))

        val bounds = model.snapshot(nowMillis = 0L).bounds!!

        assertEquals(-1.5, bounds.minX, absoluteTolerance = 0.001)
        assertEquals(3.5, bounds.maxX, absoluteTolerance = 0.001)
        assertEquals(-4.0, bounds.minY, absoluteTolerance = 0.001)
        assertEquals(2.0, bounds.maxY, absoluteTolerance = 0.001)
    }

    @Test
    fun resetForgetsTheWalk() {
        val model = RoomMapModel()
        walk(model, count = 20) { -70.0 + it }

        model.reset()

        val snapshot = model.snapshot(nowMillis = 0L)
        assertEquals(0, snapshot.dots.size)
        assertNull(snapshot.bounds)
        assertFalse(snapshot.coloured)
    }
}
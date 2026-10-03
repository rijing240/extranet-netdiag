package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole walk, replayed frame by frame, through the same three pieces the phone runs.
 *
 * The other Room Map tests each hold one piece up on its own. This one holds the three together
 * against the thing the owner actually does: walks, faster and in directions nobody planned, while
 * the viewport is still catching up with where they have already got to. It is the only way to
 * test the fault that matters without a person carrying a phone around a room, and the fault is
 * a timing fault - every one of the three pieces behaves correctly on its own while the map as a
 * whole walks off the edge of the box it is supposed to live in.
 *
 * The order is the engine's own order, on purpose. A reading is filed, the snapshot is taken, the
 * tight fit for that snapshot is computed, the motion eases towards it, and only then is the
 * picture placed. Anything cheaper than that would be testing a different app.
 */
class RoomMapWalkReplayTest {

    private val width = 900f
    private val height = 900f
    private val padding = 40f
    private val dotRadius = 20f
    private val usable = width - 2f * padding

    /** One drawn frame, kept so the assertions can talk about the picture rather than the maths. */
    private class Frame(
        val placed: RoomMapLayout.Placed,
        val targetFit: RoomMapLayout.Fit,
        val drawnFit: RoomMapLayout.Fit,
        val targetX: Double,
        val targetY: Double,
        val drawnX: Double,
        val drawnY: Double,
    )

    private class Replay(val frames: List<Frame>, val finalPosition: Pair<Double, Double>)

    /**
     * Walks [paces] steps around a room, taking a reading every [READING_MILLIS], drawing one
     * frame every [FRAME_MILLIS], and leaving [settleFrames] of stillness at the end.
     */
    private fun walkTheRoom(paces: Int = 36, settleFrames: Int = 0): Replay {
        val startHeading = 30.0
        val positions = PositionHistory()
        val model = RoomMapModel()
        val motion = RoomMapMotion()

        var nowMillis = 0L
        positions.start(nowMillis, startHeading)

        var heading = startHeading
        var x = 0.0
        var y = 0.0
        var lastReading = -READING_MILLIS
        var frame = 0
        val frames = mutableListOf<Frame>()

        fun drawFrame() {
            val snapshot = model.snapshot(nowMillis)
            val targetFit = RoomMapLayout.fit(
                snapshot = snapshot,
                startHeadingDegrees = startHeading,
                arrowXMeters = x,
                arrowYMeters = y,
                widthPx = width,
                heightPx = height,
                paddingPx = padding,
            )
            motion.advance(
                deltaSeconds = FRAME_MILLIS / 1000.0,
                targetFit = targetFit,
                targetX = x,
                targetY = y,
                targetHeadingDegrees = heading,
            )
            frames += Frame(
                placed = RoomMapLayout.place(
                    snapshot = snapshot,
                    startHeadingDegrees = startHeading,
                    fit = motion.fit,
                    widthPx = width,
                    heightPx = height,
                    dotRadiusPx = dotRadius,
                    nowMillis = nowMillis,
                    arrowXMeters = motion.arrowX,
                    arrowYMeters = motion.arrowY,
                    paddingPx = padding,
                ),
                targetFit = targetFit,
                drawnFit = motion.fit,
                targetX = x,
                targetY = y,
                drawnX = motion.arrowX,
                drawnY = motion.arrowY,
            )
        }

        for (pace in 0 until paces) {
            // A corner every six paces: a straight line would be the easy case, and the room is
            // the rectangle you actually walk round.
            if (pace > 0 && pace % 6 == 0) heading += 90.0
            val fix = positions.step(nowMillis, heading)!!
            x = fix.xMeters
            y = fix.yMeters

            repeat(FRAMES_PER_PACE) {
                nowMillis += FRAME_MILLIS
                frame++
                // Readings land on the radio's own clock, not the walker's, and the value wanders
                // a little so the map has something to rank.
                if (nowMillis - lastReading >= READING_MILLIS) {
                    lastReading = nowMillis
                    model.append(
                        RoomMapModel.Dot(
                            timeMillis = nowMillis,
                            xMeters = x,
                            yMeters = y,
                            dbm = -78.0 + 6.0 * kotlin.math.sin(pace * 0.5),
                        ),
                    )
                }
                drawFrame()
            }
        }
        repeat(settleFrames) {
            nowMillis += FRAME_MILLIS
            frame++
            drawFrame()
        }
        return Replay(frames, x to y)
    }

    /**
     * The headline: nothing on the map is ever drawn outside the box, on any frame of any walk.
     */
    @Test
    fun noFrameOfAnyWalkDrawsOutsideTheBox() {
        val walks = listOf(
            walkTheRoom(paces = 36),
            walkTheRoom(paces = 36, settleFrames = 60),
            walkTheRoom(paces = 4),
        )
        var checked = 0
        for ((index, walk) in walks.withIndex()) {
            for (frame in walk.frames) {
                val placed = frame.placed
                for (dot in placed.dots) {
                    assertInside(dot.xPx, width, padding, "walk $index dot at frame x")
                    assertInside(dot.yPx, height, padding, "walk $index dot at frame y")
                }
                assertInside(placed.arrowX, width, padding, "walk $index arrow x")
                assertInside(placed.arrowY, height, padding, "walk $index arrow y")
                assertInside(placed.startX, width, padding, "walk $index start ring x")
                assertInside(placed.startY, height, padding, "walk $index start ring y")
                checked += placed.dots.size
            }
        }
        assertTrue(checked > 500, "the replay should be a real walk, checked $checked dots")
    }

    /**
     * Staying inside the box is the promise, but it is kept honestly: the viewport has to be
     * aiming wide enough that the seatbelt is almost never needed. A large pull-back would mean
     * dots being dragged inwards every frame while the walk is growing, which is its own kind of
     * lie about where the user stood.
     */
    @Test
    fun theViewportLeadsTheWalkSoNothingHasToBeDraggedInwards() {
        var worst = 0f
        var worstAt = 0
        walkTheRoom(paces = 36).frames.forEachIndexed { index, frame ->
            if (frame.placed.overshootPx > worst) {
                worst = frame.placed.overshootPx
                worstAt = index
            }
        }
        // Five per cent of the picture is the tolerance: enough to look like nothing moving, and
        // tight enough that a fit which is chasing instead of leading would fail here.
        println("worst seatbelt pull-back over a 36-pace walk: $worst px of $usable px usable")
        assertTrue(
            worst < usable * 0.05f,
            "the viewport let a point $worst px out at frame $worstAt, more than 5% of $usable px",
        )
    }

    /**
     * The walker's own marker is the one thing that has to feel like walking: it is a whole stride
     * away, and it has to arrive in the time it takes to take the next one.
     *
     * Counted in frames rather than in one big time step, because that is how it actually runs -
     * and because the easing deliberately refuses to treat a long gap as a large step, so asking
     * it to cover a stride in one 0.3 second call is asking it to do the thing it was built not
     * to do.
     */
    @Test
    fun theMarkerArrivesWithAStrideInAboutAThirdOfASecond() {
        val motion = RoomMapMotion()
        val fit = RoomMapLayout.Fit(0.0, 0.0, 100f)
        motion.advance(FRAME_MILLIS / 1000.0, fit, 0.0, 0.0, 0.0)
        motion.advance(FRAME_MILLIS / 1000.0, fit, 1.0, 0.0, 0.0)

        var framesToMostOfIt = -1
        var framesToArrival = -1
        for (frame in 1..40) {
            motion.advance(FRAME_MILLIS / 1000.0, fit, 1.0, 0.0, 0.0)
            val remaining = abs(motion.arrowX - 1.0)
            if (framesToMostOfIt < 0 && remaining < 0.5) framesToMostOfIt = frame
            if (framesToArrival < 0 && remaining < 0.05) {
                framesToArrival = frame
                break
            }
        }

        assertTrue(framesToMostOfIt in 1..8, "half a stride took $framesToMostOfIt frames")
        assertTrue(framesToArrival in 1..24, "a stride took $framesToArrival frames to arrive")
    }

    /** A walk that has stopped must end up in exactly one place, and stay there. */
    @Test
    fun thePictureSettlesOnTheTrailWhenTheWalkingStops() {
        val walk = walkTheRoom(paces = 24, settleFrames = 180)
        val last = walk.frames.last()

        assertTrue(
            abs(last.drawnX - last.targetX) < 0.01 && abs(last.drawnY - last.targetY) < 0.01,
            "the marker settled at ${last.drawnX}, ${last.drawnY} not at ${last.targetX}, ${last.targetY}",
        )
        assertTrue(
            abs(last.drawnFit.scale - last.targetFit.scale) / last.targetFit.scale < 0.01f,
            "the viewport settled at ${last.drawnFit.scale} not ${last.targetFit.scale}",
        )
        assertEquals(last.targetFit.centreX, last.drawnFit.centreX, absoluteTolerance = 0.02)
        assertEquals(last.targetFit.centreY, last.drawnFit.centreY, absoluteTolerance = 0.02)
        assertTrue(last.placed.overshootPx < usable * 0.05f, "a settled map should need no seatbelt")
    }

    /**
     * The first frame of a walk is drawn at the walker, not eased in from the last one: a map
     * that swoops across the picture on the frame the user starts walking reads as a fault.
     */
    @Test
    fun aFreshWalkStartsWhereTheUserIsStanding() {
        val motion = RoomMapMotion()
        motion.reset()
        val fit = RoomMapLayout.Fit(2.0, 3.0, 80f)

        motion.advance(0.016, fit, targetX = 2.0, targetY = 3.0, targetHeadingDegrees = 45.0)

        assertEquals(2.0, motion.arrowX, absoluteTolerance = 1e-9)
        assertEquals(3.0, motion.arrowY, absoluteTolerance = 1e-9)
        assertEquals(2.0, motion.fit.centreX, absoluteTolerance = 1e-9)
        assertEquals(45.0, motion.arrowHeadingDegrees!!, absoluteTolerance = 1e-9)
    }

    /** An arrow on a walking bench has to turn through two degrees, not through three hundred. */
    @Test
    fun theArrowTurnsTheShortWayRound() {
        val motion = RoomMapMotion()
        val fit = RoomMapLayout.Fit(0.0, 0.0, 100f)
        motion.advance(0.016, fit, 0.0, 0.0, 359.0)

        motion.advance(0.12, fit, 0.0, 0.0, 1.0)

        val heading = motion.arrowHeadingDegrees!!
        assertTrue(heading > 359.0 || heading < 1.0, "it turned the long way, through $heading")
    }

    /** No frame is allowed to fling the marker across the map; a walk is metres per second, not tens. */
    @Test
    fun theMarkerNeverFlingsAcrossTheMapInOneFrame() {
        val walk = walkTheRoom(paces = 36, settleFrames = 60)
        var worst = 0f
        for (index in 1 until walk.frames.size) {
            val before = walk.frames[index - 1].placed
            val after = walk.frames[index].placed
            val jump = kotlin.math.hypot(after.arrowX - before.arrowX, after.arrowY - before.arrowY)
            if (jump > worst) worst = jump
        }
        // A stride at the tightest scale the map will ever use is around 150 px, and it is eased
        // over a third of a second; anything near that is walking, anything larger is a fault.
        assertTrue(worst < 45f, "the marker moved $worst px in one frame")
    }

    private fun assertInside(value: Float, limit: Float, edge: Float, what: String) {
        assertTrue(
            value >= edge - 0.001f && value <= limit - edge + 0.001f,
            "$what escaped to $value, outside $edge..${limit - edge}",
        )
    }

    private companion object {
        /** Sixty a second, which is the rate a phone drawing a moving map is aiming for. */
        const val FRAME_MILLIS = 17L

        /** Two paces a second: an unhurried walk round a room. */
        const val FRAMES_PER_PACE = 30

        /** One radio reading a second, as the mobile feed actually reports. */
        const val READING_MILLIS = 1000L
    }
}

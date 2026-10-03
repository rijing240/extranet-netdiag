package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomMapLayoutTest {

    private val width = 900f
    private val height = 900f
    private val padding = 40f

    /** A straight walk of [steps] paces at [heading], filed into a fresh model. */
    private fun walk(steps: Int, heading: Double): RoomMapModel {
        val model = RoomMapModel()
        var x = 0.0
        var y = 0.0
        for (i in 0 until steps) {
            model.append(RoomMapModel.Dot(i * 500L, x, y, -60.0 - i))
            x += PositionHistory.DEFAULT_STEP_METERS * kotlin.math.sin(Math.toRadians(heading))
            y += PositionHistory.DEFAULT_STEP_METERS * kotlin.math.cos(Math.toRadians(heading))
        }
        return model
    }

    private fun placedFor(model: RoomMapModel, startHeading: Double, arrowX: Double, arrowY: Double) =
        RoomMapLayout.place(
            snapshot = model.snapshot(nowMillis = 1_000_000L),
            startHeadingDegrees = startHeading,
            fit = RoomMapLayout.fit(
                snapshot = model.snapshot(nowMillis = 1_000_000L),
                startHeadingDegrees = startHeading,
                arrowXMeters = arrowX,
                arrowYMeters = arrowY,
                widthPx = width,
                heightPx = height,
                paddingPx = padding,
            ),
            widthPx = width,
            heightPx = height,
            dotRadiusPx = 20f,
            nowMillis = 1_000_000L,
            arrowXMeters = arrowX,
            arrowYMeters = arrowY,
        )

    /**
     * The bug this class was rewritten for: a walk whose direction does not line up with the
     * stored axes used to be fitted by its unrotated extents, so rotating it afterwards pushed
     * dots out of the picture - and the further the walk went, the further out they went.
     */
    @Test
    fun aDiagonalWalkStaysInsideTheCanvas() {
        val model = walk(steps = 30, heading = 45.0)

        val placed = placedFor(model, startHeading = 45.0, arrowX = 14.0, arrowY = 14.0)

        for (dot in placed.dots) {
            assertTrue(dot.xPx in 0f..width, "dot escaped sideways at ${dot.xPx}")
            assertTrue(dot.yPx in 0f..height, "dot escaped vertically at ${dot.yPx}")
        }
        assertTrue(placed.arrowX in 0f..width)
        assertTrue(placed.arrowY in 0f..height)
    }

    @Test
    fun aWalkInAnyDirectionStaysInsideTheCanvas() {
        for (heading in listOf(0.0, 37.0, 90.0, 143.0, 180.0, 251.0, 300.0, 359.0)) {
            val model = walk(steps = 25, heading = heading)
            val arrowX = 8.0 * kotlin.math.sin(Math.toRadians(heading))
            val arrowY = 8.0 * kotlin.math.cos(Math.toRadians(heading))

            val placed = placedFor(model, startHeading = heading, arrowX = arrowX, arrowY = arrowY)

            for (dot in placed.dots) {
                assertTrue(dot.xPx in 0f..width, "heading $heading put a dot at ${dot.xPx}")
                assertTrue(dot.yPx in 0f..height, "heading $heading put a dot at ${dot.yPx}")
            }
        }
    }

    /** The walker's own marker is included in what has to fit, even standing far from the trail. */
    @Test
    fun aMarkerFarFromTheTrailIsStillInFrame() {
        val model = walk(steps = 6, heading = 0.0)

        val placed = placedFor(model, startHeading = 0.0, arrowX = 30.0, arrowY = -20.0)

        assertTrue(placed.arrowX in 0f..width, "arrow at ${placed.arrowX}")
        assertTrue(placed.arrowY in 0f..height, "arrow at ${placed.arrowY}")
    }

    /** The start of the walk is always in frame: every direction on the map is read from it. */
    @Test
    fun theStartingPointIsAlwaysInFrame() {
        val model = walk(steps = 40, heading = 0.0)

        val placed = placedFor(model, startHeading = 0.0, arrowX = 0.0, arrowY = 28.0)

        assertTrue(placed.startX in 0f..width)
        assertTrue(placed.startY in 0f..height)
    }

    /** One step must not be magnified until it fills the picture. */
    @Test
    fun aVeryShortWalkIsNotZoomedToFillTheScreen() {
        val model = RoomMapModel()
        model.append(RoomMapModel.Dot(0L, 0.0, 0.0, -60.0))

        val fit = RoomMapLayout.fit(
            snapshot = model.snapshot(nowMillis = 0L),
            startHeadingDegrees = 0.0,
            arrowXMeters = 0.0,
            arrowYMeters = 0.0,
            widthPx = width,
            heightPx = height,
            paddingPx = padding,
        )

        // The floor is a span of four meters across the usable width, and the viewport aims a
        // little wider than that so a viewport still easing towards this one has room to be
        // behind by. One step is a sliver of the picture, not the picture.
        val usable = width - 2 * padding
        val slacked = usable / (RoomMapLayout.MIN_SPAN_METERS.toFloat() * (1f + RoomMapLayout.FIT_SLACK.toFloat()))
        assertEquals(slacked, fit.scale, absoluteTolerance = 0.01f)
        assertTrue(fit.scale < usable / RoomMapLayout.MIN_SPAN_METERS.toFloat())
    }

    /**
     * The slack in the fit is what stops the drawn viewport lagging out of its own box, so it has
     * to be there by default rather than left to each caller to remember.
     */
    @Test
    fun theFitAimsWiderThanTheTrailStrictlyNeeds() {
        val model = RoomMapModel()
        var x = 0.0
        var y = 0.0
        for (i in 0 until 30) {
            model.append(RoomMapModel.Dot(i * 500L, x, y, -60.0 - i))
            x += PositionHistory.DEFAULT_STEP_METERS
        }
        val snapshot = model.snapshot(nowMillis = 1_000_000L)

        val tight = RoomMapLayout.fit(
            snapshot = snapshot,
            startHeadingDegrees = 0.0,
            arrowXMeters = x,
            arrowYMeters = y,
            widthPx = width,
            heightPx = height,
            paddingPx = padding,
            slackFraction = 0.0,
        )
        val aimed = RoomMapLayout.fit(
            snapshot = snapshot,
            startHeadingDegrees = 0.0,
            arrowXMeters = x,
            arrowYMeters = y,
            widthPx = width,
            heightPx = height,
            paddingPx = padding,
        )

        assertTrue(aimed.scale < tight.scale, "the default fit should not be the tight one")
        assertEquals(tight.centreX, aimed.centreX, absoluteTolerance = 1e-6)
        assertEquals(
            tight.scale / (1f + RoomMapLayout.FIT_SLACK.toFloat()),
            aimed.scale,
            absoluteTolerance = 0.01f,
        )

        // The default has to be the wide one, or a caller that does not know about the slack
        // quietly gets the tight fit and the map drifts out of its box as the walk grows.
        val byDefault = RoomMapLayout.fit(
            snapshot = snapshot,
            startHeadingDegrees = 0.0,
            arrowXMeters = x,
            arrowYMeters = y,
            widthPx = width,
            heightPx = height,
            paddingPx = padding,
        )
        assertEquals(aimed.scale, byDefault.scale, absoluteTolerance = 1e-6f)
    }

    @Test
    fun theScaleBarIsARoundNumberThatFitsOnTheCanvas() {
        for (scale in listOf(4f, 20f, 80f, 200f, 600f)) {
            val meters = RoomMapLayout.barMeters(scale)
            val pixels = meters * scale
            assertTrue(pixels in 30.0..260.0, "bar of $meters m at $scale px/m was $pixels px")
            assertTrue(meters == 0.25 || meters == 0.5 || meters == 1.0 || meters == 2.0 ||
                meters == 4.0 || meters == 8.0 || meters == 16.0, "unround bar of $meters m")
        }
    }

    @Test
    fun anEmptyTrailHasNothingToFitButStillWorks() {
        val snapshot = RoomMapModel().snapshot(nowMillis = 0L)

        val fit = RoomMapLayout.fit(
            snapshot = snapshot,
            startHeadingDegrees = 0.0,
            arrowXMeters = 0.0,
            arrowYMeters = 0.0,
            widthPx = width,
            heightPx = height,
            paddingPx = padding,
        )

        assertTrue(fit.scale > 0f)
        assertFalse(fit.scale.isNaN())
    }
}
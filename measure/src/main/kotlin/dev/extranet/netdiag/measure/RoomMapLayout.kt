package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns a trail of positions in meters into dots on a canvas, in the walker's own frame.
 *
 * Two things make this worth having apart from the drawing itself.
 *
 * **The frame.** Positions are stored in the yaw frame, whose zero is wherever the gyroscope
 * happened to start - a fact about the sensor, not about the room, and one that would put "up" on
 * screen at a random angle. The map is therefore rotated so the direction the user was facing when
 * they tapped Start points up the screen. That is the only orientation the user can check against
 * their own body: "the signal was stronger over there" is checkable when "there" is a direction
 * they can still see, and uncheckable when it is a bearing relative to a gyroscope.
 *
 * **The fit.** A walk that starts in one corner and grows across a room must not walk off the
 * edge of the picture, so the trail is fitted to the canvas continuously.
 *
 * The fit is done in the *rotated* frame, and that is the part worth getting right. Fitting to the
 * unrotated bounding box and then rotating gives a box that does not contain the rotated points -
 * a trail walked diagonally spans more of the screen than its stored extents suggest - so dots
 * drift out of the frame, and the further the walk goes the further out they go. Every point is
 * therefore rotated first and the box is measured where the points actually land, which makes the
 * picture correct whatever direction the user happened to be facing.
 *
 * The fit is floored at [MIN_SPAN_METERS] so a user who has taken one step does not see that step
 * magnified until it fills the screen, which would draw a few centimetres of drift as though it
 * were a room.
 */
public object RoomMapLayout {

    /**
     * A viewport: what point in the rotated frame sits at the middle of the canvas, and how many
     * pixels a meter is worth.
     *
     * Kept as data rather than applied immediately so it can be eased towards. A fit that jumped
     * every time the walker took a step would make the whole map jitter under them, and the map is
     * the one thing on screen that must not move when the user does.
     */
    public data class Fit(
        public val centreX: Double,
        public val centreY: Double,
        public val scale: Float,
    ) {
        public companion object {
            /** A fit that draws nothing useful; replaced on the first frame with a real size. */
            public val Zero: Fit = Fit(0.0, 0.0, 1f)
        }
    }

    /** One dot, ready to draw at a pixel position. */
    public data class DotAt(
        public val xPx: Float,
        public val yPx: Float,
        public val radiusPx: Float,
        /** 0 weakest third, 1 middle, 2 strongest; null when the walk is not coloured. */
        public val bucket: Int?,
        /** Fade with age, newest at one. */
        public val alpha: Float,
        /** How far through its own arrival fade this dot is, from zero to one. */
        public val appear: Float,
        public val best: Boolean,
        public val weakest: Boolean,
        public val lowConfidence: Boolean,
    )

    /** Everything the canvas needs: the dots, and where the walker is standing now. */
    public data class Placed(
        public val dots: List<DotAt>,
        public val arrowX: Float,
        public val arrowY: Float,
        /** Where the walk began, so the picture has a fixed point to read directions from. */
        public val startX: Float,
        public val startY: Float,
        public val scale: Float,
        /** A round number of meters that makes a bar of a sensible length at this scale. */
        public val barMeters: Double,
        public val empty: Boolean,
        /**
         * How far the furthest thing on this map had to be pulled back to stay on the canvas, in
         * pixels, or zero when the viewport was already wide enough for everything.
         *
         * Reported rather than kept private because it is the one number that says whether the
         * viewport is doing its job. A map whose dots have to be dragged half a centimetre inwards
         * every frame is a map whose fit is chasing rather than leading, and that is worth being
         * able to assert on. See [fit] and [clampToCanvas].
         */
        public val overshootPx: Float,
    )

    /**
     * The viewport that fits a trail into a canvas of [widthPx] by [heightPx].
     *
     * [arrowXMeters] and [arrowYMeters] are included in what has to fit, so the walker's own
     * marker cannot be left off the picture by a trail that ended somewhere else.
     *
     * [slackFraction] aims the viewport wider than the trail strictly needs, by that fraction of
     * the usable area. It exists because the drawn viewport is not this one: it is the one the
     * walk is still easing towards. A viewport fitted tight to the trail is by definition only just
     * wide enough for it, so a viewport that lags behind it is *too narrow*, and the newest dots
     * and the marker get drawn past the edge they were supposed to stay inside. Aiming a little
     * wide means the lag eats the slack instead of the border. The cost is a slightly smaller
     * picture, which is a fair price for a walk that never leaves its box.
     */
    public fun fit(
        snapshot: RoomMapModel.Snapshot,
        startHeadingDegrees: Double,
        arrowXMeters: Double,
        arrowYMeters: Double,
        widthPx: Float,
        heightPx: Float,
        paddingPx: Float,
        minSpanMeters: Double = MIN_SPAN_METERS,
        slackFraction: Double = FIT_SLACK,
    ): Fit {
        if (widthPx <= 1f || heightPx <= 1f) return Fit.Zero

        val rotation = Math.toRadians(startHeadingDegrees)
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        fun include(x: Double, y: Double) {
            val rx = x * cos(rotation) - y * sin(rotation)
            val ry = x * sin(rotation) + y * cos(rotation)
            if (rx < minX) minX = rx
            if (rx > maxX) maxX = rx
            if (ry < minY) minY = ry
            if (ry > maxY) maxY = ry
        }

        for (placed in snapshot.dots) include(placed.dot.xMeters, placed.dot.yMeters)
        include(arrowXMeters, arrowYMeters)
        // The origin too: the map is read from where the walk started, so it is always in frame
        // even after the user has walked away from it.
        include(0.0, 0.0)

        val spanX = max(maxX - minX, minSpanMeters)
        val spanY = max(maxY - minY, minSpanMeters)
        val usableWidth = max(1f, widthPx - 2f * paddingPx)
        val usableHeight = max(1f, heightPx - 2f * paddingPx)
        val tight = min(usableWidth / spanX.toFloat(), usableHeight / spanY.toFloat())

        return Fit(
            centreX = (minX + maxX) / 2.0,
            centreY = (minY + maxY) / 2.0,
            scale = tight / (1f + max(0f, slackFraction.toFloat())),
        )
    }

    /**
     * Projects a trail through [fit] onto the canvas.
     *
     * [arrowXMeters] and [arrowYMeters] are passed in rather than read from the snapshot so the
     * caller can hand over an eased position: the walker's marker is drawn where the user has
     * *almost* reached, and the dots stay exactly where they were measured.
     */
    public fun place(
        snapshot: RoomMapModel.Snapshot,
        startHeadingDegrees: Double,
        fit: Fit,
        widthPx: Float,
        heightPx: Float,
        dotRadiusPx: Float,
        nowMillis: Long,
        arrowXMeters: Double,
        arrowYMeters: Double,
        paddingPx: Float = 0f,
    ): Placed {
        val rotation = Math.toRadians(startHeadingDegrees)
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        var overshoot = 0f

        fun keepOnCanvas(value: Float, limit: Float): Float {
            val held = clampToCanvas(value, limit, paddingPx)
            val slip = abs(value - held)
            if (slip > overshoot) overshoot = slip
            return held
        }

        fun toScreen(x: Double, y: Double): Pair<Float, Float> {
            // Rotate the world so the direction the user first faced points up the canvas, then
            // centre the fitted box and flip y: meters grow forward and up, the canvas grows down.
            val rotatedX = x * cosR - y * sinR
            val rotatedY = x * sinR + y * cosR
            return Pair(
                widthPx / 2f + ((rotatedX - fit.centreX) * fit.scale).toFloat(),
                heightPx / 2f - ((rotatedY - fit.centreY) * fit.scale).toFloat(),
            )
        }

        val dots = snapshot.dots.map { placed ->
            val (rawX, rawY) = toScreen(placed.dot.xMeters, placed.dot.yMeters)
            val x = keepOnCanvas(rawX, widthPx)
            val y = keepOnCanvas(rawY, heightPx)
            DotAt(
                xPx = x,
                yPx = y,
                radiusPx = dotRadiusPx,
                bucket = placed.bucket,
                alpha = placed.alpha,
                appear = ((nowMillis - placed.dot.arrivalMillis).toFloat() / APPEAR_MILLIS)
                    .coerceIn(0f, 1f),
                best = placed.best,
                weakest = placed.weakest,
                lowConfidence = placed.dot.lowConfidence,
            )
        }

        val (rawArrowX, rawArrowY) = toScreen(arrowXMeters, arrowYMeters)
        val arrowX = keepOnCanvas(rawArrowX, widthPx)
        val arrowY = keepOnCanvas(rawArrowY, heightPx)
        val (rawStartX, rawStartY) = toScreen(0.0, 0.0)
        val startX = keepOnCanvas(rawStartX, widthPx)
        val startY = keepOnCanvas(rawStartY, heightPx)

        return Placed(
            dots = dots,
            arrowX = arrowX,
            arrowY = arrowY,
            startX = startX,
            startY = startY,
            scale = fit.scale,
            barMeters = barMeters(fit.scale),
            empty = snapshot.dots.isEmpty(),
            overshootPx = overshoot,
        )
    }

    /**
     * The screen positions of the trail, with everything that is drawn clamped inside the canvas.
     *
     * [place] draws the viewport it is given, and the viewport the user is looking at is always a
     * little behind the one the trail has earned. That lag is what makes the map move smoothly
     * instead of stepping; it also means a dot at the very edge of a growing trail can briefly be
     * a few percent past the edge it was fitted to.
     *
     * So the seatbelt: nothing on this map is ever drawn outside [paddingPx] of the canvas edge,
     * whatever the viewport says. A dot is pulled back to the boundary rather than allowed to sit
     * on the border, cut in half, or over the cards below. The pull-back can only ever be a few
     * percent of the picture, and only while a walk is actively stretching it, which is a far
     * smaller lie than a dot drawn outside a box that says "this much room".
     */
    public fun clampToCanvas(
        value: Float,
        limit: Float,
        paddingPx: Float,
    ): Float {
        val low = paddingPx
        val high = limit - paddingPx
        if (high <= low) return limit / 2f
        return value.coerceIn(low, high)
    }

    /**
     * The screen angle, in degrees clockwise from up, that [headingDegrees] points at.
     *
     * The canvas draws the arrow with this, and nothing else uses it: it is the one place where
     * the rotation applied above has to be undone, so the arrow keeps pointing where the user is
     * facing after the trail has been rotated.
     */
    public fun screenAngleFor(headingDegrees: Double, startHeadingDegrees: Double): Double =
        RadarTracker.normalize(headingDegrees - startHeadingDegrees)

    /**
     * A round number of meters for a scale bar of a readable length at [scale] pixels per meter.
     *
     * A picture of a walk with no scale at all is decorative; one with a bar is measurable. The
     * bar is kept between about sixty and two hundred pixels by doubling or halving, which is the
     * same trick every paper map uses and needs no explanation to read.
     */
    public fun barMeters(scale: Float): Double {
        if (scale <= 0f) return 1.0
        var meters = 1.0
        while (meters * scale > BAR_MAX_PX && meters > BAR_MIN_METERS) meters /= 2.0
        while (meters * scale < BAR_MIN_PX) meters *= 2.0
        return meters
    }

    /**
     * The smallest stretch of room the canvas will fit to, in meters.
     *
     * Four meters, about six paces: roughly a room. Below this the map is showing one or two steps
     * at a scale where the step-length estimate's own error is wider than the picture, and the
     * view lurches every time a foot lands.
     */
    public const val MIN_SPAN_METERS: Double = 4.0

    /** How long a dot takes to fade in after it is filed. */
    public const val APPEAR_MILLIS: Float = 450f

    /**
     * How much wider than the trail the viewport aims, as a fraction of the usable area.
     *
     * Roughly one fast stride's worth of a room-sized trail, which is the worst lag the easing can
     * accumulate while someone is walking. See [fit].
     */
    public const val FIT_SLACK: Double = 0.14

    /** The longest a scale bar is allowed to be, in pixels. */
    private const val BAR_MAX_PX = 200.0

    /** The shortest a scale bar is allowed to be, in pixels. */
    private const val BAR_MIN_PX = 60.0

    /** The smallest a scale bar may be labelled with, in meters. */
    private const val BAR_MIN_METERS = 0.25
}
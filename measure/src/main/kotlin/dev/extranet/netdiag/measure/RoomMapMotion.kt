package dev.extranet.netdiag.measure

import kotlin.math.exp

/**
 * The Room Map's ease: how the picture catches up with the walk.
 *
 * Everything the map draws moves in discrete jumps. A step is half a meter at once, a radio
 * reading lands once a second, and a trail that has grown wider changes the fit that contains it.
 * Drawn literally, the picture snaps: the viewport lurches when the fit changes, and the marker
 * hops forward a stride at a time, which reads as a stutter rather than as walking.
 *
 * So the drawing follows the measurements rather than replacing them. The target comes from the
 * trail, this eases towards it, and the result is drawn. Two rules keep that honest:
 *
 * - **The dots never move.** Only the viewport and the walker's own marker are eased. Every dot
 *   stays exactly where it was measured, in the frame it was measured in; easing a dot would be
 *   showing a position nobody stood at.
 * - **The marker never runs ahead.** Easing is an approach, never an overshoot, so the arrow is
 *   always somewhere between the last place the phone was sure of and the place it now knows
 *   about. It cannot point at a spot the walker has not reached.
 *
 * The easing is exponential and time-based rather than per-frame, which matters because frames do
 * not arrive at a fixed rate: a phone that drops to thirty frames a second while its owner walks
 * must take the same *time* to catch up, not twice as long. [advance] takes the real elapsed time
 * and computes the approach from it, so the motion looks the same on a slow phone as a fast one.
 *
 * Angles are eased the short way round for the same reason headings are everywhere else in this
 * app: an arrow pointing at 359 degrees that needs to point at 1 has two degrees to travel, not
 * three hundred and fifty-eight, and a marker that spins the long way once per revolution is
 * worse than one that does not move at all.
 */
public class RoomMapMotion(
    /** Time constant for the viewport, in seconds. Larger is calmer and slower to re-fit. */
    private val fitTauSeconds: Double = 0.45,
    /** Time constant for the walker's marker, in seconds. Small, so a step shows at once. */
    private val markerTauSeconds: Double = 0.12,
) {

    init {
        require(fitTauSeconds > 0.0) { "a fit time constant must be positive, got $fitTauSeconds" }
        require(markerTauSeconds > 0.0) { "a marker time constant must be positive, got $markerTauSeconds" }
    }

    private var currentFit = RoomMapLayout.Fit.Zero
    private var currentX = 0.0
    private var currentY = 0.0
    private var currentHeading: Double? = null
    private var seeded = false

    /** The viewport to draw with, as of the last [advance]. */
    public val fit: RoomMapLayout.Fit
        get() = currentFit

    /** Where to draw the walker's marker; close to the measured position, on its way there. */
    public val arrowX: Double
        get() = currentX

    public val arrowY: Double
        get() = currentY

    /** The facing to draw the arrow at, or null when the phone has not said. */
    public val arrowHeadingDegrees: Double?
        get() = currentHeading

    /** Forgets everything, so the next [advance] starts at its target rather than crossing the map. */
    public fun reset() {
        seeded = false
        currentFit = RoomMapLayout.Fit.Zero
        currentX = 0.0
        currentY = 0.0
        currentHeading = null
    }

    /**
     * Moves everything a little towards where it should be.
     *
     * The first call after a [reset] jumps straight to the target, because easing from the origin
     * would draw the first second of a walk as a swoop across the picture from wherever the last
     * walk ended.
     *
     * @param deltaSeconds real time since the last call; frames do not arrive evenly.
     */
    public fun advance(
        deltaSeconds: Double,
        targetFit: RoomMapLayout.Fit,
        targetX: Double,
        targetY: Double,
        targetHeadingDegrees: Double?,
    ) {
        // Seeding comes before the elapsed-time check on purpose: the first frame of a walk has
        // no previous tick to measure against, and easing from the origin on that frame would
        // draw one picture with the trail collapsed into a corner before snapping right.
        if (!seeded) {
            currentFit = targetFit
            currentX = targetX
            currentY = targetY
            currentHeading = targetHeadingDegrees
            seeded = true
            return
        }

        if (deltaSeconds <= 0.0) return

        // Guard against the absurd: a check left in the background and resumed must not be eased
        // across in one step as though the walker had teleported, and a huge delta would do
        // exactly that. Clamping makes a resumed map catch up over about a frame or two, which is
        // what the eye expects, rather than snapping.
        val seconds = deltaSeconds.coerceAtMost(MAX_STEP_SECONDS)

        val fitFraction = approach(seconds, fitTauSeconds)
        currentFit = RoomMapLayout.Fit(
            centreX = currentFit.centreX + (targetFit.centreX - currentFit.centreX) * fitFraction,
            centreY = currentFit.centreY + (targetFit.centreY - currentFit.centreY) * fitFraction,
            scale = currentFit.scale + (targetFit.scale - currentFit.scale) * fitFraction.toFloat(),
        )

        val markerFraction = approach(seconds, markerTauSeconds)
        currentX += (targetX - currentX) * markerFraction
        currentY += (targetY - currentY) * markerFraction

        val headingTarget = targetHeadingDegrees
        val headingNow = currentHeading
        currentHeading = when {
            headingTarget == null -> headingNow
            headingNow == null -> headingTarget
            else -> headingNow + RadarTracker.turnDegrees(headingNow, headingTarget) * markerFraction
        }
    }

    /** How much of the remaining distance a time constant covers in [seconds]. */
    private fun approach(seconds: Double, tauSeconds: Double): Double = 1.0 - exp(-seconds / tauSeconds)

    private companion object {
        /**
         * The largest frame gap that is treated as time passing.
         *
         * About two frames at a slow thirty a second. Anything longer means the screen was off or
         * the app was in the background, and easing over it would be animating a walk the user
         * did not see.
         */
        const val MAX_STEP_SECONDS: Double = 0.08
    }
}
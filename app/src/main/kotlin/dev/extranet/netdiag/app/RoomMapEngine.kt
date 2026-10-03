package dev.extranet.netdiag.app

import android.content.Context
import android.os.SystemClock
import dev.extranet.netdiag.measure.PositionHistory
import dev.extranet.netdiag.measure.RoomMapLayout
import dev.extranet.netdiag.measure.RoomMapModel
import dev.extranet.netdiag.measure.RoomMapMotion

/**
 * The Room Map's instrument panel: sensors in, one [Frame] out, once per drawn frame.
 *
 * The engine walks the same line the radar does, in two dimensions instead of one. A radio
 * reading does not describe the moment it arrives; it describes a moment a little earlier, when
 * the radio was doing its averaging and the modem was finishing its own. The radar looks that
 * moment up in a history of headings; the map looks it up in a history of *places*, using the
 * same idea, and files the reading where the user actually stood.
 *
 * The three inputs are all things the phone measures directly, and nothing else is invented:
 *
 * - **Where the user is** comes from counting steps and walking forward along the direction they
 *   were facing. Never from integrating the accelerometer twice, which is how dead reckoning
 *   turns into fiction.
 * - **Which way they face** comes from the same gyroscope heading the Signal tab uses, whose
 *   offset from true north is one damped constant. The map does not need north at all: it draws
 *   in the user's own frame, pointing the way they pointed when they started.
 * - **How strong the signal is** comes from the shared radio feed, and only from its
 *   [RadioStrengthFeed.Measurement]s - a value that merely sat on screen unchanged is not new
 *   evidence and does not become another dot.
 *
 * The link-type rule is the radar's, and matters more here: mobile and Wi-Fi strengths are not on
 * one scale, so a handover mid-walk would draw half the room in one unit and half in another.
 * The scan takes the kind it saw first and drops everything after that disagrees, rather than
 * mixing them into one picture.
 *
 * Listening and recording are separate, exactly as on the radar: the map view is alive as soon as
 * the tab opens, and the trail only starts learning when the user taps Start.
 */
public class RoomMapEngine(context: Context) {

    private val heading = LiveHeadingSource(context)
    private val feed = RadioStrengthFeed(context)
    private val steps = StepDetector(context)
    private val lift = PhoneLift(context)
    private val positions = PositionHistory()
    private val model = RoomMapModel()

    private val motion = RoomMapMotion()

    /** The canvas the map is being drawn into, told to the engine whenever it changes size. */
    private var viewportWidth = 0f
    private var viewportHeight = 0f
    private var viewportPadding = 0f

    private var lastTickNanos = 0L

    private var listening = false
    private var recording = false
    private var sessionKind: RadioStrengthFeed.Kind? = null
    private var scanStartMillis = 0L
    private var stepsAtLastTick = 0
    private var startHeadingDegrees = 0.0

    /** True when this phone can map at all: it needs both a direction and a step count. */
    public val canMap: Boolean
        get() = heading.hasCompass && steps.isAvailable

    /**
     * True when the phone has a gyroscope, so its headings are relative.
     *
     * The map itself only ever needs relative headings, so this is not a gate on the map - it is
     * what the screen says when it explains why the directions are given in the user's own frame.
     */
    public val usesGyroscope: Boolean
        get() = heading.usesGyroscope

    /** Everything the map draws and says, as of one instant. */
    public data class Frame(
        public val recording: Boolean,
        public val kind: RadioStrengthFeed.Kind,
        public val dbm: Int?,
        public val map: RoomMapModel.Snapshot,
        /** Where the walker is now, in the frame that started at the origin. */
        public val xMeters: Double,
        public val yMeters: Double,
        /** The walker's facing in the stored frame, or null when the heading is not known. */
        public val headingDegrees: Double?,
        /** The facing the scan started with; the map is drawn in this frame. */
        public val startHeadingDegrees: Double,
        public val steps: Int,
        public val scanSeconds: Int,
        /** True when the phone has been raised or lowered enough to change what it measures. */
        public val keepHeight: Boolean,
        /** True when the phone cannot name a compass direction, so the map stays in its own frame. */
        public val labelsHidden: Boolean,
        public val reportIntervalSeconds: Double?,
        public val readings: Int,
        /**
         * The viewport to draw with, already eased towards the one that fits the trail.
         *
         * Computed here rather than in the screen because it has to move at the frame rate, and
         * because its behaviour is worth having under the same roof as the rest of the map: it is
         * a smoothed display value, not a measurement, and it lives beside the measurements so
         * that it is obvious which is which.
         */
        public val layout: RoomMapLayout.Fit,
        /** Where to draw the walker's marker: the measured position, slightly on its way there. */
        public val displayX: Double,
        public val displayY: Double,
        /** The facing to draw the marker at, or null when the phone has not said. */
        public val displayHeadingDegrees: Double?,
        /** The canvas this frame was laid out for, so the screen can tell when it has changed. */
        public val viewportWidth: Float,
        public val viewportHeight: Float,
        /**
         * The clock this frame was built on, for anything that fades.
         *
         * Passed through rather than read again while drawing, so a dot's arrival fade is measured
         * against the same instant the frame describes. Reading the clock at draw time would let a
         * slow frame draw a dot as older than it is.
         */
        public val displayedAtMillis: Long,
    )

    /**
     * Tells the engine how big the map is being drawn.
     *
     * The screen owns the size and the engine owns the fit, so the size has to cross. It is set
     * from the canvas's own layout callback rather than assumed, because the same map is drawn on
     * phones of every shape and the fit has to be right on all of them.
     */
    public fun setViewport(widthPx: Float, heightPx: Float, paddingPx: Float) {
        viewportWidth = widthPx
        viewportHeight = heightPx
        viewportPadding = paddingPx
    }

    /** Starts the sensors; call when the tab appears. */
    public fun startListening() {
        if (listening) return
        listening = true
        heading.start()
        feed.start()
        steps.start()
        lift.start()
    }

    /** Stops the sensors and any walk in progress; call when the tab goes away. */
    public fun stopListening() {
        if (!listening) return
        listening = false
        recording = false
        heading.stop()
        feed.stop()
        steps.stop()
        lift.stop()
    }

    /** Turns the walk on or off. Turning it on starts a fresh trail from where the user stands. */
    public fun setRecording(on: Boolean) {
        if (on && !recording) {
            // Permissions may have been granted since the tab opened, in which case the first
            // registration was refused. Registering again is harmless when it already worked.
            if (listening) {
                feed.stop()
                feed.start()
            }
            model.reset()
            steps.reset()
            lift.reset()
            motion.reset()
            stepsAtLastTick = 0
            lastTickNanos = 0L
            sessionKind = null
            scanStartMillis = SystemClock.elapsedRealtime()
            // The origin is wherever the user is standing, and the frame's up is the way they are
            // facing. A phone with no heading at all cannot start a trail that means anything.
            val now = SystemClock.elapsedRealtime()
            startHeadingDegrees = heading.headingDegrees() ?: 0.0
            positions.start(now, startHeadingDegrees)
        }
        recording = on
    }

    /** Reads the sensors once and returns the frame to draw. Cheap enough to call every frame. */
    public fun tick(): Frame {
        val now = SystemClock.elapsedRealtime()
        val headingNow = heading.headingDegrees()
        val reading = feed.read()
        val measurements = feed.drainMeasurements()
        val intervalMillis = feed.reportIntervalMillis()
        val activeKind = reading?.kind ?: RadioStrengthFeed.Kind.NONE

        if (recording) {
            // Steps are the only thing that move the walker. Counting the difference since the
            // last frame rather than the running total means a pause in drawing - the screen off,
            // the tab in the background - does not teleport the trail when it resumes.
            //
            // The marker only catches up once a heading is known: a step taken before the
            // gyroscope has said which way the phone faces has no direction to be walked in, and
            // consuming the count while unable to use it would lose the stride for good.
            val counted = steps.count()
            val fresh = counted - stepsAtLastTick
            if (fresh > 0 && headingNow != null) {
                repeat(fresh) { positions.step(now, headingNow) }
                stepsAtLastTick = counted
            }

            for (measurement in measurements) {
                // One scale per walk. A handover would put half the room in RSRP and half in RSSI,
                // and a map drawn from two units is a map of nothing.
                if (measurement.kind != activeKind) continue
                val kind = sessionKind ?: measurement.kind.also { sessionKind = it }
                if (measurement.kind != kind) continue

                // The reading describes where the user was while the radio was averaging, not
                // where they are now.
                val centre = measurement.timeMillis - feed.latencyMillis(kind)
                val exact = positions.at(centre)
                // A reading inside a pause, or before the first fix, is filed where the walker
                // last certainly was and marked doubtful. It is never dropped: a dot in slightly
                // the wrong place still shows that somewhere around here reads like this, and a
                // dropped one leaves a hole in the picture that looks like a fact about the room.
                val fix = exact ?: positions.latest() ?: continue
                model.append(
                    RoomMapModel.Dot(
                        timeMillis = centre,
                        xMeters = fix.xMeters,
                        yMeters = fix.yMeters,
                        dbm = measurement.dbm.toDouble(),
                        lowConfidence = exact == null,
                        arrivalMillis = measurement.timeMillis,
                    ),
                )
            }
        }

        val latest = positions.latest()
        val x = latest?.xMeters ?: 0.0
        val y = latest?.yMeters ?: 0.0

        // Ease the viewport and the marker towards where they now belong. The elapsed time comes
        // from the clock rather than being assumed, so a phone drawing at thirty frames a second
        // eases over the same *time* as one drawing at sixty - it simply takes more frames to do
        // it. Without that, motion would visibly slow down whenever the phone got busy.
        val deltaSeconds = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1_000.0
        lastTickNanos = now

        val snapshot = model.snapshot(now)
        val targetFit = RoomMapLayout.fit(
            snapshot = snapshot,
            startHeadingDegrees = startHeadingDegrees,
            arrowXMeters = x,
            arrowYMeters = y,
            widthPx = viewportWidth,
            heightPx = viewportHeight,
            paddingPx = viewportPadding,
        )
        motion.advance(
            deltaSeconds = deltaSeconds,
            targetFit = targetFit,
            targetX = x,
            targetY = y,
            targetHeadingDegrees = headingNow,
        )

        return Frame(
            recording = recording,
            kind = activeKind,
            dbm = reading?.dbm,
            map = snapshot,
            xMeters = x,
            yMeters = y,
            headingDegrees = headingNow,
            startHeadingDegrees = startHeadingDegrees,
            steps = steps.count(),
            scanSeconds = if (recording) ((now - scanStartMillis) / 1_000L).toInt() else 0,
            keepHeight = lift.movedRecently(),
            labelsHidden = heading.labelsAreHidden(),
            reportIntervalSeconds = intervalMillis?.let { it / 1_000.0 },
            readings = model.size,
            layout = motion.fit,
            displayX = motion.arrowX,
            displayY = motion.arrowY,
            displayHeadingDegrees = motion.arrowHeadingDegrees,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            displayedAtMillis = now,
        )
    }
}
package dev.extranet.netdiag.app

import android.content.Context
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import dev.extranet.netdiag.measure.LapCounter
import dev.extranet.netdiag.measure.RadarTracker
import dev.extranet.netdiag.measure.WalkTracker
import kotlin.math.abs
import kotlin.math.pow

/**
 * The Signal tab's instrument panel: sensors in, one [Frame] out, once per drawn frame.
 *
 * The engine owns the compass, the signal feed and the step counter, and the two trackers that
 * remember what they saw. The screen calls [tick] on every frame, so the heading is read as fast
 * as the display refreshes - about sixty times a second - and the dial turns exactly as the phone
 * does.
 *
 * What the engine will not do is pretend the signal moves that fast. The radio hands over a new
 * strength about once a second, and what it hands over describes where the phone was pointing a
 * moment *before* it arrived. So the engine works the way a surveyor would:
 *
 * - It counts each radio report once, as one measurement, never once per drawn frame.
 * - It looks the report up in the compass's recent history and files it under the heading the
 *   phone had when the radio measured it (the arrival time minus the radio's known delay), and
 *   under the whole arc the phone swept while the radio was averaging.
 * - It sets the turning pace from how often the radio actually reports, so "turn slower" is
 *   advice that is true for this phone on this network, not a fixed number.
 * - It leaves the answer to [RadarTracker], which says a direction only when the evidence
 *   supports one and says how far to trust it.
 *
 * Listening (the sensors running, the dial turning) and recording (the trackers filling) are
 * separate on purpose: the dial should turn the moment the tab opens, but a scan should only
 * start learning when the user presses Start.
 */
public class RadarEngine(context: Context) {

    private val appContext: Context = context.applicationContext
    private val heading = LiveHeadingSource(context)
    private val feed = RadioStrengthFeed(context)
    private val steps = StepDetector(context)
    private val radar = RadarTracker()
    private val walk = WalkTracker()
    private val laps = LapCounter()

    private var listening = false
    private var recording = false
    private var scanKind: RadioStrengthFeed.Kind? = null
    private var scanStartMillis = 0L
    private var trueNorth = false

    /** Latched by crossing the turning limit, cleared only well back under it. See [tick]. */
    private var turningTooFastSticky = false

    /** True when this phone has a compass, so the dial can point; false selects the walking meter. */
    public val hasCompass: Boolean
        get() = heading.hasCompass

    /** Everything the radar and the walking meter draw, as of one instant. */
    public data class Frame(
        /**
         * Smoothed compass heading, degrees clockwise from north; null without a compass. True
         * north when the phone could work out where it is, magnetic north otherwise.
         */
        public val headingDegrees: Double?,
        public val dbm: Int?,
        public val kind: RadioStrengthFeed.Kind,
        public val turnRateDegPerSec: Double,
        /** True when the phone turns faster than the radio can report. */
        public val turningTooFast: Boolean,
        /** The fastest turn, in degrees per second, that still lets every direction be measured. */
        public val maxTurnRate: Double,
        public val needsCalibration: Boolean,
        /** True when the compass's readings cannot be trusted at all; recording is refused. */
        public val compassUnreliable: Boolean,
        public val updatesPerSecond: Double,
        public val recording: Boolean,
        public val radar: RadarTracker.Snapshot,
        public val walk: WalkTracker.Snapshot,
        public val steps: Int,
        /** The turn rate that suits this radio best, in degrees per second. */
        public val idealTurnRate: Double = 15.0,
        /** Seconds between new reports from the radio; null until it has reported a few times. */
        public val reportIntervalSeconds: Double? = null,
        /** Whole seconds since the scan started; zero when not recording. */
        public val scanSeconds: Int = 0,
        /** How many radio reports the radar has taken in, this scan. */
        public val readings: Int = 0,
        /** True when headings are relative to true north rather than magnetic north. */
        public val trueNorth: Boolean = false,
        /**
         * Degrees to add to a radar-frame bearing to turn it into a compass bearing, or null
         * when this phone cannot name a direction - no magnetometer, or one reporting itself
         * unreliable.
         *
         * The radar's own [headingDegrees] is perfectly good in that state, because every
         * bearing it reports is a difference between two headings, and a constant added to both
         * ends of a difference cancels exactly. This is the one number in the whole instrument
         * that depends on the magnetometer, and it is what the labels are built from.
         */
        public val compassOffsetDegrees: Double? = null,
        /**
         * Where true north sits on the dial, in the radar's frame, or null when unknown. The
         * north badge is drawn here and is not drawn at all when this is null.
         */
        public val northBearingDegrees: Double? = null,
        /** True when the screen must withhold compass points and the north badge. */
        public val labelsHidden: Boolean = false,
        /** Whole laps the phone has been turned during this scan. */
        public val laps: Int = 0,
    )

    /** Starts the sensors; call when the tab appears. */
    public fun startListening() {
        if (listening) return
        listening = true
        heading.start()
        feed.start()
        steps.start()
        refreshDeclination()
    }

    /** Stops the sensors and any scan in progress; call when the tab goes away. */
    public fun stopListening() {
        if (!listening) return
        listening = false
        recording = false
        heading.stop()
        feed.stop()
        steps.stop()
    }

    /** Turns recording on or off. Turning it on starts a fresh scan; turning it off keeps the result. */
    public fun setRecording(on: Boolean) {
        if (on && !recording) {
            // Permissions may have been granted since the tab opened, in which case the first
            // registration was refused. Registering again is harmless when it already worked.
            if (listening) {
                feed.stop()
                feed.start()
                refreshDeclination()
            }
            radar.reset()
            walk.reset()
            laps.reset()
            steps.reset()
            scanKind = null
            scanStartMillis = SystemClock.elapsedRealtime()
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
        val intervalSeconds = (intervalMillis ?: DEFAULT_INTERVAL_MILLIS) / 1_000.0
        val turnRate = heading.turnRateDegPerSec()
        val calibrationNeeded = heading.needsCalibration()
        // Recording no longer stops when the compass reports itself unreliable: the radar
        // measures turning, and on a phone with a gyroscope the turning comes from the
        // gyroscope, which a magnet in the pocket cannot corrupt. Only the labels go.
        val labelsHidden = heading.labelsAreHidden()
        if (headingNow != null && recording) laps.append(now, headingNow)
        val activeKind = reading?.kind ?: RadioStrengthFeed.Kind.NONE

        // A person turns at some pace and the radio reports at some pace; what matters is how
        // many degrees the phone moves between two reports. About 25 degrees is comfortable and
        // 45 is the most the estimator tolerates (it models the smear, but a wider smear means
        // a blurrier answer). Both limits come from the radio's actual report interval, so a
        // network that reports every three seconds is asked for a slower turn than one that
        // reports every second.
        val idealTurn = (IDEAL_SPACING_DEGREES / intervalSeconds).coerceIn(5.0, 30.0)
        val maxTurn = (MAX_SPACING_DEGREES / intervalSeconds).coerceIn(8.0, 60.0)
        // Hysteresis on the one bit of state the user acts on. Comparing the turn rate straight
        // against the limit means a phone turning at almost exactly the limit flips in and out of
        // "too fast" several times a second, so the warning strobes and the pace bar strobes with
        // it - advice that changes while it is being followed is worse than none. Crossing the
        // limit switches the advice on; only dropping well back below it switches it off again.
        val tooFast = if (abs(turnRate) > maxTurn) {
            turningTooFastSticky = true
            true
        } else if (abs(turnRate) < maxTurn * TOO_FAST_RELEASE) {
            turningTooFastSticky = false
            false
        } else {
            turningTooFastSticky
        }

        if (recording) {
            for (measurement in measurements) {
                // The phone may report cellular strength while it is on Wi-Fi, and the two are
                // not on one scale: only the link carrying the data counts, and only one kind
                // per scan, so a handover mid-scan cannot mix scales in one fit.
                if (measurement.kind != activeKind) continue
                val kind = scanKind ?: measurement.kind.also { scanKind = it }
                if (measurement.kind != kind) continue

                walk.append(measurement.dbm, steps.count())

                // Only a phone that cannot measure which way it turned is refused here. A
                // compass that reports itself unreliable used to stop the scan outright, which
                // threw away readings that were fine: the answer compares headings taken
                // moments apart, and on a gyroscope those headings are not the compass's at all.
                // Merely low accuracy still counts, at a lower weight.
                if (!heading.hasCompass) continue
                val centre = measurement.timeMillis - feed.latencyMillis(kind)

                // The reading is filed under the heading the phone had while the radio measured
                // it, over the arc it swept doing so. The arc is a bonus, and each step down is a
                // step of less certainty about where the phone pointed, never a reason to lose
                // the reading:
                //   1. the full window, the exact heading and the exact sweep;
                //   2. the heading alone, when the compass has not recorded the whole window -
                //      it may deliver only a few headings a second, or its newest one may still
                //      be inside the window;
                //   3. the heading now, when the compass cannot resolve that moment at all. This
                //      is late by the radio's delay, which rotates the whole picture by a
                //      constant angle rather than blurring it, and the tracker's error bar already
                //      charges for exactly that delay.
                // What it must never do is throw the reading away. A dropped reading leaves the
                // dial empty while the screen goes on telling the user to turn more slowly, which
                // is what a scan that gathered nothing looks like from the outside - and nothing
                // on screen would say so.
                val arc = heading.history.arcAround(centre, ARC_WINDOW_MILLIS)
                val headingAtMeasurement = arc?.centerDegrees
                    ?: heading.history.headingAt(centre)
                    ?: headingNow
                    ?: continue
                val width = arc?.widthDegrees ?: 0.0
                val rate = arc?.turnRateDegPerSec ?: turnRate
                val smear = 1.0 / (1.0 + (width / SMEAR_SCALE_DEGREES).pow(2))
                // A low-accuracy compass used to weigh down every reading. On the gyroscope
                // path the compass is not in the loop at all, so there is nothing to weigh
                // down: the offset it contributes is one constant on every bearing, and a
                // constant cancels out of a difference.
                val trust = if (calibrationNeeded && !heading.usesGyroscope) 0.6 else 1.0
                radar.observe(
                    nowMillis = measurement.timeMillis,
                    headingDegrees = headingAtMeasurement,
                    dbm = measurement.dbm.toDouble(),
                    weight = smear * trust,
                    arcDegrees = width,
                    turnRateDegPerSec = rate,
                )
            }
        }

        return Frame(
            headingDegrees = headingNow,
            dbm = reading?.dbm,
            kind = activeKind,
            turnRateDegPerSec = turnRate,
            turningTooFast = tooFast,
            maxTurnRate = maxTurn,
            needsCalibration = calibrationNeeded,
            compassUnreliable = heading.isUnreliable(),
            updatesPerSecond = 1.0 / intervalSeconds,
            recording = recording,
            radar = radar.snapshot(),
            walk = walk.snapshot(),
            steps = steps.count(),
            idealTurnRate = idealTurn,
            reportIntervalSeconds = intervalMillis?.let { it / 1_000.0 },
            scanSeconds = if (recording) ((now - scanStartMillis) / 1_000L).toInt() else 0,
            readings = radar.observationCount,
            trueNorth = trueNorth,
            compassOffsetDegrees = heading.compassOffsetDegrees(),
            northBearingDegrees = heading.northBearingDegrees(),
            labelsHidden = labelsHidden,
            laps = laps.laps(),
        )
    }

    /**
     * Looks up the magnetic declination for wherever the phone last knew it was, and applies it
     * so the compass points at true north. Needs no new permission: it reads the last position
     * another app or the system already found, and does nothing if there is none.
     */
    private fun refreshDeclination() {
        val degrees = declinationFromLastFix(appContext) ?: run {
            // No cached position is the common case on a phone that has not been anywhere since
            // it booted, and leaving the compass on magnetic north for the whole scan is how the
            // dial ends up pointing tens of degrees away from the map. Ask for one position;
            // it is a single coarse fix, not a tracker, and it is only used to turn magnetic
            // into true north.
            requestOneFix()
            return
        }
        heading.setDeclination(degrees)
        trueNorth = true
    }

    /**
     * Asks for a single position, purely to read the declination from it, and stops listening
     * as soon as it has one. Everything is wrapped because a phone that will not answer - no
     * permission, no provider, location switched off - is an ordinary outcome, and the scan
     * must go on being a scan without it.
     */
    private fun requestOneFix() {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return
        val provider = runCatching {
            manager.getProviders(true).firstOrNull { it == LocationManager.NETWORK_PROVIDER }
                ?: manager.getProviders(true).firstOrNull { it == LocationManager.GPS_PROVIDER }
        }.getOrNull() ?: return
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { manager.removeUpdates(this) }
                val degrees = runCatching {
                    GeomagneticField(
                        location.latitude.toFloat(),
                        location.longitude.toFloat(),
                        location.altitude.toFloat(),
                        System.currentTimeMillis(),
                    ).declination.toDouble()
                }.getOrNull() ?: return
                heading.setDeclination(degrees)
                trueNorth = true
            }

            // Required on older platforms; the defaults above are what this app needs.
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        runCatching {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, appContext.mainLooper)
        }.onFailure { return }
        // Never keep the listener past a short grace period: it exists to fetch one number, and
        // a location listener left running is a privacy liability and a battery cost. If no fix
        // arrives the scan simply continues on magnetic north, and the screen says so.
        android.os.Handler(appContext.mainLooper).postDelayed({
            runCatching { manager.removeUpdates(listener) }
        }, FIX_TIMEOUT_MILLIS)
    }

    private companion object {
        /** Used for the pace advice until the radio has reported often enough to measure. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000L

        /** Degrees the phone should move between two radio reports for the best answer. */
        const val IDEAL_SPACING_DEGREES: Double = 25.0

        /** Degrees between reports beyond which the answer blurs too much to rely on. */
        const val MAX_SPACING_DEGREES: Double = 45.0

        /**
         * Fraction of the limit a person must fall back under before the warning clears.
         *
         * Four fifths. Close enough that obeying the advice clears it at once, far enough that a
         * hand-wobbling on the boundary does not clear and re-set it several times a second.
         */
        const val TOO_FAST_RELEASE: Double = 0.8

        /** The radio averages over roughly this long before it reports. */
        const val ARC_WINDOW_MILLIS: Long = 800L

        /** A reading smeared over this many degrees counts for half. */
        const val SMEAR_SCALE_DEGREES: Double = 45.0

        /** How long the declination lookup waits for one position before giving up. */
        const val FIX_TIMEOUT_MILLIS: Long = 8_000L
    }
}

/** Declination in degrees (east positive) at the newest position the system remembers, or null. */
private fun declinationFromLastFix(context: Context): Double? = runCatching {
    val manager = context.getSystemService(LocationManager::class.java) ?: return@runCatching null
    var newest: Location? = null
    for (provider in manager.getProviders(true)) {
        val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        val current = newest
        if (current == null || fix.time > current.time) newest = fix
    }
    newest?.let { fix ->
        GeomagneticField(
            fix.latitude.toFloat(),
            fix.longitude.toFloat(),
            fix.altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination.toDouble()
    }
}.getOrNull()

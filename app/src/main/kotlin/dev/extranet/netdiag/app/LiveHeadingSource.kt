package dev.extranet.netdiag.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dev.extranet.netdiag.measure.HeadingAxes
import dev.extranet.netdiag.measure.HeadingHistory
import dev.extranet.netdiag.measure.RadarTracker
import dev.extranet.netdiag.measure.YawMagneticOffsetFilter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A streaming compass heading for the radar: smooth, tilt-aware, and honest about being absent.
 *
 * What it does, in order:
 * - Runs the heading sensors at game rate, about fifty readings a second, so the dial follows a
 *   full turn without stepping.
 * - Uses the gyroscope's own rotation vector when the phone has one, which measures turning and
 *   nothing else, and the accelerometer-and-magnetometer pair when it does not.
 * - Keeps the two things apart. The gyroscope answers "which way have I turned since a moment
 *   ago", which is the only question the radar asks and the one it asks best. The magnetometer
 *   answers "which way is north", which is the one question the gyroscope cannot answer at all,
 *   because its idea of north wanders by a fraction of a degree every second. Joining the two is
 *   [YawMagneticOffsetFilter]'s job, and the join is a single slowly-changing number: see
 *   [compassOffsetDegrees] and [northBearingDegrees].
 * - Works however the phone is held. Flat in the hand, the heading is the way the top of the
 *   phone points; held upright like a camera, it is the way the back of the phone points - the
 *   way the user is looking - so the dial does not lurch when someone lifts the phone to look at
 *   it, and does not come out a half turn wrong once it is up. That choice of axis is
 *   [HeadingAxes]'s job and is tested there at poses that can be built by hand, because getting
 *   it backwards reverses every compass word the app says while leaving the radar's own relative
 *   measurements looking perfectly reasonable.
 * - Smooths the heading as a unit vector rather than as an angle, so 359 and 1 degrees average
 *   to north and not to south, and measures how fast the phone is turning.
 * - Reports the magnetometer's own accuracy, so the screen can ask for a figure-8 calibration
 *   when the compass is unsure rather than pointing confidently the wrong way.
 *
 * Why keeping them apart is worth the extra part: a magnetometer in a pocket with a magnet in
 * it does not merely add noise, it swings the reported heading by tens of degrees while the
 * phone stands still, and every signal reading would be filed under a direction the phone was
 * never facing. The gyroscope cannot be fooled that way. So a scan measured on turning keeps
 * working through bad magnetic ground, and only the *labels* are withheld - see
 * [labelsAreHidden]. That is the difference between a scan that says nothing and a scan that
 * says "that way" without claiming to know which way that is.
 *
 * What it will not do is invent a heading. A phone with neither a gyroscope nor a magnetometer
 * cannot know which way it faces, and [hasCompass] is false there, so the screen switches to the
 * walking meter instead.
 *
 * Two frames are in play, and the difference matters. [headingDegrees] and everything in
 * [history] are the *radar frame*: the gyroscope's own yaw, which is arbitrary at the moment the
 * sensor starts and drifts slowly after. Every bearing the radar reports is a difference between
 * two headings, so the arbitrary origin and the drift cancel out of the answer exactly.
 * [compassOffsetDegrees] is the *label frame*: yaw plus the offset plus the declination, which
 * the only thing here that claims to be a real direction on the map, and it is as good as the
 * last magnetometer reading it was built from.
 */
public class LiveHeadingSource(context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * True when this phone can record which way it turned.
     *
     * A gyroscope on its own is enough for that, and enough for the radar: it does not know
     * where north is, but it knows exactly which way it turned, and a bearing is only ever a
     * difference. So this is true on a phone whose magnetometer has gone bad or is not there at
     * all. What such a phone cannot do is *name* the directions it finds - see
     * [labelsAreHidden] - and the screen says so rather than pretending.
     */
    public val hasCompass: Boolean =
        gameRotationVector != null || (magnetometer != null && (rotationVector != null || accelerometer != null))

    /** True when the headings come from the gyroscope alone, with no magnetometer in the loop. */
    public val usesGyroscope: Boolean = gameRotationVector != null

    /**
     * Every heading the sensor delivered lately, stamped on the elapsed-realtime clock the radio
     * feed also uses, so a signal reading can be matched to the heading it was measured at
     * rather than the heading of the moment it arrived. The raw heading is recorded, not the
     * smoothed one: smoothing is for drawing, and its lag would put every reading late.
     *
     * These are in the radar frame - see [headingDegrees] for what that means.
     */
    public val history: HeadingHistory = HeadingHistory()

    @Volatile
    private var declinationDegrees = 0.0

    /**
     * Turns magnetic headings into true ones for the labels only. A compass points at magnetic
     * north, which is not quite where the map's north is; the gap (the declination) depends on
     * where on Earth the phone is. Zero until the screen supplies one, which leaves the labels
     * on magnetic north - and leaves every bearing the radar reports untouched, because a
     * constant added to both ends of a difference cancels.
     */
    public fun setDeclination(degrees: Double) {
        declinationDegrees = if (degrees.isNaN()) 0.0 else degrees
    }

    /** Legacy name for "no compass"; the tilt-only fallback was removed because it cannot work. */
    public val isTiltOnly: Boolean
        get() = !hasCompass

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveMagnetic = false

    /** The gyroscope's frame, driving the radar. */
    private val yawRotation = FloatArray(9)

    /** The magnetometer's frame, used only to learn where north is. */
    private val magneticRotation = FloatArray(9)

    /** The join between the two frames: a single damped number, held here. */
    private val offset = YawMagneticOffsetFilter()

    private var smoothSin = 0.0
    private var smoothCos = 0.0
    private var hasSmooth = false
    private var previousDegrees: Double? = null
    private var previousNanos = 0L

    /** The newest unsmoothed yaw, paired with each magnetometer reading for the offset. */
    @Volatile
    private var latestRawYaw: Double? = null

    @Volatile
    private var smoothedDegrees: Double? = null

    @Volatile
    private var lastEventNanos = 0L

    @Volatile
    private var turnRate = 0.0

    @Volatile
    private var accuracyStatus = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    /** Starts the heading stream; false when this phone cannot measure which way it turned. */
    public fun start(): Boolean {
        val manager = sensorManager ?: return false
        val mag = magnetometer ?: return false
        if (!hasCompass) return false
        return runCatching {
            val game = gameRotationVector
            val rotationSensor = rotationVector
            val accelSensor = accelerometer
            if (game != null) {
                // The gyroscope at game rate: smooth, immediate, and completely indifferent to
                // whatever magnet the phone is sitting next to.
                manager.registerListener(this, game, SensorManager.SENSOR_DELAY_GAME)
                // North is a slow fact. Reading it at UI rate is ample - the offset it feeds is
                // damped over seconds anyway - and keeps the magnetometer off the hot path.
                if (rotationSensor != null) {
                    manager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                } else if (accelSensor != null) {
                    manager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
                    manager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
                }
            } else if (rotationSensor != null) {
                manager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
                // Only to learn how sure the compass is; the heading itself comes from the vector.
                manager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
            } else if (accelSensor != null) {
                manager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
                manager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            }
            true
        }.getOrDefault(false)
    }

    /** Stops the stream and forgets the last heading; safe to call any number of times. */
    public fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
        hasSmooth = false
        haveGravity = false
        haveMagnetic = false
        previousDegrees = null
        smoothedDegrees = null
        latestRawYaw = null
        turnRate = 0.0
        offset.reset()
        history.clear()
    }

    /**
     * The latest smoothed heading in the radar frame, or null when there is no heading or the
     * stream has gone quiet.
     *
     * This is *not* a direction on the map and must never be drawn as one. Its origin is wherever
     * the gyroscope happened to be when the sensor started. It is safe for the two things the
     * radar does with a heading - filing a reading under the direction the phone faced, and
     * comparing two of them - because both are differences. Use [compassOffsetDegrees] for anything
     * that names a place.
     */
    public fun headingDegrees(): Double? {
        val degrees = smoothedDegrees ?: return null
        val ageNanos = SystemClock.elapsedRealtimeNanos() - lastEventNanos
        return if (ageNanos > STALE_NANOS) null else degrees
    }

    /**
     * Degrees to add to a radar-frame heading to turn it into a true compass bearing, or null
     * when this phone cannot say.
     *
     * Null is the honest answer on a phone with no working magnetometer, and while the compass
     * reports itself unreliable - a dial that names "north-east" off a compass sitting next to a
     * magnet is worse than one that names nothing. The radar's own bearings are unaffected and
     * still perfectly good, because this is a single constant added to both ends of every
     * difference it measures, and a constant cancels. So the scan keeps working and only the
     * labels go.
     *
     * Declination is folded in here rather than into the recorded headings, for the same reason:
     * it is one more constant, and leaving it out of the history means a phone that gains a
     * position partway through a scan does not have every reading it has already taken quietly
     * re-labelled underneath it.
     */
    public fun compassOffsetDegrees(): Double? {
        if (!hasMagneticReference()) return null
        return offset.offsetDegrees() + declinationDegrees
    }

    /**
     * The radar-frame heading at which true north currently lies, or null when that is unknown.
     *
     * The dial draws its north badge at this bearing, and it is zero only in the one frame whose
     * zero happens to be north. The dial's frame is the gyroscope's, so this is asked for rather
     * than assumed. Null in the same state [compassOffsetDegrees] is null, and the badge is then
     * not drawn at all rather than drawn at a made-up place.
     */
    public fun northBearingDegrees(): Double? {
        val compassOffset = compassOffsetDegrees() ?: return null
        return RadarTracker.normalize(-compassOffset)
    }

    /** How fast the phone is turning, in degrees per second; positive is clockwise. */
    public fun turnRateDegPerSec(): Double = turnRate

    /** True when the compass reports low or unreliable accuracy and a figure-8 wave would help. */
    public fun needsCalibration(): Boolean =
        accuracyStatus == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            accuracyStatus == SensorManager.SENSOR_STATUS_ACCURACY_LOW

    /**
     * True when the compass's own readings should not be trusted to name a direction.
     *
     * UNRELIABLE is the sensor saying its values cannot be trusted at all. LOW is a coarse fix,
     * not a broken one, so it still labels - the offset it produces is a few degrees out, which
     * the screen's "magnetic north" line already admits to.
     *
     * Neither of these stops a scan. The radar measures turning, and the gyroscope measures
     * turning whether or not the magnetometer is having a bad day.
     */
    public fun isUnreliable(): Boolean =
        accuracyStatus == SensorManager.SENSOR_STATUS_UNRELIABLE

    /**
     * True when directions cannot be named, so the screen should withhold compass points and the
     * north badge rather than draw them.
     *
     * True when the magnetometer is missing entirely, when it reports itself unreliable, and
     * before its first reading has been folded in. A gyroscope on its own is enough to scan with
     * and not enough to say "east".
     */
    public fun labelsAreHidden(): Boolean = !hasMagneticReference()

    /** Whether this phone can put a name to a direction at all. See [labelsAreHidden]. */
    private fun hasMagneticReference(): Boolean =
        magnetometer != null && (rotationVector != null || accelerometer != null) &&
            offset.hasReading() && !isUnreliable()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (matrixFrom(event, yawRotation)) publishYaw(event.timestamp, yawRotation)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (matrixFrom(event, magneticRotation)) {
                    publishMagnetic(event.timestamp, magneticRotation)
                    // With no gyroscope-only sensor, this same heading is the yaw as well.
                    if (gameRotationVector == null) publishYaw(event.timestamp, magneticRotation)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(gravity, event.values)
                haveGravity = true
                if (rotationVector == null) updateFromAccelerometerAndMagnetometer(event.timestamp)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                accuracyStatus = event.accuracy
                if (rotationVector == null) {
                    lowPass(geomagnetic, event.values)
                    haveMagnetic = true
                    updateFromAccelerometerAndMagnetometer(event.timestamp)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) accuracyStatus = accuracy
    }

    private fun updateFromAccelerometerAndMagnetometer(timestampNanos: Long) {
        if (!haveGravity || !haveMagnetic) return
        if (!SensorManager.getRotationMatrix(magneticRotation, null, gravity, geomagnetic)) return
        publishMagnetic(timestampNanos, magneticRotation)
        if (gameRotationVector == null) publishYaw(timestampNanos, magneticRotation)
    }

    /** Reads a rotation vector, which is 4 or 5 values long and says so by its array size. */
    private fun matrixFrom(event: SensorEvent, into: FloatArray): Boolean {
        val values = if (event.values.size > 4) event.values.copyOf(4) else event.values
        return runCatching { SensorManager.getRotationMatrixFromVector(into, values) }.isSuccess
    }

    /**
     * Folds one magnetometer reading into the offset that joins the two frames.
     *
     * The yaw is the newest unsmoothed one, not the current smoothed one: the two sensors deliver
     * at their own rates and never at the same instant, so pairing a smoothed value here would
     * fold the smoothing's own lag into the offset as a permanent bias. Whatever is left over is
     * a few tens of milliseconds of turn, which is exactly what [YawMagneticOffsetFilter] damps.
     */
    private fun publishMagnetic(timestampNanos: Long, matrix: FloatArray) {
        val magnetic = azimuthFrom(matrix) ?: return
        val yaw = latestRawYaw ?: return
        offset.update(magneticHeadingDegrees = magnetic, gameYawDegrees = yaw)
    }

    /**
     * Turns the current rotation matrix into a heading in the radar frame.
     *
     * Which axis of the phone counts as the way it faces is [HeadingAxes]'s decision, not this
     * class's: flat it is the top edge, held up it is the back of the phone, and the two agree
     * where the phone crosses between them so the dial does not lurch as someone raises it.
     */
    private fun publishYaw(timestampNanos: Long, matrix: FloatArray) {
        val raw = azimuthFrom(matrix) ?: return
        latestRawYaw = raw
        history.record(SystemClock.elapsedRealtime(), raw)

        val radians = Math.toRadians(raw)
        if (!hasSmooth) {
            smoothSin = sin(radians)
            smoothCos = cos(radians)
            hasSmooth = true
        } else {
            smoothSin += SMOOTHING * (sin(radians) - smoothSin)
            smoothCos += SMOOTHING * (cos(radians) - smoothCos)
        }
        val degrees = RadarTracker.normalize(Math.toDegrees(atan2(smoothSin, smoothCos)))

        val previous = previousDegrees
        if (previous != null) {
            val seconds = (timestampNanos - previousNanos) / 1_000_000_000.0
            if (seconds > 0.001) {
                val delta = RadarTracker.turnDegrees(previous, degrees)
                turnRate += RATE_SMOOTHING * (delta / seconds - turnRate)
            }
        }
        previousDegrees = degrees
        previousNanos = timestampNanos
        smoothedDegrees = degrees
        lastEventNanos = timestampNanos
    }

    /**
     * The bearing the matrix says the phone faces, or null when there is not a full matrix.
     *
     * The platform's own `getOrientation` is not used here. It reports the bearing of whichever
     * axis the matrix has been arranged to point with, and arranging it for an upright phone is
     * the step where the screen's axis is easy to pick up by mistake - which reports the way the
     * user's face points, not the way they face. [HeadingAxes] does the projection itself, in the
     * pure module, where it is tested at known poses.
     */
    private fun azimuthFrom(matrix: FloatArray): Double? {
        if (matrix.size < 9) return null
        return HeadingAxes.facingDegrees(matrix)
    }

    private fun lowPass(target: FloatArray, values: FloatArray) {
        val count = minOf(3, values.size)
        for (i in 0 until count) target[i] += SENSOR_SMOOTHING * (values[i] - target[i])
    }

    private companion object {
        /** Weight of each new heading in the smoothed one; about 80 ms of lag at fifty a second. */
        const val SMOOTHING: Double = 0.25

        /** Weight of each new reading in the turn rate. */
        const val RATE_SMOOTHING: Double = 0.1

        /** Weight of each new accelerometer or magnetometer sample on the no-vector path. */
        const val SENSOR_SMOOTHING: Float = 0.2f

        /** A heading older than this is reported as no heading at all. */
        const val STALE_NANOS: Long = 1_000_000_000L
    }
}
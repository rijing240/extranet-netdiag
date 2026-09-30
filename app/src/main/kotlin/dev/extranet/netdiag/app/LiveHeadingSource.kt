package dev.extranet.netdiag.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * A streaming compass heading, for the radar's live dot.
 *
 * Two sensor paths, tried in order. The rotation vector is the accurate one and needs a
 * magnetometer; the accelerometer-only fallback computes a pitch/roll tilt orientation, which
 * tracks how the phone is held but has no fixed north - on the A03, which ships an accelerometer
 * and no magnetometer, that is the difference between a dial that turns as you turn the phone
 * and a dial that never receives a heading at all.
 *
 * The fallback's values are still recorded into the session, because the radar's question -
 * "which way was the phone facing when the signal was stronger" - is answerable relative to the
 * phone's own starting orientation. The needle and wedges then work; only the N marker's claim
 * to be north does not, and the radar labels it "start" instead when this source is degraded.
 *
 * The value follows the timeline's convention: degrees clockwise, 0..360. Null means no event
 * yet - the dot simply does not draw.
 */
public class LiveHeadingSource(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** True when the source is the tilt fallback rather than a real compass. */
    public val isTiltOnly: Boolean = rotationVector == null && accelerometer != null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    @Volatile
    private var latestDegrees: Double? = null

    /** Starts delivering headings; false when this device has neither sensor path. */
    public fun start(): Boolean {
        val manager = sensorManager ?: return false
        return runCatching {
            if (rotationVector != null) {
                manager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI)
            } else if (accelerometer != null && magnetometer != null) {
                manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
                manager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
            } else if (accelerometer != null) {
                // Tilt-only: heading relative to the phone's own frame, no north.
                manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            } else {
                return false
            }
            true
        }.getOrDefault(false)
    }

    /** Stops delivering headings; safe to call any number of times. */
    public fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
    }

    /** The latest heading in degrees clockwise, or null before the first event. */
    public fun headingDegrees(): Double? = latestDegrees

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> latestDegrees = runCatching { azimuthOfRotationVector(event) }.getOrNull()
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, minOf(3, event.values.size))
                updateTiltAzimuth()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, minOf(3, event.values.size))
                updateTiltAzimuth()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun azimuthOfRotationVector(event: SensorEvent): Double {
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        return normalized(orientation[0])
    }

    /**
     * Azimuth from whatever gravity/magnetic data has arrived.
     *
     * With both sensors this is the standard rotation-matrix path. With gravity alone the matrix
     * is built with an identity magnetic field, which yields a bearing that rotates with the
     * phone but is anchored nowhere - exactly the relative trend the radar can still use.
     */
    private fun updateTiltAzimuth() {
        val hasGravity = gravity.any { it != 0f }
        if (!hasGravity) return
        val rotation = FloatArray(9)
        val hasMagnetic = geomagnetic.any { it != 0f }
        val ok = if (hasMagnetic) {
            SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)
        } else {
            SensorManager.getRotationMatrix(rotation, null, gravity, floatArrayOf(0f, 1f, 0f))
        }
        if (!ok) return
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        latestDegrees = normalized(orientation[0])
    }

    private fun normalized(azimuthRadians: Float): Double {
        val degrees = Math.toDegrees(azimuthRadians.toDouble())
        return ((degrees % 360.0) + 360.0) % 360.0
    }

}

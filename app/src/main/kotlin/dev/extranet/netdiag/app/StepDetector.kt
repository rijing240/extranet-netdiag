package dev.extranet.netdiag.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * A step counter built on the accelerometer alone.
 *
 * The platform's own step sensors want the activity-recognition permission, and a signal meter
 * has no business asking for it. Walking shakes the phone in a rhythm that the accelerometer
 * sees clearly, so this counts the peaks: take the strength of the acceleration, subtract a slow
 * estimate of gravity, smooth what is left, and count each time it rises through a threshold, at
 * most about three times a second.
 *
 * The count is approximate by nature - a wave of the hand can register a step and a gentle
 * shuffle can miss one - and the screen only ever says "about N steps". It exists to turn
 * "the signal was best a while ago" into "go back about this far".
 */
public class StepDetector(context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    private var steps = 0

    private var gravityEstimate = SensorManager.GRAVITY_EARTH.toDouble()
    private var smoothed = 0.0
    private var above = false
    private var lastStepMillis = 0L

    /** True when this phone has an accelerometer to count with. */
    public val isAvailable: Boolean = accelerometer != null

    /** Starts counting; false when there is no accelerometer. */
    public fun start(): Boolean {
        val manager = sensorManager ?: return false
        val sensor = accelerometer ?: return false
        return runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            true
        }.getOrDefault(false)
    }

    /** Stops counting; safe to call any number of times. */
    public fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
    }

    /** Steps counted since the last [reset]. */
    public fun count(): Int = steps

    /** Starts the count again from zero. */
    public fun reset() {
        steps = 0
        smoothed = 0.0
        above = false
        lastStepMillis = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)

        gravityEstimate += GRAVITY_SMOOTHING * (magnitude - gravityEstimate)
        smoothed += SIGNAL_SMOOTHING * ((magnitude - gravityEstimate) - smoothed)

        val nowMillis = event.timestamp / 1_000_000L
        val isAbove = smoothed > THRESHOLD
        if (isAbove && !above && nowMillis - lastStepMillis > MIN_STEP_GAP_MILLIS) {
            steps++
            lastStepMillis = nowMillis
        }
        above = isAbove
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** How much the smoothed shake must rise above gravity to count as a footfall, in m/s^2. */
        const val THRESHOLD: Double = 1.0

        /** Steps closer together than this are one step: walking is under four a second. */
        const val MIN_STEP_GAP_MILLIS: Long = 300L

        /** Slow, so gravity is tracked but a footfall is not absorbed into it. */
        const val GRAVITY_SMOOTHING: Double = 0.02

        /** Fast enough to follow a step, slow enough to ignore sensor fuzz. */
        const val SIGNAL_SMOOTHING: Double = 0.3
    }
}

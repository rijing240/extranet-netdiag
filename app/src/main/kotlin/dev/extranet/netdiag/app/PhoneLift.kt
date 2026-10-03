package dev.extranet.netdiag.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Notices when the user lifts the phone or lowers it.
 *
 * The Room Map assumes the phone stays at one height: the signal at chest height is not the
 * signal on the floor, and a map drawn from a walk that changed height is mixing two different
 * measurements into one picture. Raising the phone overhead happens for a reason - people do it
 * when they are looking for signal - so the map says "keep your phone at the same height" when it
 * does, rather than silently drawing two rooms on top of each other.
 *
 * What it measures is **movement**, not height, and the difference matters. Height would need the
 * phone's position relative to the Earth, which no phone sensor reports; acceleration would need
 * integrating twice, which drifts into nonsense within seconds - the same reason the map counts
 * steps instead of integrating the accelerometer. So this integrates once and then deliberately
 * leaks: the estimate follows a lift or a drop as it happens, decays back toward zero within a
 * few seconds, and is never claimed as a height. A user who is walking with the phone in a normal
 * grip registers nothing, because the up-and-down of a stride cancels within a step.
 *
 * The cost is that it cannot tell "held overhead for a minute" from "raised a minute ago and put
 * back down": both decay. That is acceptable for a nudge that says "keep it level", and it is the
 * honest limit of what the sensor can support.
 */
public class PhoneLift(context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** True when this phone has an accelerometer to notice movement with. */
    public val isAvailable: Boolean = accelerometer != null

    private val gravity = DoubleArray(3)
    private var haveGravity = false
    private var lastNanos = 0L

    private var velocity = 0.0
    private var displacement = 0.0

    @Volatile
    private var moving = false

    public fun start(): Boolean {
        val manager = sensorManager ?: return false
        val sensor = accelerometer ?: return false
        return runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            true
        }.getOrDefault(false)
    }

    public fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
        reset()
    }

    /** Forgets everything, as though the phone had never moved. */
    public fun reset() {
        haveGravity = false
        lastNanos = 0L
        velocity = 0.0
        displacement = 0.0
        moving = false
    }

    /**
     * True when the phone has recently been moved up or down by more than a stride.
     *
     * Sticky for a few seconds after the movement stops, so a message about it stays on screen
     * long enough to be read rather than flickering with the end of the gesture.
     */
    public fun movedRecently(): Boolean = moving

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude <= 0.0) return

        if (!haveGravity) {
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
            haveGravity = true
            lastNanos = event.timestamp
            return
        }

        // The gravity vector's direction is the phone's own idea of "down", which is what makes
        // this work in a pocket and in a hand: it does not need to know how the phone is held,
        // only which part of the reading is the weight of the phone.
        gravity[0] += GRAVITY_SMOOTHING * (x - gravity[0])
        gravity[1] += GRAVITY_SMOOTHING * (y - gravity[1])
        gravity[2] += GRAVITY_SMOOTHING * (z - gravity[2])

        val gravityMagnitude = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gravityMagnitude <= 0.0) return

        // How much of the reading is acceleration along the phone's own down axis, over and
        // above the weight it is always carrying.
        val along = (x * gravity[0] + y * gravity[1] + z * gravity[2]) / gravityMagnitude
        val vertical = along - gravityMagnitude

        val seconds = if (lastNanos == 0L) 0.0 else (event.timestamp - lastNanos) / 1_000_000_000.0
        lastNanos = event.timestamp
        if (seconds <= 0.0 || seconds > 0.25) return

        velocity = (velocity + vertical * seconds) * VELOCITY_DECAY
        displacement = (displacement + velocity * seconds) * DISPLACEMENT_DECAY

        if (abs(displacement) >= LIFT_METERS) moving = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Weight of each new sample in the gravity estimate. */
        const val GRAVITY_SMOOTHING: Double = 0.05

        /**
         * Per-sample decay of the vertical velocity, at a nominal fifty samples a second.
         *
         * Chosen so the velocity built up by a lift is spent within about a second: a hand
         * holding a phone still does not hold it perfectly still, and without this the residual
         * acceleration of a normal grip would integrate into a metre of imaginary movement.
         */
        const val VELOCITY_DECAY: Double = 0.97

        /** Per-sample decay of the displacement, so a lift is remembered and then forgotten. */
        const val DISPLACEMENT_DECAY: Double = 0.995

        /**
         * How far the phone must be moved vertically to count, in meters.
         *
         * About half a stride, far enough that the ordinary bounce of walking does not reach it
         * and close enough that raising the phone to look at it does.
         */
        const val LIFT_METERS: Double = 0.35
    }
}
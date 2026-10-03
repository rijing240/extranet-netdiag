package dev.extranet.netdiag.measure

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gap between what the gyroscope says the phone is facing and what the magnetometer says.
 *
 * A phone has two ways of knowing which way it points, and they fail in opposite ways.
 *
 * The gyroscope measures rotation and nothing else. Integrated, it is smooth and quick - it
 * answers "which way have I turned since a moment ago" without a hint of noise - but it has no
 * idea where north is, and it wanders: the integration drifts, so its idea of north slides by a
 * fraction of a degree every second. Over a slow scan of twenty seconds that is several degrees
 * of nonsense pointing at the sky.
 *
 * The magnetometer knows which way magnetic north is, and does not drift. It also sits in a
 * pocket with a magnet in it, next to a speaker, under a car park, and there it is worth anything
 * from a couple of degrees to ninety. It jitters while it is read.
 *
 * So neither is used alone. The gyroscope supplies the *turn* - the shape of the circle the
 * phone sweeps, which is all the radar actually needs to place a signal on a direction - and the
 * magnetometer supplies only the one fact the gyroscope cannot: which way north is, as a constant
 * offset. This filter is where the two are joined, and the whole design rests on that offset
 * being slow and small.
 *
 * It is small because the phone does not move relative to the Earth's magnetic field, so if it
 * faces magnetic 70 degrees and the gyroscope calls the same pose 20, the difference is 50 and
 * stays 50. It is slow because when the magnetometer does jump - the user walks past a speaker,
 * or puts the phone on a metal table - that new number is a lie about north, and the honest
 * answer is the old one, damped. Averaging towards the new reading on a timescale of seconds
 * means a magnet a metre away shifts the offset a degree or two and then settles, instead of
 * swinging the whole dial by ninety degrees and every bin with it.
 *
 * The averaging is done on the circle rather than on the number, which is the whole trick. A
 * magnetic heading of 359 degrees and one of 1 degree are two degrees apart, not 358. Averaging
 * them as plain numbers gives 180 - due south, the exact opposite of the truth - and a compass
 * that points the wrong way is worse than no compass, because the user cannot tell. Averaging
 * them as directions gives 0, which is right.
 *
 * Headings are degrees, 0 up to 360. Every method returns a number in that range, and the filter
 * holds no state that a caller cannot read back.
 */
public class YawMagneticOffsetFilter(
    /**
     * How much of each new difference the smoothed offset takes, from just above zero to one.
     *
     * Low is a long memory and a steady dial. High follows the magnetometer closely, which is
     * right for a phone that is genuinely moving through changing magnetic fields and wrong for
     * one that is being handed a bad reading for a moment.
     */
    public val smoothingFactor: Double = DEFAULT_SMOOTHING,
) {

    init {
        require(smoothingFactor > 0.0 && smoothingFactor <= 1.0) {
            "a smoothing factor is a fraction of a step, above zero and at most one, got $smoothingFactor"
        }
    }

    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private var seeded = false

    /**
     * Folds one pair of readings in and returns the offset now believed, in degrees.
     *
     * [magneticHeadingDegrees] and [gameYawDegrees] must describe the same instant: the heading
     * the magnetometer reports at that moment, and the gyroscope's heading at that same moment.
     * They come from different sensors and arrive at different rates, so in practice the caller
     * passes the newest yaw it has; a few tens of milliseconds of disagreement is exactly what
     * the damping is for.
     *
     * The first pair sets the offset outright rather than easing towards it, so a scan is not
     * dragged through a wrong compass while the filter finds its feet. A pair containing a NaN is
     * ignored and leaves the offset as it was, because a sensor that has failed once will say so
     * by returning nothing, and "no new information" is not the same as "north is unchanged".
     */
    public fun update(magneticHeadingDegrees: Double, gameYawDegrees: Double): Double {
        if (magneticHeadingDegrees.isNaN() || gameYawDegrees.isNaN()) return offsetDegrees()

        val radians = Math.toRadians(magneticHeadingDegrees - gameYawDegrees)
        val sin = sin(radians)
        val cos = cos(radians)
        if (!seeded) {
            smoothedSin = sin
            smoothedCos = cos
            seeded = true
        } else {
            smoothedSin += smoothingFactor * (sin - smoothedSin)
            smoothedCos += smoothingFactor * (cos - smoothedCos)
        }
        return offsetDegrees()
    }

    /**
     * The offset currently believed, in degrees: how far the gyroscope's heading has to be turned
     * clockwise to reach magnetic north.
     *
     * Zero before the first [update], and after [reset]. A caller that wants to know whether the
     * compass has said anything yet asks [hasReading]; zero is a perfectly good offset and there
     * is no other way to tell "pointing north" from "has never looked".
     */
    public fun offsetDegrees(): Double = if (!seeded) 0.0 else RadarTracker.normalize(
        Math.toDegrees(atan2(smoothedSin, smoothedCos)),
    )

    /** True once the magnetometer has contributed a reading since the last [reset]. */
    public fun hasReading(): Boolean = seeded

    /**
     * A gyroscope heading turned into the compass heading it stands for.
     *
     * This is the one direction the conversion is safe in, because the radar only ever asks
     * about *relative* bearings - how far the phone turned from one reading to the next - and a
     * constant offset added to both ends cancels exactly. It is not safe for a direction the user
     * is about to walk towards, because the offset itself is only as good as the last magnetometer
     * reading, and drift in between is uncorrected. Callers showing a compass point need the
     * magnetometer's own reported accuracy as well.
     */
    public fun magneticHeadingFor(gameYawDegrees: Double): Double =
        RadarTracker.normalize(gameYawDegrees + offsetDegrees())

    /** Forgets everything, as though no reading had ever arrived. */
    public fun reset() {
        smoothedSin = 0.0
        smoothedCos = 0.0
        seeded = false
    }

    public companion object {
        /**
         * The default damping: about a fifth of each new difference.
         *
         * At the fifty readings a second a rotation-vector sensor delivers, that is a time
         * constant near a fifth of a second - fast enough that a real change in magnetic north
         * (walking to another side of the building) is followed within a second or two, slow
         * enough that a passing magnet does not move the dial at all.
         */
        public const val DEFAULT_SMOOTHING: Double = 0.2
    }
}
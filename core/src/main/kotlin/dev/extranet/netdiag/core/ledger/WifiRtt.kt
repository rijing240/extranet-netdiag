package dev.extranet.netdiag.core.ledger

/**
 * Wi-Fi round-trip-time ranging (IEEE 802.11mc / FTM).
 *
 *     d = c * RTT / 2
 *
 * A 20 ns round trip is 2.998 m with the exact speed of light, and 3.00 m with the rounded
 * value. Both are within 0.01 m of each other, so the ledger uses the exact constant and the
 * tests assert the rounded figure to two decimal places.
 *
 * ## Availability is the hard constraint, not the maths
 * This only works when the access point itself implements the FTM responder side of
 * 802.11mc. Urban deployments are sparse, so every call site must be gated on a capability
 * check rather than assuming the API's presence implies a usable result. Android additionally
 * requires the scan to run in the foreground and needs location-adjacent permission.
 */
public object WifiRtt {

    /** Device feature flag that must be present for any RTT API to be worth calling. */
    public const val REQUIRED_FEATURE: String = "android.hardware.wifi.rtt"

    /** Round trips faster than this are physically implausible and indicate a bad measurement. */
    public const val MIN_PLAUSIBLE_ROUND_TRIP_NANOS: Long = 1L

    /** One-way distance in metres for a round trip measured in nanoseconds. */
    public fun distanceMetres(roundTripNanos: Double): Double =
        PhysicalConstants.SPEED_OF_LIGHT_MPS * (roundTripNanos * 1.0e-9) / 2.0

    /**
     * The documented accuracy of an individual RTT measurement, metres. Used to widen a
     * reported distance into a [RangeBand] instead of treating it as exact.
     */
    public const val TYPICAL_ACCURACY_METRES: Double = 2.0

    /** Wraps a measured distance in a band reflecting the documented 1-2 m accuracy. */
    public fun rangeMetres(roundTripNanos: Double): RangeBand {
        val centre = distanceMetres(roundTripNanos)
        val half = TYPICAL_ACCURACY_METRES / 2.0
        return RangeBand(kotlin.math.max(0.0, centre - half), centre + half)
    }
}

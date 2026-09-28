package dev.extranet.netdiag.core.ledger

/**
 * Fundamental physical constants used by the calculation ledger.
 *
 * Two spellings of the speed of light are kept deliberately. The exact SI value is used
 * everywhere real distance is computed. The rounded 3x10^8 value is retained only so the
 * ledger can explain where the widely quoted "78.125 m per LTE timing-advance step"
 * figure comes from; it is 0.07% larger than reality (a 3.4 m error at 5 km).
 */
public object PhysicalConstants {

    /** Exact speed of light in vacuum, m/s (SI definition). */
    public const val SPEED_OF_LIGHT_MPS: Double = 299_792_458.0

    /** Rounded c = 3x10^8 m/s. Historical basis of the 78.125 m TA step. Not used for ranging. */
    public const val ROUNDED_SPEED_OF_LIGHT_MPS: Double = 300_000_000.0

    /** Metres in one kilometre, for display conversions. */
    public const val METRES_PER_KILOMETRE: Double = 1_000.0
}

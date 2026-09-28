package dev.extranet.netdiag.core.ledger

/**
 * GNSS Doppler (pseudorange-rate) velocity.
 *
 *     f_d = (v . u) / lambda
 *
 * Velocity noise is the carrier wavelength times the Doppler measurement noise:
 *
 *     sigma_v = lambda * sigma_f
 *
 * For GPS L1 at 1575.42 MHz the wavelength is 0.19029 m, so 1 Hz of Doppler noise is
 * 0.19 m/s. Two important consequences:
 *
 * 1. The error does **not** grow with the sampling interval, unlike differencing successive
 *    coordinates. This is why Doppler speed is smoother and reacts faster than GPS speed.
 * 2. Android already hands the caller the converted quantity:
 *    `GnssMeasurement.getPseudorangeRateMetersPerSecond()`. Do not re-derive it from
 *    `getCarrierDopplerHz()`; use the platform's value and solve the small least-squares
 *    system for the 3-vector velocity plus receiver clock drift (4 unknowns, >=4 satellites).
 *
 * The ledger keeps the wavelength constants because they are the documented uncertainty
 * budget, not because the conversion has to be redone.
 */
public object Doppler {

    /** GPS L1 carrier frequency, Hz. */
    public const val L1_CARRIER_HZ: Double = 1_575_420_000.0

    /** GPS L1 carrier wavelength, metres: 0.190294... */
    public val L1_WAVELENGTH_METRES: Double = PhysicalConstants.SPEED_OF_LIGHT_MPS / L1_CARRIER_HZ

    /** 5G NR band n78 centre frequency, Hz. Useful for the cellular-side sanity check. */
    public const val NR_N78_CARRIER_HZ: Double = 3_500_000_000.0

    /** NR n78 carrier wavelength, metres. */
    public val NR_N78_WAVELENGTH_METRES: Double = PhysicalConstants.SPEED_OF_LIGHT_MPS / NR_N78_CARRIER_HZ

    /** Optimistic Doppler measurement noise, Hz. */
    public const val DOPPLER_SIGMA_HZ_MIN: Double = 0.5

    /** Pessimistic Doppler measurement noise, Hz. */
    public const val DOPPLER_SIGMA_HZ_MAX: Double = 2.0

    /**
     * The headline uncertainty for the shipped feature: 1 Hz of Doppler noise on L1 maps to
     * 0.1903 m/s of velocity noise.
     */
    public val L1_VELOCITY_SIGMA_MPS: Double = 1.0 * L1_WAVELENGTH_METRES

    /** Velocity uncertainty for a given Doppler noise level and carrier wavelength. */
    public fun velocitySigmaMetresPerSecond(
        dopplerSigmaHz: Double,
        wavelengthMetres: Double = L1_WAVELENGTH_METRES,
    ): Double = dopplerSigmaHz * wavelengthMetres

    /** Number of unknowns in the velocity solve: 3 velocity components + receiver clock drift. */
    public const val VELOCITY_SOLVE_UNKNOWNS: Int = 4

    /** Minimum satellites required for the velocity solve to be well posed. */
    public const val VELOCITY_SOLVE_MIN_SATELLITES: Int = VELOCITY_SOLVE_UNKNOWNS
}

package dev.extranet.netdiag.core.report

/**
 * How a probed platform API actually behaved on the device.
 *
 * The distinction between [UNAVAILABLE] and [FEATURE_ABSENT] matters for the roadmap: the
 * first means "the modem has this API but returned no value" (a per-device calibration
 * problem), while the second means "this hardware cannot ever report it" (a design
 * constraint). [NOT_PROBED] exists so asynchronous probes, which need a live session rather
 * than a synchronous read, are visible in the report instead of silently missing.
 */
public enum class SupportStatus {
    /** Returned a usable value. */
    SUPPORTED,

    /** API exists and was callable, but returned the platform UNAVAILABLE sentinel or null. */
    UNAVAILABLE,

    /** The call threw. The finding's detail carries the exception class and message. */
    THROWS,

    /** Not attempted or refused because a required runtime permission is not granted. */
    PERMISSION_DENIED,

    /** Device API level is below the level at which the API exists. Not attempted. */
    BELOW_API_LEVEL,

    /** Device does not advertise the required hardware feature. Not attempted. */
    FEATURE_ABSENT,

    /** Needs a live session or callback registration; not observable from a synchronous read. */
    NOT_PROBED,
}

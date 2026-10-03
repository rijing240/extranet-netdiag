package dev.extranet.netdiag.core.verdict

/**
 * Whether a collector actually produced a number.
 *
 * The three cases are different facts and stay different all the way to the screen: [FAILED] is
 * something that was attempted and did not answer, [UNAVAILABLE] is something the platform does
 * not offer, and neither may be rendered as zero. An empty stretch of time is not a flat line.
 */
public enum class SampleStatus {
    /** A reading was obtained. */
    OK,

    /** Attempted, and the attempt did not produce a reading. */
    FAILED,

    /** The platform does not expose this reading on this device. */
    UNAVAILABLE,
}

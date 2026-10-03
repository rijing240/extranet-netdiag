package dev.extranet.netdiag.core.verdict

/**
 * Why a subject is in the state it is in, when one word is too coarse to carry it.
 *
 * [DiagnosisState] is deliberately a short closed list because it is what a screen prints. It
 * cannot say *which* thing is wrong, though, and the difference matters enormously to the person
 * reading the answer: mobile data that a carrier has switched off, mobile data a person
 * switched off in Settings, and mobile data blocked by a phone policy are all [DiagnosisState.OFFLINE],
 * and they need three completely different instructions - a top-up, one tap, and a phone call to
 * an administrator respectively.
 *
 * So a finding may carry a cause beside its state, and only the decision layer reads it, because
 * only the decision layer writes sentences. The alternative - recognising the situation from the
 * wording of the evidence - couples the copy to the inference and breaks silently the first time
 * somebody rewords a sentence: the rules keep running and quietly return a weaker answer.
 *
 * The set is closed for the same reason [DiagnosisState] is. A finding whose cause is not on
 * this list has none, and is worded as the plain state it is.
 */
public enum class FindingCause {
    /** Carrier signalling or a carrier privileged app disabled mobile data; exact reason unknown. */
    CARRIER_BLOCKED,

    /** A device policy or parental control switched mobile data off. */
    POLICY_BLOCKED,

    /** The user switched mobile data off in Settings. */
    USER_DISABLED,

    /** The phone switched mobile data off to protect its temperature or its battery. */
    THERMAL_BLOCKED,

    /** Mobile data is off and the platform will not say who turned it off. */
    BLOCKED_UNKNOWN,

    /**
     * Every hop answered but the way out to the internet is much slower than the way to the
     * local link: the network is busy rather than broken.
     */
    CONGESTED,
}

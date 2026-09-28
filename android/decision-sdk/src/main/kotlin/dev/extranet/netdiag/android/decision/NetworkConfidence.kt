package dev.extranet.netdiag.android.decision

/**
 * S4 Decision SDK: the paid surface.
 *
 * This is the only part of the blueprint a customer ever imports, and it is the only part that
 * must never lie. It exists as an interface in B0 so the shape is fixed early and so nobody is
 * tempted to ship a stub implementation that a payment flow could bind to.
 *
 * ## The ordering constraint this interface encodes
 * The plan ships the consumer diagnostic app (B1-B7) before this SDK (B8-B10) precisely because
 * this interface cannot be honoured without a measured model. [isModelCalibrated] is the gate:
 * an integrator is expected to check it and fall back to its existing timeout-based behaviour
 * rather than trusting an uncalibrated verdict.
 *
 * ## What this SDK deliberately does not solve
 * Extending offline credit creates double-spend, replay and repudiation risk. Predicting the
 * drop only decides *when* to queue a transaction; the ledger, signing, idempotency and a
 * per-user risk-priced ceiling are the integrator's problem, and the plan calls that out as the
 * real work behind "zero failed payments".
 */
public interface NetworkConfidence {

    /**
     * Current confidence that the network is usable.
     *
     * @return a reading in `[0, 1]`, or `null` when there is not enough history to answer. A
     *   null must be treated as "no opinion" rather than as a low score.
     */
    public fun confidence(): Double?

    /** True once the underlying model has a measured on-device precision/recall curve. */
    public val isModelCalibrated: Boolean

    /** Whether this device can back the estimate at all, per the S7 capability registry. */
    public val isSupportedOnThisDevice: Boolean
}

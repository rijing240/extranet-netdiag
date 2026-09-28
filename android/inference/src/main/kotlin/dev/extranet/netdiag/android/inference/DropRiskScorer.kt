package dev.extranet.netdiag.android.inference

/**
 * S3 Inference seam.
 *
 * This boundary exists in B0 so that the presentation layer and the Decision SDK can be written
 * against a stable interface before there is a model behind it. It is intentionally the only
 * thing in this module: shipping a placeholder predictor would be worse than shipping nothing,
 * because the plan's whole schedule depends on the model being *measured* rather than assumed.
 *
 * ## What B8 and B9 have to supply before this can be implemented
 * - B8: at least 385 labelled positive events, the ceiling imposed by
 *   `ModelGate.requiredPositiveSamples()`, and a precision/recall curve at 2 s, 5 s and 10 s.
 * - B9: a threshold tuned so that precision holds at 85% or better, because a false "you are
 *   about to lose signal" makes a payment app queue a transaction unnecessarily.
 *
 * ## Feature set the model is expected to consume
 * Radio: RSRP, RSRQ, SINR slopes over a 10 s window; timing-advance delta; serving band;
 * RAT downgrade flag. Environment: GNSS C/N0 slope, barometric delta. Network: transport
 * change, service state, time since the last RRC transition. Context: screen state and Doppler
 * velocity from `SampleBudget`-gated sampling.
 */
public interface DropRiskScorer {

    /**
     * Probability that the device loses usable service within [horizonSeconds].
     *
     * @return a value in `[0, 1]`, or `null` when there is not yet enough history to score.
     *   Returning null rather than a default is deliberate: the Decision SDK must be able to
     *   distinguish "no risk" from "no opinion".
     */
    public fun riskOfLoss(horizonSeconds: Int): Double?

    /** True when the score is backed by a model whose precision has been measured on device. */
    public val isCalibrated: Boolean
}

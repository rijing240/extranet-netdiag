package dev.extranet.netdiag.core.verdict

/**
 * One inference-layer claim about one subject.
 *
 * A finding is an observation, not advice: it says what was seen and how sure the inference is,
 * and the decision layer turns findings into a [Verdict] with the copy that goes on screen. It
 * borrows [DiagnosisState] so the words never have to be translated between layers, minus
 * [DiagnosisState.CHECKING], which describes a test rather than something observed.
 *
 * @property subject what the claim is about: a hop of the path ("hop1", "hop2") or a subsystem
 *   ("signal", "dns", "throughput").
 * @property assessment how the subject looks, in the same words the verdict will use.
 * @property confidence 0.0 to 1.0; the decision layer refuses to diagnose below its floor.
 * @property evidence the measured facts behind the claim, e.g. "median RSRP -105 dBm over 60
 *   samples". Facts only: advice belongs to the decision layer.
 */
public data class Finding(
    public val subject: String,
    public val assessment: DiagnosisState,
    public val confidence: Double,
    public val evidence: List<String>,
) {
    init {
        require(subject.isNotBlank()) { "a finding must name its subject" }
        require(confidence in 0.0..1.0) { "confidence is a fraction between 0 and 1, got $confidence" }
        require(assessment != DiagnosisState.CHECKING) {
            "a finding records something observed, not a test in progress"
        }
        require(evidence.isNotEmpty()) { "a finding without evidence is an opinion" }
    }
}

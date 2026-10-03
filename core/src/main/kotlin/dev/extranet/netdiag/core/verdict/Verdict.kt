package dev.extranet.netdiag.core.verdict

/**
 * The one shape every screen shows: what happened, where the problem is, what to do.
 *
 * The nullable fields are null exactly where the truth is missing rather than filled in:
 * [where] stays null when there is no cause to name, which is what [DiagnosisState.UNKNOWN]
 * means, and [action] is null only while a check is still running. Every other state must name
 * a cause and an action, so a screen cannot render advice that nothing supports.
 *
 * @property state the word shown first.
 * @property what what happened, as measured.
 * @property where where the problem is; null when it could not be determined.
 * @property action what to do; null only while [state] is [DiagnosisState.CHECKING].
 * @property confidence 0.0 to 1.0 behind the cause.
 * @property evidence the facts behind the answer, shown under "Evidence".
 */
public data class Verdict(
    public val state: DiagnosisState,
    public val what: String,
    public val where: String?,
    public val action: String?,
    public val confidence: Double,
    public val evidence: List<String>,
) {
    init {
        require(what.isNotBlank()) { "a verdict must say what happened" }
        require(confidence in 0.0..1.0) { "confidence is a fraction between 0 and 1, got $confidence" }
        when (state) {
            DiagnosisState.CHECKING -> require(where == null && action == null) {
                "a check in progress has no cause and no advice yet"
            }

            DiagnosisState.UNKNOWN -> {
                require(where == null) { "an Unknown verdict must not name a cause" }
                require(!action.isNullOrBlank()) { "an Unknown verdict still says what to do next" }
                require(evidence.isNotEmpty()) { "an Unknown verdict says which evidence was missing" }
            }

            else -> {
                require(!where.isNullOrBlank()) { "a diagnosis must name where the problem is" }
                require(!action.isNullOrBlank()) { "a diagnosis must say what to do" }
                require(evidence.isNotEmpty()) { "a diagnosis without evidence is an opinion" }
            }
        }
    }

    /**
     * The same verdict with its cause withheld when [confidence] does not clear [floor].
     *
     * The measurement in [what] survives because it happened; the cause and the advice do not,
     * because both were inferred from evidence too weak to show. [retryAction] is what the
     * decision layer offers instead. A check in progress, and a verdict that is already
     * [DiagnosisState.UNKNOWN], pass through untouched.
     */
    public fun withheldBelow(floor: Double, retryAction: String): Verdict {
        require(floor in 0.0..1.0) { "the confidence floor is a fraction between 0 and 1, got $floor" }
        return when {
            state == DiagnosisState.CHECKING || state == DiagnosisState.UNKNOWN -> this
            confidence >= floor -> this
            else -> {
                require(retryAction.isNotBlank()) { "withholding a cause needs advice to show instead" }
                copy(state = DiagnosisState.UNKNOWN, where = null, action = retryAction)
            }
        }
    }

    public companion object {
        /** Copy for the cause slot when there is no cause to name. */
        public const val NO_CAUSE_COPY: String = "Couldn't determine the cause"
    }
}

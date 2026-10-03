package dev.extranet.netdiag.core.verdict

/**
 * The seven words a result may use.
 *
 * The set is closed so no screen invents its own state and no percentage has to be explained:
 * the "Network Health 72%" figure was dropped because no formula behind it had been agreed.
 * [CHECKING] is a test in progress; [UNKNOWN] is a real outcome rather than an error, shown
 * whenever the evidence does not support a diagnosis.
 */
public enum class DiagnosisState(public val label: String) {
    GOOD("Good"),
    FAIR("Fair"),
    SLOW("Slow"),
    WEAK("Weak"),
    OFFLINE("Offline"),
    CHECKING("Checking"),
    UNKNOWN("Unknown"),
    ;

    /** True for the five states that make a claim about the network. */
    public val isDiagnosis: Boolean
        get() = when (this) {
            GOOD, FAIR, SLOW, WEAK, OFFLINE -> true
            CHECKING, UNKNOWN -> false
        }
}

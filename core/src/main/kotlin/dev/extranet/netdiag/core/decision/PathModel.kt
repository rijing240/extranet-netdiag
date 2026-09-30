package dev.extranet.netdiag.core.decision

import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding

/** One hop of the path diagram: what it is called and how it looked. */
public data class PathNode(
    public val name: String,
    public val state: DiagnosisState,
    /** The first measured fact behind the state, or null when nothing was measured. */
    public val detail: String?,
)

/**
 * The one path diagram: Phone, then the network in front of it, then the internet.
 *
 * The diagram exists so Checkup, Hotspot and Wi-Fi diagnosis cannot disagree about where the
 * problem is: they all read the same findings and produce the same three states. The nodes name
 * the *role* (the first hop, whatever box provides it) rather than the hardware, because Android
 * cannot tell a router from a phone hotspot from a carrier NAT - and a diagram that guessed
 * would be wrong on exactly the networks this app exists to diagnose.
 */
public object PathModel {

    /**
     * Builds the three nodes from the findings of one checkup.
     *
     * A hop that was never measured is [DiagnosisState.UNKNOWN], not Good: the diagram's whole
     * job is to distinguish "this hop is fine" from "nobody asked this hop".
     */
    public fun of(findings: List<Finding>): List<PathNode> {
        val phone = findings.firstOrNull { it.subject == Subjects.SIGNAL }
        val localLink = findings.firstOrNull { it.subject == Subjects.FIRST_HOP }
        val internet = findings.firstOrNull { it.subject == Subjects.INTERNET_HOP }
        return listOf(
            PathNode("This phone", phone.state(), phone?.evidence?.firstOrNull()),
            PathNode("Network / router", localLink.state(), localLink?.evidence?.firstOrNull()),
            PathNode("Internet", internet.state(), internet?.evidence?.firstOrNull()),
        )
    }

    private fun Finding?.state(): DiagnosisState = this?.assessment ?: DiagnosisState.UNKNOWN
}

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
 * problem is: they all read the same findings and produce the same three states. The middle
 * node is named by the *measured* link, not one guessed at: a phone on Wi-Fi is behind a
 * router, a phone on mobile data is behind a carrier cell. The link's own evidence carries
 * its type ("... samples on WIFI"), so the diagram reads the same facts the rules do rather
 * than a parallel flag - and evidence that names no type gets the neutral wording, because a
 * diagram that guessed would be wrong on exactly the networks this app exists to diagnose.
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
            PathNode(middleNodeName(phone), localLink.state(), localLink?.evidence?.firstOrNull()),
            PathNode("Internet", internet.state(), internet?.evidence?.firstOrNull()),
        )
    }

    /**
     * What the middle node is called, decided by the phone finding's own evidence.
     *
     * The sample's `networkType` is written into that evidence by the inference layer, so the
     * diagram reads the same facts the rules do rather than a parallel flag: on Wi-Fi the first
     * hop is the router, on any cellular technology it is the carrier, and evidence naming no
     * type keeps the role name rather than a guess about the box.
     */
    internal fun middleNodeName(phone: Finding?): String {
        val marker = phone?.evidence?.firstOrNull() ?: return "Network"
        return when {
            marker.contains(" on WIFI") -> "Wi-Fi router"
            marker.contains(" on ") -> "Carrier network"
            else -> "Network"
        }
    }

    private fun Finding?.state(): DiagnosisState = this?.assessment ?: DiagnosisState.UNKNOWN
}

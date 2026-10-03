package dev.extranet.netdiag.core.decision

import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the path diagram: three hops, and an honest state for a hop nobody measured.
 *
 * The point of the diagram is that Checkup, Hotspot and Wi-Fi diagnosis read the same three
 * states, so the failure this test guards against is a hop silently rendered as Good because
 * its finding was absent.
 */
class PathModelTest {

    private fun finding(subject: String, assessment: DiagnosisState, evidence: String): Finding =
        Finding(subject, assessment, 0.9, listOf(evidence))

    @Test
    fun anUnmeasuredHopIsUnknownRatherThanGood() {
        val nodes = PathModel.of(emptyList())
        // No phone finding, no transport marker: the middle node keeps the neutral role name
        // rather than guessing a router or a tower the session never saw.
        assertEquals(listOf("This phone", "Network", "Internet"), nodes.map { it.name })
        assertEquals(listOf(DiagnosisState.UNKNOWN, DiagnosisState.UNKNOWN, DiagnosisState.UNKNOWN), nodes.map { it.state })
        assertNull(nodes.first().detail)
    }

    @Test
    fun theMiddleNodeIsNamedByTheTransportTheEvidenceRecords() {
        fun phoneFinding(evidence: String): Finding =
            finding(Subjects.SIGNAL, DiagnosisState.GOOD, evidence)
        assertEquals(
            "Wi-Fi router",
            PathModel.of(listOf(phoneFinding("median -45 dBm over 10 samples on WIFI")))[1].name,
            "a Wi-Fi phone's first hop is the router, not a tower",
        )
        assertEquals(
            "Carrier network",
            PathModel.of(listOf(phoneFinding("median -95 dBm over 10 samples on LTE")))[1].name,
            "a cellular phone's first hop is the carrier, not a router",
        )
        assertEquals(
            "Network",
            PathModel.of(listOf(phoneFinding("no type was recorded")))[1].name,
            "evidence naming no type gets the neutral name, not a guess",
        )
    }

    @Test
    fun theDiagramNamesTheHopThatFailedNotTheHardware() {
        val nodes = PathModel.of(
            listOf(
                finding(Subjects.SIGNAL, DiagnosisState.GOOD, "median -91 dBm over 60 samples on LTE"),
                finding(Subjects.FIRST_HOP, DiagnosisState.GOOD, "gateway 10.0.0.1: 8 ms"),
                finding(Subjects.INTERNET_HOP, DiagnosisState.OFFLINE, "no answer in 3 rounds"),
            ),
        )
        assertEquals(DiagnosisState.GOOD, nodes[0].state)
        assertEquals(DiagnosisState.GOOD, nodes[1].state)
        assertEquals(DiagnosisState.OFFLINE, nodes[2].state)
        assertEquals("no answer in 3 rounds", nodes[2].detail)
        assertEquals("Carrier network", nodes[1].name)
    }
}

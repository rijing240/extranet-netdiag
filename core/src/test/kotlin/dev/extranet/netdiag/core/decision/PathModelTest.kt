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
        assertEquals(listOf("This phone", "Network / router", "Internet"), nodes.map { it.name })
        assertEquals(listOf(DiagnosisState.UNKNOWN, DiagnosisState.UNKNOWN, DiagnosisState.UNKNOWN), nodes.map { it.state })
        assertNull(nodes.first().detail)
    }

    @Test
    fun theDiagramNamesTheHopThatFailedNotTheHardware() {
        val nodes = PathModel.of(
            listOf(
                finding(Subjects.SIGNAL, DiagnosisState.GOOD, "median RSRP -91 dBm over 60 samples"),
                finding(Subjects.FIRST_HOP, DiagnosisState.GOOD, "gateway 10.0.0.1: 8 ms"),
                finding(Subjects.INTERNET_HOP, DiagnosisState.OFFLINE, "no answer in 3 rounds"),
            ),
        )
        assertEquals(DiagnosisState.GOOD, nodes[0].state)
        assertEquals(DiagnosisState.GOOD, nodes[1].state)
        assertEquals(DiagnosisState.OFFLINE, nodes[2].state)
        assertEquals("no answer in 3 rounds", nodes[2].detail)
    }
}

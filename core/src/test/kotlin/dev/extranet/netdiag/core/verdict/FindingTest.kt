package dev.extranet.netdiag.core.verdict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pins the inference contract: a finding names what it saw, with the evidence behind it. */
class FindingTest {

    @Test
    fun aFindingUsesTheSameWordsTheVerdictWillUse() {
        val finding = Finding(
            subject = "signal",
            assessment = DiagnosisState.WEAK,
            confidence = 0.8,
            evidence = listOf("median RSRP -105 dBm over 60 samples"),
        )
        assertEquals("signal", finding.subject)
        assertEquals(DiagnosisState.WEAK, finding.assessment)
        assertEquals(listOf("median RSRP -105 dBm over 60 samples"), finding.evidence)
    }

    @Test
    fun aFindingWithoutEvidenceIsAnOpinion() {
        assertFailsWith<IllegalArgumentException> {
            Finding("signal", DiagnosisState.WEAK, 0.8, emptyList())
        }
    }

    @Test
    fun aFindingIsAnObservationNotATestInProgress() {
        assertFailsWith<IllegalArgumentException> {
            Finding("signal", DiagnosisState.CHECKING, 0.8, listOf("still running"))
        }
    }

    @Test
    fun aFindingMustNameItsSubject() {
        assertFailsWith<IllegalArgumentException> {
            Finding("  ", DiagnosisState.WEAK, 0.8, listOf("median RSRP -105 dBm"))
        }
    }

    @Test
    fun confidenceIsAFractionOfCertainty() {
        assertFailsWith<IllegalArgumentException> {
            Finding("signal", DiagnosisState.WEAK, 1.2, listOf("median RSRP -105 dBm"))
        }
        assertFailsWith<IllegalArgumentException> {
            Finding("signal", DiagnosisState.WEAK, -0.1, listOf("median RSRP -105 dBm"))
        }
    }
}

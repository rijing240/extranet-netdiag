package dev.extranet.netdiag.core.verdict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Pins the one result shape: what happened, where, what to do — and what may be left blank. */
class VerdictTest {

    private fun slowDownload(confidence: Double = 0.7): Verdict = Verdict(
        state = DiagnosisState.SLOW,
        what = "1.6 kB moved in 935 ms",
        where = "the network between your phone and the internet",
        action = "Run the check again when you are somewhere else",
        confidence = confidence,
        evidence = listOf("160 ms ping, 0.00 Mbps over 20 s"),
    )

    @Test
    fun aDiagnosisNamesItsCauseAndItsAction() {
        val verdict = slowDownload()
        assertEquals(DiagnosisState.SLOW, verdict.state)
        assertEquals("the network between your phone and the internet", verdict.where)
        assertEquals(0.7, verdict.confidence)
    }

    @Test
    fun aDiagnosisWithoutACauseIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            slowDownload().copy(where = null)
        }
    }

    @Test
    fun unknownMustNotNameACause() {
        assertFailsWith<IllegalArgumentException> {
            Verdict(
                state = DiagnosisState.UNKNOWN,
                what = "only three samples arrived",
                where = "the cell tower",
                action = "Run the check again",
                confidence = 0.2,
                evidence = listOf("3 samples in 60 s"),
            )
        }
    }

    @Test
    fun unknownStillSaysWhatToDoAndWhichEvidenceWasMissing() {
        val verdict = Verdict(
            state = DiagnosisState.UNKNOWN,
            what = "only three samples arrived",
            where = null,
            action = "Run the check again",
            confidence = 0.2,
            evidence = listOf("3 samples in 60 s"),
        )
        assertNull(verdict.where)
        assertEquals("Run the check again", verdict.action)
    }

    @Test
    fun checkingHasNoCauseAndNoAdviceYet() {
        val checking = Verdict(
            state = DiagnosisState.CHECKING,
            what = "Running the speed check",
            where = null,
            action = null,
            confidence = 0.0,
            evidence = emptyList(),
        )
        assertEquals(DiagnosisState.CHECKING, checking.state)
        assertFailsWith<IllegalArgumentException> { checking.copy(action = "Hold still") }
    }

    @Test
    fun belowTheFloorTheCauseAndTheAdviceAreWithheld() {
        val shown = slowDownload(confidence = 0.3).withheldBelow(
            floor = 0.5,
            retryAction = "Run the check again",
        )
        assertEquals(DiagnosisState.UNKNOWN, shown.state)
        assertNull(shown.where)
        assertEquals("Run the check again", shown.action)
        assertEquals("1.6 kB moved in 935 ms", shown.what)
        assertEquals(0.3, shown.confidence)
    }

    @Test
    fun atOrAboveTheFloorTheVerdictStands() {
        val shown = slowDownload(confidence = 0.5).withheldBelow(
            floor = 0.5,
            retryAction = "Run the check again",
        )
        assertEquals(DiagnosisState.SLOW, shown.state)
    }

    @Test
    fun aCheckInProgressIsNotCollapsedToUnknown() {
        val checking = Verdict(
            state = DiagnosisState.CHECKING,
            what = "Running the speed check",
            where = null,
            action = null,
            confidence = 0.0,
            evidence = emptyList(),
        )
        assertEquals(DiagnosisState.CHECKING, checking.withheldBelow(0.5, "Run the check again").state)
    }

    @Test
    fun theUnknownCopyIsThePhraseTheSpecChose() {
        assertEquals("Couldn't determine the cause", Verdict.NO_CAUSE_COPY)
    }

    @Test
    fun confidenceIsAFractionOfCertainty() {
        assertFailsWith<IllegalArgumentException> { slowDownload(confidence = 1.5) }
    }

    @Test
    fun theStateVocabularyIsTheSevenAgreedWords() {
        assertEquals(
            listOf("Good", "Fair", "Slow", "Weak", "Offline", "Checking", "Unknown"),
            DiagnosisState.entries.map { it.label },
        )
        assertEquals(5, DiagnosisState.entries.count { it.isDiagnosis })
    }
}

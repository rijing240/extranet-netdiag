package dev.extranet.netdiag.core.decision

import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the sentences the app is allowed to say.
 *
 * These tests are the reason the copy lives in a pure function: every one of them asks what a
 * user would see, so a rule that starts blaming the tower for a dead router fails here rather
 * than in a screenshot someone has to notice.
 */
class DiagnosisRulesTest {

    private val rules = DiagnosisRules()

    private fun finding(
        subject: String,
        assessment: DiagnosisState,
        confidence: Double = 0.9,
        evidence: String = "median 12 ms over 3 rounds",
    ): Finding = Finding(subject, assessment, confidence, listOf(evidence))

    private fun signal(assessment: DiagnosisState, confidence: Double = 0.9): Finding =
        finding(Subjects.SIGNAL, assessment, confidence, "median RSRP -118 dBm over 60 samples")

    private fun localLink(assessment: DiagnosisState, confidence: Double = 0.85): Finding =
        finding(Subjects.FIRST_HOP, assessment, confidence, "gateway 10.0.0.1: 8 ms")

    private fun internet(assessment: DiagnosisState, confidence: Double = 0.85): Finding =
        finding(Subjects.INTERNET_HOP, assessment, confidence, "connectivitycheck.gstatic.com: 42 ms")

    private fun throughput(assessment: DiagnosisState, confidence: Double = 0.9): Finding =
        finding(Subjects.THROUGHPUT, assessment, confidence, "1 MB in 0.5 s, 16.0 Mbps")

    @Test
    fun bothHopsDeadNamesTheLocalLinkRatherThanHardwareTheUserMayNotHave() {
        val verdict = rules.checkup(listOf(localLink(DiagnosisState.OFFLINE), internet(DiagnosisState.OFFLINE)))
        assertEquals(DiagnosisState.OFFLINE, verdict.state)
        assertEquals("The local link, and everything past it", verdict.where)
        assertTrue(verdict.action!!.contains("mobile data"))
    }

    @Test
    fun theInternetBeingDeadWithoutAFirstHopFindingSaysTheHopWasUntested() {
        // The finding's evidence names an address, but no finding exists for that hop: this is
        // the case the B3 device run produced, where no first hop could be determined at all.
        val verdict = rules.checkup(listOf(internet(DiagnosisState.OFFLINE)))
        assertEquals(DiagnosisState.OFFLINE, verdict.state)
        assertTrue(verdict.what.contains("the local link was not tested"))
        assertTrue(
            verdict.evidence.contains("connectivitycheck.gstatic.com: 42 ms"),
            "the only evidence available is the internet hop's own, and it must be shown",
        )
    }

    @Test
    fun theLocalLinkAnsweringWhileTheInternetDoesNotBlamesBeyondTheLocalLink() {
        val verdict = rules.checkup(listOf(localLink(DiagnosisState.GOOD), internet(DiagnosisState.OFFLINE)))
        assertEquals(DiagnosisState.OFFLINE, verdict.state)
        assertEquals("Beyond the local link: the carrier or the upstream network", verdict.where)
    }

    @Test
    fun reachabilityOutranksSpeedSoADeadLinkIsNeverCalledSlow() {
        val verdict = rules.checkup(
            listOf(
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.OFFLINE),
                throughput(DiagnosisState.SLOW),
            ),
        )
        assertEquals(DiagnosisState.OFFLINE, verdict.state)
    }

    @Test
    fun aFilteredFirstHopIsFairRatherThanOffline() {
        val verdict = rules.checkup(listOf(localLink(DiagnosisState.OFFLINE), internet(DiagnosisState.GOOD)))
        assertEquals(DiagnosisState.FAIR, verdict.state)
        assertTrue(verdict.where!!.contains("filtering the probe"))
    }

    @Test
    fun aReachablePathThatBarelyMovesIsSlowAndPointsAtTheNetwork() {
        val verdict = rules.checkup(listOf(localLink(DiagnosisState.GOOD), internet(DiagnosisState.GOOD), throughput(DiagnosisState.SLOW)))
        assertEquals(DiagnosisState.SLOW, verdict.state)
        assertEquals("The network between your phone and the internet", verdict.where)
        assertTrue(verdict.evidence.contains("1 MB in 0.5 s, 16.0 Mbps"))
    }

    @Test
    fun movingDataOnAWeakLinkIsFairAndNamesTheDistance() {
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.WEAK),
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.GOOD),
                throughput(DiagnosisState.GOOD),
            ),
        )
        assertEquals(DiagnosisState.FAIR, verdict.state)
        assertTrue(verdict.where!!.startsWith("Your distance from the tower"))
    }

    @Test
    fun everythingAnsweringIsGoodAndNamesNoFault() {
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.GOOD),
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.GOOD),
                throughput(DiagnosisState.GOOD),
            ),
        )
        assertEquals(DiagnosisState.GOOD, verdict.state)
        assertEquals("Every hop answered and data is moving.", verdict.what)
        assertEquals("Nowhere in the path: nothing failed", verdict.where)
    }

    @Test
    fun aCheckThatMeasuredNoHopIsUnknownAndOffersARetry() {
        val verdict = rules.checkup(emptyList())
        assertEquals(DiagnosisState.UNKNOWN, verdict.state)
        assertNull(verdict.where)
        assertEquals("Run the checkup again", verdict.action)
        assertTrue(verdict.evidence.isNotEmpty())
    }

    @Test
    fun aCauseBelowTheFloorIsWithheldButTheMeasurementSurvives() {
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.WEAK, confidence = 0.3),
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.GOOD),
            ),
        )
        assertEquals(DiagnosisState.UNKNOWN, verdict.state)
        assertNull(verdict.where)
        assertEquals("Run the checkup again", verdict.action)
        assertTrue(verdict.what.contains("speed check did not finish"))
    }

    @Test
    fun theFloorIsAParameterSoTheNumberCanBeAgreedLater() {
        val strict = DiagnosisRules(floor = 0.95)
        val verdict = strict.checkup(
            listOf(
                signal(DiagnosisState.WEAK, confidence = 0.9),
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.GOOD),
            ),
        )
        assertEquals(DiagnosisState.UNKNOWN, verdict.state)
        assertEquals(0.9, verdict.confidence)
    }

    @Test
    fun atOrAboveTheFloorTheDiagnosisStands() {
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.WEAK, confidence = DiagnosisRules.PROPOSED_CONFIDENCE_FLOOR),
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.GOOD),
            ),
        )
        assertEquals(DiagnosisState.WEAK, verdict.state)
    }

    @Test
    fun theSpeedTabAnswersFromPayloadAloneInTheLedgerBands() {
        assertEquals(DiagnosisState.GOOD, rules.speed(listOf(throughput(DiagnosisState.GOOD))).state)
        assertEquals(DiagnosisState.FAIR, rules.speed(listOf(throughput(DiagnosisState.FAIR))).state)
        assertEquals(DiagnosisState.SLOW, rules.speed(listOf(throughput(DiagnosisState.SLOW))).state)
    }

    @Test
    fun aTransferThatBroughtNothingBackDoesNotBecomeASpeedClaim() {
        val verdict = rules.speed(listOf(throughput(DiagnosisState.UNKNOWN, confidence = 0.0)))
        assertEquals(DiagnosisState.UNKNOWN, verdict.state)
        assertNull(verdict.where)
        assertTrue(verdict.action!!.contains("speed"))
    }

    @Test
    fun theSpeedTabWithoutAMeasurementSaysSoRatherThanGuessing() {
        val verdict = rules.speed(emptyList())
        assertEquals(DiagnosisState.UNKNOWN, verdict.state)
        assertEquals("The transfer never started.", verdict.what)
    }

    @Test
    fun theSignalTabAnswersFromTheRadioAndStillOffersAnAction() {
        val weak = rules.signal(listOf(signal(DiagnosisState.WEAK)))
        assertEquals(DiagnosisState.WEAK, weak.state)
        assertTrue(weak.where!!.startsWith("Your distance from the tower"))
        assertTrue(weak.action!!.contains("window"))

        val strong = rules.signal(listOf(signal(DiagnosisState.GOOD)))
        assertEquals(DiagnosisState.GOOD, strong.state)
    }

    @Test
    fun aRadioThatReportedNothingIsUnknownWithItsReasonVisible() {
        val verdict = rules.signal(listOf(finding(Subjects.SIGNAL, DiagnosisState.UNKNOWN, confidence = 0.0, evidence = "only 2 of 60 samples carried an RSRP")))
        assertEquals(DiagnosisState.UNKNOWN, verdict.state)
        assertTrue(verdict.evidence.contains("only 2 of 60 samples carried an RSRP"))
    }

    @Test
    fun everyDiagnosisNamesACauseAnActionAndEvidence() {
        val verdicts = listOf(
            rules.checkup(listOf(localLink(DiagnosisState.OFFLINE), internet(DiagnosisState.OFFLINE))),
            rules.checkup(listOf(localLink(DiagnosisState.GOOD), internet(DiagnosisState.GOOD), throughput(DiagnosisState.SLOW))),
            rules.checkup(listOf(signal(DiagnosisState.GOOD), localLink(DiagnosisState.GOOD), internet(DiagnosisState.GOOD), throughput(DiagnosisState.GOOD))),
            rules.speed(listOf(throughput(DiagnosisState.SLOW))),
            rules.signal(listOf(signal(DiagnosisState.WEAK))),
        )
        for (verdict in verdicts) {
            assertTrue(verdict.state.isDiagnosis, "${verdict.state} was not expected to be a diagnosis")
            assertTrue(!verdict.where.isNullOrBlank(), "${verdict.state} named no cause")
            assertTrue(!verdict.action.isNullOrBlank(), "${verdict.state} offered no action")
            assertTrue(verdict.evidence.isNotEmpty(), "${verdict.state} cited no evidence")
        }
    }
}

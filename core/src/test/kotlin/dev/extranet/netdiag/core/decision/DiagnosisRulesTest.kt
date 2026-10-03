package dev.extranet.netdiag.core.decision

import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.verdict.FindingCause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** A hop finding that also carries the round trip it measured, as the inference layer does. */
    private fun hop(subject: String, latencyMillis: Long): Finding = Finding(
        subject = subject,
        assessment = DiagnosisState.GOOD,
        confidence = 0.85,
        evidence = listOf("$subject answered in $latencyMillis ms"),
        latencyMillis = latencyMillis,
    )

    private fun mobileData(cause: FindingCause, assessment: DiagnosisState = DiagnosisState.OFFLINE): Finding =
        Finding(
            subject = Subjects.MOBILE_DATA,
            assessment = assessment,
            confidence = 0.9,
            evidence = listOf("the carrier has switched mobile data off on this SIM"),
            cause = cause,
        )

    private fun onWifi(signal: DiagnosisState = DiagnosisState.GOOD): Finding =
        Finding(Subjects.SIGNAL, signal, 0.9, listOf("median -48 dBm over 30 samples on WIFI"))

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
        assertTrue(verdict.where!!.contains("not tested"))
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
    fun aFailedReachabilityProbeDoesNotOverrideAWorkingPayloadTransfer() {
        val verdict = rules.checkup(
            listOf(
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.OFFLINE),
                throughput(DiagnosisState.SLOW),
            ),
        )
        assertEquals(DiagnosisState.FAIR, verdict.state)
        // The fault belongs to the one destination that stayed silent. The transfer is the
        // evidence that the line itself carried a megabyte, so the ISP must not be named as
        // the place to look: that would send someone to their router over one dead host.
        assertTrue(verdict.where!!.contains("destination"), "got: ${verdict.where}")
        assertFalse(verdict.where.contains("ISP"), "got: ${verdict.where}")
        assertTrue(verdict.action!!.contains("may be unavailable"), "got: ${verdict.action}")
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
    fun aCarrierThatSwitchedDataOffOutranksEveryReachabilityAnswer() {
        // A carrier restriction should be diagnosed before failures that might otherwise be
        // blamed on the network or the user's spot, and
        // every one of those instructions would waste their afternoon.
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.GOOD),
                mobileData(FindingCause.CARRIER_BLOCKED),
                localLink(DiagnosisState.OFFLINE),
                internet(DiagnosisState.OFFLINE),
            ),
        )
        assertEquals(DiagnosisState.OFFLINE, verdict.state)
        assertTrue(verdict.what.contains("carrier has blocked mobile data"))
        assertTrue(
            verdict.action!!.contains("balance") || verdict.action!!.contains("top up"),
            "the instruction must be about the allowance, not the router: ${verdict.action}",
        )
        assertEquals("Your mobile carrier or account", verdict.where)
    }

    @Test
    fun theThreeThingsThatCanStopMobileDataAreToldApart() {
        val byUser = rules.checkup(
            listOf(signal(DiagnosisState.GOOD), mobileData(FindingCause.USER_DISABLED), internet(DiagnosisState.OFFLINE)),
        )
        assertTrue(byUser.what.contains("switched off on this phone"), "got: ${byUser.what}")
        assertTrue(byUser.action!!.contains("Turn mobile data on"), "got: ${byUser.action}")

        val byPolicy = rules.checkup(
            listOf(signal(DiagnosisState.GOOD), mobileData(FindingCause.POLICY_BLOCKED), internet(DiagnosisState.OFFLINE)),
        )
        assertTrue(byPolicy.what.contains("policy"), "got: ${byPolicy.what}")
        assertTrue(byPolicy.action!!.contains("administrator"), "got: ${byPolicy.action}")

        // A carrier block is the one that gets mistaken for an outage, and the one the phone
        // cannot see inside: it must point at the bundle without claiming the bundle is spent.
        val byCarrier = rules.checkup(
            listOf(signal(DiagnosisState.GOOD), mobileData(FindingCause.CARRIER_BLOCKED), internet(DiagnosisState.OFFLINE)),
        )
        assertTrue(byCarrier.what.contains("carrier"), "got: ${byCarrier.what}")
        assertTrue(byCarrier.what.contains("can't confirm"), "got: ${byCarrier.what}")
        assertTrue(byCarrier.action!!.contains("balance"), "got: ${byCarrier.action}")
    }

    @Test
    fun mobileDataThatIsSwitchedOffIsNotBlameWhileThePhoneIsOnWifi() {
        // On Wi-Fi the allowance has nothing to do with the connection being tested, so the
        // normal reachability answer must survive.
        val verdict = rules.checkup(
            listOf(
                onWifi(),
                mobileData(FindingCause.CARRIER_BLOCKED),
                localLink(DiagnosisState.GOOD),
                internet(DiagnosisState.OFFLINE),
            ),
        )
        assertEquals("Past the Wi-Fi router: the broadband line or the upstream network", verdict.where)
    }

    @Test
    fun aSlowWayOutToTheInternetIsCalledCongestionNotAFault() {
        val verdict = rules.checkup(
            listOf(
                onWifi(),
                hop(Subjects.FIRST_HOP, 3L),
                hop(Subjects.INTERNET_HOP, 780L),
                throughput(DiagnosisState.SLOW),
            ),
        )
        assertEquals(DiagnosisState.SLOW, verdict.state)
        assertTrue(verdict.what.contains("busy"), "got: ${verdict.what}")
        assertTrue(verdict.where!!.contains("not your phone"))
        assertTrue(
            verdict.evidence.contains("the local link answered in 3 ms, so the delay is not on your side"),
            "congestion is a claim about the split between the hops, so both numbers must be shown",
        )
    }

    @Test
    fun aFarButQuickInternetHostIsNotCalledCongestion() {
        val verdict = rules.checkup(
            listOf(
                onWifi(),
                hop(Subjects.FIRST_HOP, 3L),
                hop(Subjects.INTERNET_HOP, 90L),
                throughput(DiagnosisState.SLOW),
            ),
        )
        assertEquals("The network between your phone and the internet", verdict.where)
        assertTrue(!verdict.what.contains("busy"))
    }

    @Test
    fun slowDataOnAWeakRadioIsNotCalledCongestion() {
        // Both produce slow data, and only one of them is worth taking to the ISP's door.
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.WEAK),
                hop(Subjects.FIRST_HOP, 3L),
                hop(Subjects.INTERNET_HOP, 900L),
                throughput(DiagnosisState.SLOW),
            ),
        )
        assertTrue(!verdict.what.contains("busy"), "got: ${verdict.what}")
        assertEquals("The network between your phone and the internet", verdict.where)
    }

    @Test
    fun onCellularTheLocalHopIsTheCarriersOwnSoOnlyTheAbsoluteFloorApplies() {
        // Over cellular the first hop is the carrier's core, so comparing it with the internet
        // hop would compare the carrier with itself. A slow absolute time still counts.
        val verdict = rules.checkup(
            listOf(
                signal(DiagnosisState.GOOD),
                hop(Subjects.FIRST_HOP, 600L),
                hop(Subjects.INTERNET_HOP, 640L),
                throughput(DiagnosisState.SLOW),
            ),
        )
        assertTrue(verdict.what.contains("busy"), "got: ${verdict.what}")
        assertTrue(verdict.action!!.contains("carrier's side"))
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

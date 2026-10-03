package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.decision.Subjects
import dev.extranet.netdiag.core.verdict.DiagnosisState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what inference claims, and what it refuses to claim.
 *
 * The refusals matter more than the claims: an empty sample, a hop nobody could address and a
 * transfer that brought nothing back must all come out Unknown, because those are the cases
 * where a friendly-looking "Good" would be a lie the decision rules could not detect.
 */
class ObservationsTest {

    private fun sample(rsrpDbm: Int?): RadioSample = RadioSample(
        epochMillis = 0L,
        networkType = "LTE",
        rsrpDbm = rsrpDbm,
        rsrqDb = null,
        rssnrDb = null,
        rssiDbm = null,
        timingAdvance = null,
        level = null,
        headingDegrees = null,
        networkEvent = null,
    )

    private fun samples(rsrpDbm: Int?, count: Int): List<RadioSample> = List(count) { sample(rsrpDbm) }

    private fun hop(
        address: String,
        reachable: Boolean,
        latencyMillis: Long?,
        answeredEcho: Boolean = false,
    ): TwoHopProbe.HopResult = TwoHopProbe.HopResult(address, reachable, latencyMillis, answeredEcho)

    @Test
    fun aWeakMedianIsWeakAndTheEvidenceCarriesTheNumber() {
        val finding = Observations.signal(samples(-118, 60))
        assertEquals(DiagnosisState.WEAK, finding.assessment)
        assertEquals(0.9, finding.confidence)
        assertTrue(finding.evidence.first().contains("median -118 dBm over 60 samples on LTE"))
    }

    @Test
    fun aWifiSessionIsGradedOnTheWifiScaleNotTheLteScale() {
        // -55 dBm is a superb Wi-Fi link but an impossible LTE RSRP; -85 is a Wi-Fi network
        // at the edge of usable and an ordinary LTE cell. One scale cannot grade both.
        val good = Observations.signal(samples(-55, 10).map { it.copy(networkType = "WIFI") })
        assertEquals(DiagnosisState.GOOD, good.assessment, "-55 dBm Wi-Fi must be Good")
        assertTrue(good.evidence.first().endsWith("on WIFI"))
        val weak = Observations.signal(samples(-85, 10).map { it.copy(networkType = "WIFI") })
        assertEquals(DiagnosisState.WEAK, weak.assessment, "-85 dBm Wi-Fi must be Weak")
    }

    @Test
    fun aSessionWithoutATypeStillGradesButKeepsTheNeutralEvidence() {
        // -105 dBm separates the scales: FAIR on the cellular thresholds, WEAK on the Wi-Fi
        // ones. The cellular reading is the conservative default when nothing says otherwise.
        val untyped = samples(-105, 10).map { it.copy(networkType = null) }
        val finding = Observations.signal(untyped)
        assertEquals(DiagnosisState.FAIR, finding.assessment)
        assertTrue(finding.evidence.first().endsWith("on null"))
    }

    @Test
    fun theMedianResistsADipRatherThanFollowingTheWorstSample() {
        // Fifty-nine good seconds and two behind a wall is still a good link: the median is
        // chosen over the worst sample precisely so a moment at a doorway is not a diagnosis.
        val dip = List(59) { sample(-85) } + listOf(sample(-125), sample(-125))
        assertEquals(DiagnosisState.GOOD, Observations.signal(dip).assessment)

        // The same dip, but where most of the session was weak, stays weak.
        val mostlyWeak = List(59) { sample(-118) } + List(2) { sample(-85) }
        assertEquals(DiagnosisState.WEAK, Observations.signal(mostlyWeak).assessment)
    }

    @Test
    fun theBandBoundariesAreTheThresholdsThemselves() {
        assertEquals(DiagnosisState.WEAK, Observations.signal(samples(Observations.WEAK_RSRP_DBM, 10)).assessment)
        assertEquals(DiagnosisState.FAIR, Observations.signal(samples(Observations.FAIR_RSRP_DBM, 10)).assessment)
        assertEquals(DiagnosisState.GOOD, Observations.signal(samples(Observations.FAIR_RSRP_DBM + 1, 10)).assessment)
    }

    @Test
    fun tooFewReadingsIsUnknownRatherThanAGuess() {
        val finding = Observations.signal(samples(-118, 2))
        assertEquals(DiagnosisState.UNKNOWN, finding.assessment)
        assertEquals(0.0, finding.confidence)
        assertTrue(finding.evidence.first().contains("2 of 2"))
    }

    @Test
    fun aTimelineThatNeverCarriedAnRsrpIsUnknown() {
        val finding = Observations.signal(List(20) { sample(null) })
        assertEquals(DiagnosisState.UNKNOWN, finding.assessment)
        assertTrue(finding.evidence.first().contains("0 of 20"))
    }

    @Test
    fun bothHopsAnsweringIsGoodAndNamesTheirLatency() {
        val findings = Observations.hops(
            TwoHopProbe.Verdict(
                gateway = hop("10.0.0.1", reachable = true, latencyMillis = 8L),
                internet = hop("1.1.1.1", reachable = true, latencyMillis = 42L),
                gatewayMedianMillis = 8L,
                internetMedianMillis = 42L,
            ),
        )
        assertEquals(listOf(Subjects.FIRST_HOP, Subjects.INTERNET_HOP), findings.map { it.subject })
        assertEquals(listOf(DiagnosisState.GOOD, DiagnosisState.GOOD), findings.map { it.assessment })
        assertTrue(
            findings.first().evidence.first().contains("gateway 10.0.0.1 answered in 8 ms"),
            "the first hop's evidence must name the gateway and the round trip it took",
        )
    }

    @Test
    fun aGatewayThatOnlyAnswersAnEchoIsGoodAndQuotesNoTimeItNeverMeasured() {
        // The cellular case: nothing is listening on port 80, the router answers the echo
        // request instead. The hop is alive and is reported Good - not Unknown - but no round
        // trip was timed, so no figure is printed. "Answered in 0 ms" would be a number the
        // congestion rule then compares against a real one.
        val findings = Observations.hops(
            TwoHopProbe.Verdict(
                gateway = hop("10.241.101.1", reachable = true, latencyMillis = null, answeredEcho = true),
                internet = hop("1.1.1.1", reachable = true, latencyMillis = 42L),
                gatewayMedianMillis = null,
                internetMedianMillis = 42L,
            ),
            onCellular = true,
        )
        val gateway = findings.first()
        assertEquals(DiagnosisState.GOOD, gateway.assessment, "a gateway that answered is a healthy hop")
        assertNull(gateway.latencyMillis, "an echo answer times nothing, so no latency is claimed")
        assertEquals("gateway 10.241.101.1 answered an echo request", gateway.evidence.first())
    }

    @Test
    fun aSilentInternetHopIsOfflineWhileTheLocalLinkStaysGood() {
        val findings = Observations.hops(
            TwoHopProbe.Verdict(
                gateway = hop("10.0.0.1", reachable = true, latencyMillis = 8L),
                internet = hop("1.1.1.1", reachable = false, latencyMillis = null),
                gatewayMedianMillis = 8L,
                internetMedianMillis = null,
            ),
        )
        assertEquals(DiagnosisState.GOOD, findings[0].assessment)
        assertEquals(DiagnosisState.OFFLINE, findings[1].assessment)
        assertEquals(Observations.UNREACHABLE_HOP_CONFIDENCE, findings[1].confidence)
    }

    @Test
    fun aHopNobodyCouldAddressIsUntestedNotDead() {
        val findings = Observations.hops(
            TwoHopProbe.Verdict(
                gateway = hop(TwoHopProbe.UNKNOWN_GATEWAY_ADDRESS, reachable = false, latencyMillis = null),
                internet = hop("1.1.1.1", reachable = true, latencyMillis = 40L),
                gatewayMedianMillis = null,
                internetMedianMillis = 40L,
            ),
        )
        assertEquals(DiagnosisState.UNKNOWN, findings[0].assessment)
        assertEquals(0.0, findings[0].confidence)
        assertEquals(DiagnosisState.GOOD, findings[1].assessment)
    }

    @Test
    fun aTransferThatBroughtNothingBackMakesNoSpeedClaim() {
        val finding = Observations.throughput(
            MiniThroughputProbe.Result(
                bytesMoved = 0L,
                transferMillis = 20_000L,
                bytesPerSecond = 0.0,
                verdict = "POOR",
                pingMedianMillis = null,
                detail = "SocketTimeoutException: read timed out",
            ),
        )
        assertEquals(DiagnosisState.UNKNOWN, finding.assessment)
        assertEquals(0.0, finding.confidence)
        assertTrue(finding.evidence.first().contains("read timed out"))
    }

    @Test
    fun theLedgerBandsBecomeTheStatesTheRulesExpect() {
        fun result(verdict: String, bps: Double): MiniThroughputProbe.Result = MiniThroughputProbe.Result(
            bytesMoved = 1_000_000L,
            transferMillis = 8_000L,
            bytesPerSecond = bps,
            verdict = verdict,
            pingMedianMillis = 120L,
            detail = null,
        )
        assertEquals(DiagnosisState.GOOD, Observations.throughput(result("GOOD", 1_250_000.0)).assessment)
        assertEquals(DiagnosisState.FAIR, Observations.throughput(result("MARGINAL", 500_000.0)).assessment)
        assertEquals(DiagnosisState.SLOW, Observations.throughput(result("POOR", 50_000.0)).assessment)
    }

    @Test
    fun theEvidenceForASpeedRunIsThePayloadAndThePing() {
        val finding = Observations.throughput(
            MiniThroughputProbe.Result(
                bytesMoved = 1_000_000L,
                transferMillis = 8_000L,
                bytesPerSecond = 125_000.0,
                verdict = "MARGINAL",
                pingMedianMillis = 120L,
                detail = null,
            ),
        )
        assertTrue(finding.evidence.any { it.contains("moved 1000000 B in 8000 ms") })
        assertTrue(finding.evidence.any { it.contains("ping 120 ms") })
    }

    @Test
    fun aCheckupComposesTheRadioAndBothHopsAndThePayload() {
        val findings = Observations.checkup(
            samples = samples(-95, 10),
            hops = TwoHopProbe.Verdict(
                gateway = hop("10.0.0.1", reachable = true, latencyMillis = 9L),
                internet = hop("1.1.1.1", reachable = true, latencyMillis = 45L),
                gatewayMedianMillis = 9L,
                internetMedianMillis = 45L,
            ),
            throughput = MiniThroughputProbe.Result(
                bytesMoved = 1_000_000L,
                transferMillis = 1_000L,
                bytesPerSecond = 1_000_000.0,
                verdict = "MARGINAL",
                pingMedianMillis = 45L,
                detail = null,
            ),
        )
        assertEquals(
            listOf(Subjects.SIGNAL, Subjects.FIRST_HOP, Subjects.INTERNET_HOP, Subjects.THROUGHPUT),
            findings.map { it.subject },
        )
    }
}

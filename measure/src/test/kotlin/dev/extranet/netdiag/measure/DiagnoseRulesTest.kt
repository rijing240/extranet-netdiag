package dev.extranet.netdiag.measure

import dev.extranet.netdiag.measure.DiagnoseRules.AddressFacts
import dev.extranet.netdiag.measure.DiagnoseRules.Confidence
import dev.extranet.netdiag.measure.DiagnoseRules.Facts
import dev.extranet.netdiag.measure.DiagnoseRules.HttpFacts
import dev.extranet.netdiag.measure.DiagnoseRules.LinkFacts
import dev.extranet.netdiag.measure.DiagnoseRules.PerformanceFacts
import dev.extranet.netdiag.measure.DiagnoseRules.SignalFacts
import dev.extranet.netdiag.measure.DiagnoseRules.Situation
import dev.extranet.netdiag.measure.DiagnoseRules.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnoseRulesTest {

    private val cellularUp = LinkFacts(
        transport = Transport.CELLULAR,
        connected = true,
        hasInternetCapability = true,
        validated = true,
        captivePortal = false,
        airplaneMode = false,
    )

    private val wifiUp = cellularUp.copy(transport = Transport.WIFI)

    /** A phone on cellular with strong signal and a clean run: the baseline for the failure cases. */
    private fun goodCellular(
        signal: SignalFacts = SignalFacts(rsrpDbm = -85),
        http: HttpFacts = HttpFacts(attempted = true, reached = true, statusCode = 204, elapsedMillis = 320L),
        address: AddressFacts = AddressFacts(nameResolved = true, rawAddressReached = true),
        performance: PerformanceFacts = PerformanceFacts(
            latencyMedianMillis = 40,
            throughputBitsPerSecond = 20_000_000.0,
            endpointsTried = 3,
            endpointsFailed = 0,
        ),
    ) = Facts(cellularUp, signal, http, address, performance)

    @Test
    fun airplaneModeIsNoLinkAtAll() {
        val result = DiagnoseRules.classify(
            goodCellular().copy(link = cellularUp.copy(airplaneMode = true, connected = false)),
        )

        assertEquals(Situation.NO_LINK, result.situation)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun nothingConnectedIsNoLinkAtAll() {
        val result = DiagnoseRules.classify(
            Facts(link = cellularUp.copy(connected = false, transport = Transport.NONE)),
        )

        assertEquals(Situation.NO_LINK, result.situation)
    }

    @Test
    fun aWifiSignInPageIsReportedAsWifiRatherThanAsData() {
        val result = DiagnoseRules.classify(
            Facts(link = wifiUp.copy(captivePortal = true)),
        )

        assertEquals(Situation.NO_LINK, result.situation)
        assertTrue(result.headline.contains("sign in"), result.headline)
    }

    /** A carrier intercepting traffic is the shape of an exhausted plan, and says so. */
    @Test
    fun aCarrierRedirectIsCalledDataBlockedWithHighConfidence() {
        val result = DiagnoseRules.classify(
            goodCellular(
                http = HttpFacts(
                    attempted = true,
                    reached = true,
                    statusCode = 302,
                    redirectedToHost = "topup.example-carrier.example",
                    elapsedMillis = 900L,
                ),
            ),
        )

        assertEquals(Situation.DATA_BLOCKED, result.situation)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.evidence.any { it.contains("topup.example-carrier.example") })
    }

    /** Strong signal but the network refuses at once: the classic depleted-plan signature. */
    @Test
    fun strongSignalWithAnInstantRefusalIsDataBlockedWithMediumConfidence() {
        val result = DiagnoseRules.classify(
            goodCellular(
                http = HttpFacts(attempted = true, reached = false, elapsedMillis = 120L),
                address = AddressFacts(nameResolved = true, rawAddressReached = false),
                performance = PerformanceFacts(endpointsTried = 3, endpointsFailed = 1),
            ),
        )

        assertEquals(Situation.DATA_BLOCKED, result.situation)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    /** Slow failures with a strong signal point past the phone, not at it. */
    @Test
    fun strongSignalWithSlowTimeoutsIsAnUpstreamOutage() {
        val result = DiagnoseRules.classify(
            goodCellular(
                http = HttpFacts(attempted = true, reached = false, elapsedMillis = 5_800L),
                address = AddressFacts(nameResolved = false, rawAddressReached = false),
                performance = PerformanceFacts(endpointsTried = 3, endpointsFailed = 3),
            ),
        )

        assertEquals(Situation.UPSTREAM_OUTAGE, result.situation)
        assertEquals(Confidence.MEDIUM, result.confidence)
        assertTrue(result.evidence.any { it.contains("3") })
    }

    @Test
    fun namesFailingWhileAddressesWorkIsADnsProblem() {
        val result = DiagnoseRules.classify(
            goodCellular(
                http = HttpFacts(attempted = true, reached = false, elapsedMillis = 4_500L),
                address = AddressFacts(nameResolved = false, rawAddressReached = true),
                performance = PerformanceFacts(endpointsTried = 3, endpointsFailed = 0),
            ),
        )

        assertEquals(Situation.DNS_PROBLEM, result.situation)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun weakSignalIsReportedBeforeAnythingElseIsBlamed() {
        val result = DiagnoseRules.classify(
            goodCellular(
                signal = SignalFacts(rsrpDbm = -118),
                http = HttpFacts(attempted = true, reached = false, elapsedMillis = 5_500L),
            ),
        )

        assertEquals(Situation.WEAK_SIGNAL, result.situation)
    }

    @Test
    fun weakWifiIsJudgedOnItsOwnScale() {
        val result = DiagnoseRules.classify(
            Facts(link = wifiUp, signal = SignalFacts(rssiDbm = -88)),
        )

        assertEquals(Situation.WEAK_SIGNAL, result.situation)
    }

    @Test
    fun aStrongSignalWithSlowDataIsCongestion() {
        val result = DiagnoseRules.classify(
            goodCellular(
                performance = PerformanceFacts(
                    latencyMedianMillis = 480,
                    throughputBitsPerSecond = 900_000.0,
                    endpointsTried = 3,
                    endpointsFailed = 0,
                ),
            ),
        )

        assertEquals(Situation.CONGESTION, result.situation)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    /** Strong power but poor quality means a busy cell, and that is worth saying more firmly. */
    @Test
    fun strongPowerWithPoorQualityRaisesTheConfidenceOfACongestionVerdict() {
        val result = DiagnoseRules.classify(
            goodCellular(
                signal = SignalFacts(rsrpDbm = -86, rsrqDb = -19, sinrDb = 1),
                performance = PerformanceFacts(
                    latencyMedianMillis = 520,
                    throughputBitsPerSecond = 700_000.0,
                    endpointsTried = 3,
                    endpointsFailed = 0,
                ),
            ),
        )

        assertEquals(Situation.CONGESTION, result.situation)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.evidence.any { it.contains("quality") })
    }

    @Test
    fun aCleanRunIsNormal() {
        val result = DiagnoseRules.classify(goodCellular())

        assertEquals(Situation.NORMAL, result.situation)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.evidence.any { it.contains("not being redirected") || it.contains("without being redirected") })
    }

    /** A network the platform doubts, with nothing measured yet, still has to say something. */
    @Test
    fun aSuspiciousNetworkWithNoInternetCapabilityIsNoLinkAtMediumConfidence() {
        val result = DiagnoseRules.classify(
            Facts(link = cellularUp.copy(hasInternetCapability = false, validated = false)),
        )

        assertEquals(Situation.NO_LINK, result.situation)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    /** Nothing answered and nothing was confirmed either: low confidence, and honest about it. */
    @Test
    fun partialFailuresWithoutAStrongSignalGiveALowConfidenceOutage() {
        val result = DiagnoseRules.classify(
            goodCellular(
                signal = SignalFacts(),
                http = HttpFacts(attempted = true, reached = false, elapsedMillis = 3_000L),
                address = AddressFacts(nameResolved = true, rawAddressReached = false),
                performance = PerformanceFacts(endpointsTried = 3, endpointsFailed = 1),
            ),
        )

        assertEquals(Situation.UPSTREAM_OUTAGE, result.situation)
        assertEquals(Confidence.LOW, result.confidence)
    }
}
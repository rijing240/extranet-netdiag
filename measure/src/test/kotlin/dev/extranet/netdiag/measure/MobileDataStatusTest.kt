package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.decision.Subjects
import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.FindingCause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the app is allowed to conclude about a SIM's mobile data.
 *
 * The tests that matter most here are the negative ones: that no balance is ever printed, and
 * that an allowance spent is not confused with a user who switched data off. Both mistakes
 * would be invisible on a phone whose data works, and both would cost a user real time.
 */
class MobileDataStatusTest {

    private fun status(
        hasActiveSim: Boolean = true,
        carrierName: String? = "Test Mobile",
        dataEnabled: Boolean = true,
        offReason: DataOffReason = DataOffReason.NONE,
        dataState: DataLinkState = DataLinkState.CONNECTED,
        cellularActive: Boolean = true,
        cellularValidated: Boolean = true,
        roaming: Boolean = false,
        mobileBytesSinceBoot: Long? = null,
    ) = MobileDataStatus(
        hasActiveSim = hasActiveSim,
        carrierName = carrierName,
        dataEnabled = dataEnabled,
        offReason = offReason,
        dataState = dataState,
        cellularActive = cellularActive,
        cellularValidated = cellularValidated,
        roaming = roaming,
        mobileBytesSinceBoot = mobileBytesSinceBoot,
    )

    @Test
    fun aCarrierThatSwitchedDataOffIsReportedWithoutGuessingTheBundleBalance() {
        val finding = Observations.mobileData(
            status(dataEnabled = false, offReason = DataOffReason.CARRIER),
        )
        assertEquals(DiagnosisState.OFFLINE, finding.assessment)
        assertEquals(FindingCause.CARRIER_BLOCKED, finding.cause)
        assertTrue(finding.evidence.any { it.contains("carrier has switched mobile data off") })
        assertTrue(finding.evidence.any { it.contains("exact bundle amount was not available") })
    }

    @Test
    fun theFourReasonsAreKeptApartBecauseTheyNeedFourDifferentInstructions() {
        fun causeFor(reason: DataOffReason) =
            Observations.mobileData(status(dataEnabled = false, offReason = reason)).cause

        assertEquals(FindingCause.CARRIER_BLOCKED, causeFor(DataOffReason.CARRIER))
        assertEquals(FindingCause.POLICY_BLOCKED, causeFor(DataOffReason.POLICY))
        assertEquals(FindingCause.USER_DISABLED, causeFor(DataOffReason.USER))
        assertEquals(FindingCause.THERMAL_BLOCKED, causeFor(DataOffReason.THERMAL))
        assertEquals(FindingCause.BLOCKED_UNKNOWN, causeFor(DataOffReason.UNKNOWN))
    }

    @Test
    fun aTemporarilySuspendedConnectionIsNotMisdiagnosedAsAnExhaustedBundle() {
        val finding = Observations.mobileData(
            status(dataState = DataLinkState.SUSPENDED, cellularActive = false, cellularValidated = false),
        )
        assertEquals(DiagnosisState.FAIR, finding.assessment)
        assertNull(finding.cause)
        assertFalse(finding.evidence.any { it.contains("carrier has switched") })
    }

    @Test
    fun noSimMeansThereIsNoPlanToHaveRunOut() {
        val finding = Observations.mobileData(status(hasActiveSim = false))
        assertEquals(DiagnosisState.UNKNOWN, finding.assessment)
        assertNull(finding.cause)
        assertTrue(finding.evidence.single().contains("no SIM"))
    }

    @Test
    fun dataThePlatformConfirmsOverCellularIsGood() {
        val finding = Observations.mobileData(status())
        assertEquals(DiagnosisState.GOOD, finding.assessment)
        assertNull(finding.cause)
    }

    @Test
    fun anIdleCellularRadioWhileOnAnotherConnectionIsNotAFault() {
        // Mobile data on, but the phone is on Wi-Fi: there is no cellular session at all, and
        // calling that a problem would report a fault on every Wi-Fi user with a SIM.
        val finding = Observations.mobileData(
            status(cellularActive = false, cellularValidated = false, dataState = DataLinkState.DISCONNECTED),
        )
        assertEquals(DiagnosisState.FAIR, finding.assessment)
        assertTrue(finding.evidence.any { it.contains("idle") })
    }

    @Test
    fun cellularUpButUnconfirmedIsFairRatherThanGood() {
        val finding = Observations.mobileData(status(cellularValidated = false))
        assertEquals(DiagnosisState.FAIR, finding.assessment)
        assertTrue(finding.evidence.any { it.contains("could not confirm") })
    }

    @Test
    fun noFindingEverQuotesABalanceBecauseThereIsNoApiForOne() {
        val findings = listOf(
            Observations.mobileData(status()),
            Observations.mobileData(status(dataEnabled = false, offReason = DataOffReason.CARRIER)),
            Observations.mobileData(status(mobileBytesSinceBoot = 4_500_000_000L)),
        )
        for (finding in findings) {
            val text = finding.evidence.joinToString(" ").lowercase()
            for (word in listOf("left", "remaining", "balance", "allowance of", "used of")) {
                assertFalse(
                    text.contains(word),
                    "a finding must not quote a balance Android cannot read: \"$text\"",
                )
            }
        }
    }

    @Test
    fun trafficSinceBootIsContextAndNeverComparedAgainstAPlan() {
        val finding = Observations.mobileData(status(mobileBytesSinceBoot = 2_400_000_000L))
        assertTrue(finding.evidence.any { it.contains("2.4 GB") })
        assertTrue(
            finding.evidence.any { it.contains("since the phone last restarted") },
            "a bare byte count would be read as this app's usage or as a share of the plan",
        )
    }

    @Test
    fun aCarrierBlockIsRealEvenWhileTheUserHasDataSwitchedOn() {
        // A carrier restriction can apply while the user's own toggle is on. Forbidding this
        // combination would make a restricted SIM read as
        // healthy, because the reader could not report the only fact that distinguished the two.
        val status = MobileDataStatus(
            hasActiveSim = true,
            carrierName = "Example",
            dataEnabled = true,
            offReason = DataOffReason.CARRIER,
            dataState = DataLinkState.DISCONNECTED,
            cellularActive = false,
            cellularValidated = false,
            roaming = false,
            mobileBytesSinceBoot = null,
        )
        assertTrue(status.mobileDataBroken, "a carrier block is mobile data not working")
        assertTrue(status.blockedByCarrier)
    }

    @Test
    fun aUserSwitchIsNonsensicalWhileDataIsOn() {
        val result = runCatching {
            MobileDataStatus(
                hasActiveSim = true,
                carrierName = null,
                dataEnabled = true,
                offReason = DataOffReason.USER,
                dataState = DataLinkState.CONNECTED,
                cellularActive = false,
                cellularValidated = false,
                roaming = false,
                mobileBytesSinceBoot = null,
            )
        }
        assertTrue(result.isFailure, "a user switch only exists while data is off")
    }

    @Test
    fun aCheckupWithoutASimOrAPhoneKeepsTheSubjectsItAlwaysHad() {
        val findings = Observations.checkup(
            samples = emptyList(),
            hops = TwoHopProbe.Verdict(
                gateway = Hop(TwoHopProbe.UNKNOWN_GATEWAY_ADDRESS, false, null),
                internet = Hop("1.1.1.1", true, 40L),
                gatewayMedianMillis = null,
                internetMedianMillis = 40L,
            ),
            throughput = MiniThroughputProbe.Result(
                bytesMoved = 1_000L,
                transferMillis = 100L,
                bytesPerSecond = 10_000.0,
                verdict = "GOOD",
                pingMedianMillis = 40L,
                detail = null,
            ),
            mobileData = null,
        )
        assertEquals(
            listOf(Subjects.SIGNAL, Subjects.FIRST_HOP, Subjects.INTERNET_HOP, Subjects.THROUGHPUT),
            findings.map { it.subject },
            "not every device can read a SIM, and the old shape must survive that",
        )
    }

    private fun Hop(address: String, reachable: Boolean, latencyMillis: Long?) =
        TwoHopProbe.HopResult(address, reachable, latencyMillis)
}

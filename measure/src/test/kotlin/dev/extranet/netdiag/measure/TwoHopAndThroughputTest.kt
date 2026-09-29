package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two-hop attribution logic and the throughput verdict bands.
 *
 * The network-dependent paths (real sockets) are exercised by the instrumented suite; these
 * tests pin what the verdicts *mean*, which is the part a wrong guess would silently corrupt.
 */
class TwoHopAndThroughputTest {

    @Test
    fun theLedgerVerdictBandsMeanWhatTheySay() {
        // 1 MB in 20 s = 50 kB/s: POOR. In 8 s = 125 kB/s: MARGINAL. In 0.5 s: GOOD.
        assertEquals("POOR", TimelineBudget.throughputVerdict(50_000.0))
        assertEquals("MARGINAL", TimelineBudget.throughputVerdict(62_500.0))
        assertEquals("MARGINAL", TimelineBudget.throughputVerdict(500_000.0))
        assertEquals("GOOD", TimelineBudget.throughputVerdict(1_250_000.0))
        assertEquals("POOR", TimelineBudget.throughputVerdict(0.0))
    }

    @Test
    fun theTwoHopVerdictDistinguishesTheFourWorlds() {
        val up = TwoHopProbe.HopResult("10.0.0.1", reachable = true, latencyMillis = 5L)
        val down = TwoHopProbe.HopResult("gateway unknown", reachable = false, latencyMillis = null)

        val routerDead = TwoHopProbe.Verdict(down, down, null, null)
        assertTrue(routerDead.statement().startsWith("The local link itself is unreachable"))

        val carrierDead = TwoHopProbe.Verdict(up, down, 5L, null)
        assertTrue(carrierDead.statement().contains("beyond the gateway"))

        val bothUp = TwoHopProbe.Verdict(up, up.copy(address = "1.1.1.1"), 5L, 40L)
        assertTrue(bothUp.statement().contains("Both hops are up"))

        val weird = TwoHopProbe.Verdict(down, up.copy(address = "1.1.1.1"), null, 40L)
        assertTrue(weird.statement().startsWith("Unusual"))
    }

    @Test
    fun gatewayDiscoveryNeverThrowsOnASandboxedDevice() {
        // On a JVM or a locked-down device this may return null; it must not throw.
        val gateway = TwoHopProbe.discoverGatewayAddress()
        // Either an address or null is a legal answer; the contract is only "no crash".
        assertTrue(gateway == null || gateway.hostAddress != null)
    }

    @Test
    fun aFailedThroughputFetchReportsZeroRateAndADetail() {
        // The verdict logic of a failed run is exercised via the Result itself, because the
        // fetch needs a live network: a zero-byte, zero-rate result must be POOR, not GOOD.
        val failed = MiniThroughputProbe.Result(
            bytesMoved = 0L,
            transferMillis = 4_000L,
            bytesPerSecond = 0.0,
            verdict = TimelineBudget.throughputVerdict(0.0),
            pingMedianMillis = null,
            detail = "the server sent headers but no body",
        )
        assertEquals("POOR", failed.verdict)
        assertTrue(failed.statement().startsWith("Data barely moves"))
        assertEquals("the server sent headers but no body", failed.detail)
    }

    @Test
    fun throughputTimeoutIsTighterThanTheWaterfallCap() {
        // A reality check a user can sit through: the fetch must end within 20 s even when
        // the network is dead, which is why its timeout is its own ledger constant.
        assertTrue(TimelineBudget.THROUGHPUT_TIMEOUT_MILLIS <= 20_000)
        assertTrue(TimelineBudget.THROUGHPUT_PAYLOAD_BYTES == 1_000_000L)
    }

    @Test
    fun twoHopRoundsAreMultipleSoOneLostPacketIsNotADiagnosis() {
        assertTrue(TimelineBudget.TWO_HOP_ROUNDS >= 3)
    }
}

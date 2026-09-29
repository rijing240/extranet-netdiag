package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the run orchestration: how many sets run, against what, in what order, when the run stops
 * early, and what verdict it reaches.
 *
 * The gate arithmetic itself is pinned in `:core` (`MeasurementBudgetTest`); what is pinned here
 * is that the engine actually honours it, including in the cases where the honest answer is
 * "this run proved nothing".
 */
class ProbeEngineTest {

    private val targets = listOf(
        ProbeTarget(host = "a.example"),
        ProbeTarget(host = "b.example"),
    )

    private class FakeOsDiagnostics(
        private val resolver: ResolverAddress? = ResolverAddress("10.0.2.3"),
        private val report: OsDiagnostics? = null,
        private val onCollect: () -> Unit = {},
    ) : OsDiagnosticsSource {

        var collectCalls: Int = 0
            private set

        override fun resolver(): ResolverAddress? = resolver

        override fun collect(waitMillis: Long): OsDiagnostics? {
            collectCalls++
            onCollect()
            return report
        }
    }

    private fun diagnostics(): OsDiagnostics = OsDiagnostics(
        reportTimestampMillis = 1_700_000_000_000L,
        interfaceName = "wlan0",
        mtu = 1_500,
        dnsServers = listOf("10.0.2.3"),
        privateDnsActive = false,
        privateDnsServerName = null,
        transports = listOf("WIFI"),
        linkDownstreamBandwidthKbps = 86_000,
        linkUpstreamBandwidthKbps = 43_000,
        validationResult = 0,
        probesAttemptedBitmask = 23,
        probesSucceededBitmask = 23,
        additionalInfo = mapOf("networkValidationResult" to "0"),
        dataStall = null,
    )

    // --- how many sets run, and against what ------------------------------------------------

    @Test
    fun `a full clean run attempts every set and meets the exit criterion`() {
        val source = FakeProbeSetSource()
        val run = ProbeEngine(setSource = source, osDiagnosticsSource = FakeOsDiagnostics())
            .run(targets = targets, sets = 100)

        assertEquals(100, run.setsAttempted)
        assertEquals(100, run.completeSets)
        assertEquals(0, run.failedSets)
        assertEquals(0.0, run.failureRate, 0.0)
        assertFalse(run.truncated)
        assertTrue(run.meetsExitCriterion)
        assertEquals(100, source.probed.size)
    }

    @Test
    fun `targets are probed round robin so one host cannot own the whole run`() {
        val source = FakeProbeSetSource()
        ProbeEngine(setSource = source).run(targets = targets, sets = 5, collectOsDiagnostics = false)

        assertEquals(
            listOf("a.example", "b.example", "a.example", "b.example", "a.example"),
            source.probed.map { it.second.host },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), source.probed.map { it.first })
    }

    @Test
    fun `a run with failures under the ceiling still passes and one over it does not`() {
        val under = ProbeEngine(
            setSource = FakeProbeSetSource { sequence, _ ->
                if (sequence < 4) failedAt(Layer.TLS, "handshake reset") else okSamples()
            },
        ).run(targets = targets, sets = 100, collectOsDiagnostics = false)

        assertEquals(4, under.failedSets)
        assertTrue(under.meetsExitCriterion, "4% is under the 5% ceiling")

        val over = ProbeEngine(
            setSource = FakeProbeSetSource { sequence, _ ->
                if (sequence < 6) failedAt(Layer.DNS, "servfail") else okSamples()
            },
        ).run(targets = targets, sets = 100, collectOsDiagnostics = false)

        assertEquals(6, over.failedSets)
        assertFalse(over.meetsExitCriterion, "6% is over the 5% ceiling")
    }

    @Test
    fun `the wall clock cap stops the run and the report says it was truncated`() {
        val source = FakeProbeSetSource()
        // The engine reads the monotonic clock once before the loop and once per set, so a clock
        // that advances 60,000 ms per read trips the 200,000 ms cap before the fourth set.
        var now = 0L
        val run = ProbeEngine(
            setSource = source,
            monotonicMillis = { now.also { now += 60_000L } },
        ).run(targets = targets, sets = 100, collectOsDiagnostics = false)

        assertEquals(3, run.setsAttempted)
        assertTrue(run.truncated)
        assertFalse(
            run.meetsExitCriterion,
            "a run that stopped early has not demonstrated the criterion, whatever its failure rate",
        )
        assertTrue(run.notes.any { it.contains("stopped after 3 of 100 sets") }, run.notes.toString())
        assertEquals(0.0, run.failureRate, 0.0, "the sets it did run all succeeded")
    }

    // --- the platform's own answer ----------------------------------------------------------

    @Test
    fun `platform diagnostics are collected before the sets and reported alongside them`() {
        val os = FakeOsDiagnostics(report = diagnostics())
        val run = ProbeEngine(setSource = FakeProbeSetSource(), osDiagnosticsSource = os)
            .run(targets = targets, sets = 10)

        assertEquals(1, os.collectCalls)
        assertNotNull(run.osDiagnostics)
        assertEquals("wlan0", run.osDiagnostics?.interfaceName)
        assertNull(run.osDiagnosticsUnavailableReason)
    }

    @Test
    fun `a platform that stays silent is reported as silent rather than as empty`() {
        val run = ProbeEngine(
            setSource = FakeProbeSetSource(),
            osDiagnosticsSource = FakeOsDiagnostics(report = null),
        ).run(targets = targets, sets = 10)

        assertNull(run.osDiagnostics)
        assertTrue(
            run.osDiagnosticsUnavailableReason?.contains("no connectivity report") == true,
            run.osDiagnosticsUnavailableReason,
        )
    }

    @Test
    fun `a missing platform source is distinguished from a silent one`() {
        val run = ProbeEngine(setSource = FakeProbeSetSource(), osDiagnosticsSource = null)
            .run(targets = targets, sets = 10)
        assertTrue(run.osDiagnosticsUnavailableReason?.contains("no platform diagnostics source") == true)
    }

    @Test
    fun `the platform wait is not charged against the wall clock cap`() {
        // The cap governs the network work, not the time the platform takes to answer. A wait
        // that counted would leave no room for a single set; this run must still finish all 100.
        var now = 0L
        val run = ProbeEngine(
            setSource = FakeProbeSetSource(),
            osDiagnosticsSource = FakeOsDiagnostics(onCollect = { now += 300_000L }),
            monotonicMillis = { now },
        ).run(targets = targets, sets = 100)

        assertEquals(100, run.setsAttempted)
        assertFalse(run.truncated)
        assertTrue(run.meetsExitCriterion)
    }

    // --- environment gaps -------------------------------------------------------------------

    @Test
    fun `an environment with no resolver still runs and says why the DNS rows are empty`() {
        val source = FakeProbeSetSource()
        val run = ProbeEngine(
            setSource = source,
            osDiagnosticsSource = FakeOsDiagnostics(resolver = null),
        ).run(targets = targets, sets = 3)

        assertNull(run.resolver)
        assertTrue(source.resolvers.all { it == null }, "the gap must reach the source, not be papered over")
        assertTrue(run.notes.any { it.contains("no resolver") }, run.notes.toString())
    }

    @Test
    fun `the resolver the platform offers reaches every set`() {
        val source = FakeProbeSetSource()
        val resolver = ResolverAddress("1.1.1.1")
        ProbeEngine(setSource = source, osDiagnosticsSource = FakeOsDiagnostics(resolver = resolver))
            .run(targets = targets, sets = 4)

        assertTrue(source.resolvers.all { it == resolver })
    }

    @Test
    fun `a run reports which targets it probed`() {
        val run = ProbeEngine(setSource = FakeProbeSetSource(), osDiagnosticsSource = null)
            .run(targets = targets, sets = 4)
        assertEquals(listOf("a.example", "b.example"), run.targets.map { it.host })
    }

    @Test
    fun `a run that produced no sets reports the worst failure rate`() {
        val run = ProbeRun(
            startedAtEpochMillis = 0L,
            finishedAtEpochMillis = 0L,
            setsRequested = 100,
            sets = emptyList(),
            truncated = true,
            resolver = null,
            osDiagnostics = null,
            osDiagnosticsUnavailableReason = "nothing ran",
            notes = emptyList(),
        )
        assertEquals(1.0, run.failureRate, 0.0)
        assertFalse(run.meetsExitCriterion)
        assertNull(run.dominantLayer)
    }

    @Test
    fun `a run needs at least one target and at least one set`() {
        val engine = ProbeEngine(setSource = FakeProbeSetSource())
        val noTargets = runCatching { engine.run(targets = emptyList(), sets = 1) }
        val noSets = runCatching { engine.run(targets = targets, sets = 0) }
        assertTrue(noTargets.exceptionOrNull() is IllegalArgumentException)
        assertTrue(noSets.exceptionOrNull() is IllegalArgumentException)
    }
}

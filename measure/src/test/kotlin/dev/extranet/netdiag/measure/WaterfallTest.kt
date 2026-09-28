package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the waterfall: every stage is always a row, and the counts say what actually happened. */
class WaterfallTest {

    private val target = ProbeTarget(host = "example.com")

    private fun setOf(sequence: Int, samples: List<LayerSample>): ProbeSet =
        ProbeSet(sequence = sequence, target = target, startedAtEpochMillis = 0L, samples = samples)

    @Test
    fun `an empty run still reports one row per stage rather than nothing`() {
        val rows = Waterfall.build(emptyList())
        assertEquals(listOf(Layer.DNS, Layer.TCP, Layer.TLS, Layer.TTFB), rows.map { it.layer })
        assertTrue(rows.all { it.observed == 0 && it.failures == 0 && it.skipped == 0 && it.stats == null })
    }

    @Test
    fun `every stage is aggregated independently across the run`() {
        val sets = listOf(
            setOf(0, okSamples(dnsMillis = 10, tcpMillis = 20, tlsMillis = 30, ttfbMillis = 40)),
            setOf(1, okSamples(dnsMillis = 20, tcpMillis = 40, tlsMillis = 60, ttfbMillis = 80)),
        )
        val rows = Waterfall.build(sets).associateBy { it.layer }

        assertEquals(2, rows.getValue(Layer.DNS).observed)
        assertEquals(10L, rows.getValue(Layer.DNS).stats?.minMillis)
        assertEquals(20L, rows.getValue(Layer.DNS).stats?.maxMillis)
        assertEquals(15L, rows.getValue(Layer.DNS).stats?.p50Millis, "nearest rank: the lower of two")
        assertEquals(80L, rows.getValue(Layer.TTFB).stats?.maxMillis)
    }

    @Test
    fun `a failure is counted against the stage that failed and the rest are skipped`() {
        val sets = listOf(
            setOf(0, okSamples()),
            setOf(1, failedAt(Layer.TLS, "handshake reset")),
        )
        val rows = Waterfall.build(sets).associateBy { it.layer }

        assertEquals(1, rows.getValue(Layer.TLS).failures)
        assertEquals(1, rows.getValue(Layer.TLS).observed, "the one set that got through is still counted")
        assertEquals(1, rows.getValue(Layer.TTFB).skipped, "the stage after a failure was never attempted")
        assertEquals(0, rows.getValue(Layer.TTFB).failures, "so it is not blamed for it")
        assertEquals(0, rows.getValue(Layer.DNS).failures)
    }

    @Test
    fun `the dominant stage is the one carrying the most median latency`() {
        val sets = listOf(
            setOf(0, okSamples(dnsMillis = 200, tcpMillis = 10, tlsMillis = 20, ttfbMillis = 30)),
            setOf(1, okSamples(dnsMillis = 210, tcpMillis = 10, tlsMillis = 20, ttfbMillis = 30)),
        )
        assertEquals(Layer.DNS, Waterfall.dominant(Waterfall.build(sets)))
    }

    @Test
    fun `nothing measured means no dominant stage rather than an arbitrary one`() {
        assertNull(Waterfall.dominant(Waterfall.build(emptyList())))
    }

    @Test
    fun `failing stages are listed worst first`() {
        val sets = listOf(
            setOf(0, failedAt(Layer.DNS, "servfail")),
            setOf(1, failedAt(Layer.DNS, "servfail")),
            setOf(2, failedAt(Layer.TLS, "handshake reset")),
            setOf(3, okSamples()),
        )
        assertEquals(listOf(Layer.DNS, Layer.TLS), Waterfall.failing(Waterfall.build(sets)))
    }

    @Test
    fun `a stage that was only ever skipped has no statistics`() {
        val sets = listOf(setOf(0, failedAt(Layer.DNS, "no resolver")))
        val dns = Waterfall.build(sets).first { it.layer == Layer.DNS }
        assertEquals(1, dns.failures)
        assertEquals(0, dns.observed)
        assertNull(dns.stats)
    }
}

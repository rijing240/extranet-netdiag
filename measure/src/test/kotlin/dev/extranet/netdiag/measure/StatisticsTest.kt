package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the percentile arithmetic.
 *
 * These numbers decide where a fault is, so the rule is nearest rank: every value reported is a
 * value that was actually observed. An interpolated p95 would be a millisecond that no probe ever
 * produced, and the first time it mattered would be in an argument with an operator about whose
 * network is slow.
 */
class StatisticsTest {

    @Test
    fun `nothing to summarise is null rather than a zero`() {
        assertNull(LatencyStats.of(emptyList()))
    }

    @Test
    fun `one sample is its own minimum, median and maximum`() {
        val stats = LatencyStats.of(listOf(42L))
        assertEquals(1, stats?.count)
        assertEquals(42L, stats?.minMillis)
        assertEquals(42L, stats?.p50Millis)
        assertEquals(42L, stats?.p95Millis)
        assertEquals(42L, stats?.maxMillis)
    }

    @Test
    fun `nearest rank picks an observed value rather than interpolating one`() {
        val sorted = listOf(10L, 20L, 30L, 40L)
        // p50 of four samples is the second: the lower of the two middle values, not their mean.
        assertEquals(20L, LatencyStats.percentile(sorted, 0.50))
        assertEquals(40L, LatencyStats.percentile(sorted, 0.95))
        assertEquals(10L, LatencyStats.percentile(sorted, 0.0))
        assertEquals(40L, LatencyStats.percentile(sorted, 1.0))
    }

    @Test
    fun `the rank never runs off either end of the series`() {
        val sorted = listOf(1L, 2L)
        assertEquals(2L, LatencyStats.percentile(sorted, 0.99))
        assertEquals(1L, LatencyStats.percentile(sorted, 0.01))
    }

    @Test
    fun `a slow tail moves p95 while the median stays where most users live`() {
        // Ninety sets at 10 ms and ten at a second: exactly the shape the plan's prediction model
        // exists to catch, and the reason a median alone is not a network report.
        val samples = List(90) { 10L } + List(10) { 1_000L }
        val stats = LatencyStats.of(samples)

        assertEquals(100, stats?.count)
        assertEquals(10L, stats?.p50Millis, "nine sets in ten were fast, so the median is a fast one")
        assertEquals(1_000L, stats?.p95Millis, "and the tail appears at p95, where a user feels it")
        assertEquals(1_000L, stats?.maxMillis)
    }

    @Test
    fun `p95 over a short run is simply the slowest set`() {
        // With n below twenty, ceil(0.95n) is n, so the upper percentile is the maximum. Worth
        // pinning so nobody later reads a p95 of a five-set smoke test as a tail statistic.
        val stats = LatencyStats.of(listOf(1L, 2L, 3L, 4L, 99L))
        assertEquals(99L, stats?.p95Millis)
        assertEquals(3L, stats?.p50Millis)
    }

    @Test
    fun `the ledger's percentiles are the two the report publishes`() {
        assertEquals(0.50, MeasurementBudget.PERCENTILE_P50, 0.0)
        assertEquals(0.95, MeasurementBudget.PERCENTILE_P95, 0.0)
        val stats = LatencyStats.of(listOf(10L, 20L, 30L, 40L, 50L))
        assertEquals(LatencyStats.percentile(listOf(10L, 20L, 30L, 40L, 50L), MeasurementBudget.PERCENTILE_P50), stats?.p50Millis)
    }

    @Test
    fun `an empty series has no percentile to compute`() {
        val thrown = runCatching { LatencyStats.percentile(emptyList(), 0.5) }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a fraction outside zero to one is rejected rather than clamped silently`() {
        val thrown = runCatching { LatencyStats.percentile(listOf(1L), 1.5) }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }
}

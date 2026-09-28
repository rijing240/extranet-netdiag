package dev.extranet.netdiag.measure

/**
 * One stage's numbers across a run.
 *
 * @property observed how many sets produced a usable duration for this stage.
 * @property failures how many sets attempted this stage and did not succeed.
 * @property skipped how many sets never attempted it, which is an environment gap.
 */
public data class LayerStats(
    public val layer: Layer,
    public val observed: Int,
    public val failures: Int,
    public val skipped: Int,
    public val stats: LatencyStats?,
)

/**
 * The latency waterfall: one row per stage, in the order the stages happen.
 *
 * Rows are always present, even for a stage nothing was measured on, because a missing row and a
 * row explaining that nothing was measured are different statements and a reader should never
 * have to guess which one they are looking at.
 */
public object Waterfall {

    /** Aggregates every stage across [sets], in [Layer.ORDER]. */
    public fun build(sets: List<ProbeSet>): List<LayerStats> = Layer.ORDER.map { layer ->
        val samples = sets.mapNotNull { it.sample(layer) }
        LayerStats(
            layer = layer,
            observed = samples.count { it.outcome.isMeasured },
            failures = samples.count { it.outcome.isFailure },
            skipped = samples.count { it.outcome is LayerOutcome.Skipped },
            stats = LatencyStats.of(
                samples.filter { it.outcome.isMeasured }.map { it.durationMillis },
            ),
        )
    }

    /**
     * The stage carrying the most median latency, or null when nothing was measured.
     *
     * This is the one-line answer to "where is the time going", which is the question the
     * waterfall exists to answer.
     */
    public fun dominant(stats: List<LayerStats>): Layer? = stats
        .mapNotNull { row -> row.stats?.let { row.layer to it.p50Millis } }
        .maxByOrNull { it.second }
        ?.first

    /** The stages that failed somewhere in this run, worst first by failure count. */
    public fun failing(stats: List<LayerStats>): List<Layer> = stats
        .filter { it.failures > 0 }
        .sortedByDescending { it.failures }
        .map { it.layer }
}

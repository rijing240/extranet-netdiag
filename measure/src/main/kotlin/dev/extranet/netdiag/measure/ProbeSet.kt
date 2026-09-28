package dev.extranet.netdiag.measure

/**
 * One pass through the waterfall against one target.
 *
 * @property sequence position of this set in its run, so a report can be read next to the run's
 *   own ordering without relying on the order of a list.
 * @property startedAtEpochMillis wall clock at the start; durations themselves come from a
 *   monotonic clock, so a clock adjustment cannot produce a negative latency.
 */
public data class ProbeSet(
    public val sequence: Int,
    public val target: ProbeTarget,
    public val startedAtEpochMillis: Long,
    public val samples: List<LayerSample>,
) {
    /** The sample for [layer], or null when the stage was never attempted. */
    public fun sample(layer: Layer): LayerSample? = samples.firstOrNull { it.layer == layer }

    /** How long [layer] took, or null when it was not measured. */
    public fun durationMillis(layer: Layer): Long? =
        sample(layer)?.takeIf { it.outcome.isMeasured }?.durationMillis

    /**
     * Sum of the stages that produced a duration.
     *
     * This is attributed time, not the set's wall clock: it deliberately leaves out the small
     * gaps between stages, so the parts always add up to something the whole is at least as
     * large as. Reporting the wall clock here would make the waterfall look like it does not
     * account for the total.
     */
    public val attributedMillis: Long
        get() = samples.filter { it.outcome.isMeasured }.sumOf { it.durationMillis }

    /** Stages that were attempted and did not succeed, in waterfall order. */
    public val failedLayers: List<Layer>
        get() = Layer.ORDER.filter { sample(it)?.outcome?.isFailure == true }

    /** True when at least one stage was attempted and did not succeed. */
    public val failed: Boolean get() = failedLayers.isNotEmpty()

    /** True when every stage of the waterfall succeeded. */
    public val complete: Boolean get() = Layer.ORDER.all { sample(it)?.outcome === LayerOutcome.Ok }

    /**
     * The first stage that failed.
     *
     * Everything after it is unattributable: if the address never resolved, the connect and
     * handshake numbers say nothing about the network, which is why the report marks later
     * stages skipped rather than failed.
     */
    public val firstFailure: Layer? get() = failedLayers.firstOrNull()
}

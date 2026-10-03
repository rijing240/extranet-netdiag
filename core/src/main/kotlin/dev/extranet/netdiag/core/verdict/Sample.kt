package dev.extranet.netdiag.core.verdict

/**
 * One timestamped reading from one collector.
 *
 * The invariant is the point: a sample carries a number only when its status is
 * [SampleStatus.OK]. A timeout, a refused permission and an API that returns nothing are all
 * explicit states with no number, because a missing reading plotted as zero would draw a flat
 * line that looks exactly like a measurement. The factories exist so a failed attempt cannot
 * accidentally be handed the value it failed to produce.
 *
 * @property collector which collector produced it, e.g. "radio", "gateway-ping", "dns".
 * @property metric what was read, e.g. "rsrpDbm", "rttMillis", "bytesPerSecond".
 * @property atEpochMillis when the reading was taken.
 * @property status whether the reading happened at all.
 * @property value the number, present exactly when [status] is [SampleStatus.OK].
 * @property detail why a non-OK sample failed, or any note worth keeping with an OK one.
 */
public data class Sample(
    public val collector: String,
    public val metric: String,
    public val atEpochMillis: Long,
    public val status: SampleStatus,
    public val value: Double? = null,
    public val detail: String? = null,
) {
    init {
        require(collector.isNotBlank()) { "a sample must name its collector" }
        require(metric.isNotBlank()) { "a sample must name the metric it read" }
        require(atEpochMillis >= 0L) { "a sample cannot be taken before the epoch" }
        require(status != SampleStatus.OK || value != null) {
            "an OK sample must carry the value it measured; a missing reading has no number"
        }
        require(status == SampleStatus.OK || value == null) {
            "a $status sample must not carry a value: missing data is explicit, never zero"
        }
    }

    public companion object {
        /** A reading that happened. */
        public fun reading(
            collector: String,
            metric: String,
            atEpochMillis: Long,
            value: Double,
            detail: String? = null,
        ): Sample = Sample(collector, metric, atEpochMillis, SampleStatus.OK, value, detail)

        /** An attempt that did not produce a reading. */
        public fun failed(
            collector: String,
            metric: String,
            atEpochMillis: Long,
            detail: String? = null,
        ): Sample = Sample(collector, metric, atEpochMillis, SampleStatus.FAILED, null, detail)

        /** A reading this platform does not expose. */
        public fun unavailable(
            collector: String,
            metric: String,
            atEpochMillis: Long,
            detail: String? = null,
        ): Sample = Sample(collector, metric, atEpochMillis, SampleStatus.UNAVAILABLE, null, detail)
    }
}

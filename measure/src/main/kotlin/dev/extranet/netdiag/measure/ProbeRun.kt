package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.json.Json
import dev.extranet.netdiag.core.ledger.MeasurementBudget

/** One distinct way a stage failed, and how many sets it failed in. */
public data class FailureMode(
    public val layer: Layer,
    public val detail: String,
    public val count: Int,
)

/**
 * The result of one run: B1's deliverable.
 *
 * The verdict fields are computed here rather than left to a reader, because the whole point of
 * the exit criterion is that it can be checked mechanically: [meetsExitCriterion] is true only
 * when a run completed the required number of sets, stayed under the failure ceiling, and was
 * not cut short by the wall clock cap. A run that stopped early has not demonstrated anything,
 * which is why [truncated] is part of the verdict rather than a footnote.
 *
 * @property resolver the resolver the sets were pointed at, or null when the environment offered
 *   none, in which case every set's DNS stage is skipped and the report says so.
 */
public data class ProbeRun(
    public val startedAtEpochMillis: Long,
    public val finishedAtEpochMillis: Long,
    public val setsRequested: Int,
    public val sets: List<ProbeSet>,
    public val truncated: Boolean,
    public val resolver: ResolverAddress?,
    public val osDiagnostics: OsDiagnostics?,
    public val osDiagnosticsUnavailableReason: String?,
    public val notes: List<String>,
) {
    /** How many sets actually ran. */
    public val setsAttempted: Int get() = sets.size

    /** Sets in which at least one stage was attempted and did not succeed. */
    public val failedSets: Int get() = sets.count { it.failed }

    /** Sets in which every stage succeeded. */
    public val completeSets: Int get() = sets.count { it.complete }

    /** Failed sets over attempted sets, 1.0 when nothing ran. */
    public val failureRate: Double get() = MeasurementBudget.failureRate(setsAttempted, failedSets)

    /** Wall clock the run occupied, milliseconds. */
    public val measuredWallClockMillis: Long get() = finishedAtEpochMillis - startedAtEpochMillis

    /** The waterfall, one row per stage in order. */
    public val waterfall: List<LayerStats> get() = Waterfall.build(sets)

    /** The stage carrying the most median latency, or null when nothing was measured. */
    public val dominantLayer: Layer? get() = Waterfall.dominant(waterfall)

    /** The distinct targets this run probed. */
    public val targets: List<ProbeTarget> get() = sets.map { it.target }.distinct()

    /** Grouped failure reasons, most frequent first. */
    public val failureModes: List<FailureMode> get() = sets
        .flatMap { set ->
            set.samples
                .filter { it.outcome.isFailure }
                .map { set.sequence to (it.layer to (it.outcome.detail ?: "failed with no detail")) }
        }
        .groupingBy { it.second }
        .eachCount()
        .map { (key, count) -> FailureMode(key.first, key.second, count) }
        .sortedWith(compareByDescending<FailureMode> { it.count }.thenBy { it.layer.ordinal })

    /** True only for a complete, unterminated run under the failure ceiling. */
    public val meetsExitCriterion: Boolean
        get() = !truncated && MeasurementBudget.meetsExitCriterion(setsAttempted, failedSets)

    /** One-line verdict for a screen, a log, or a CI summary. */
    public fun summaryLine(): String {
        val p50 = waterfall.filter { it.stats != null }
            .joinToString(", ") { "${it.layer.wireName} ${it.stats!!.p50Millis} ms" }
        return "$setsAttempted/$setsRequested sets, $failedSets failed " +
            "(${Math.round(failureRate * 10_000.0) / 100.0}%), dominant ${dominantLayer?.wireName ?: "none"}" +
            (if (p50.isEmpty()) "" else " [$p50]") +
            if (meetsExitCriterion) " - exit criterion met" else " - exit criterion NOT met"
    }

    /** Machine-readable rendering. This is the artifact a device run produces. */
    public fun toJson(): String = Json.objectOf(
        listOf(
            "schemaVersion" to Json.number(SCHEMA_VERSION),
            "kind" to Json.quote("latency-waterfall"),
            "startedAtEpochMillis" to Json.number(startedAtEpochMillis),
            "finishedAtEpochMillis" to Json.number(finishedAtEpochMillis),
            "measuredWallClockMillis" to Json.number(measuredWallClockMillis),
            "setsRequested" to Json.number(setsRequested),
            "setsAttempted" to Json.number(setsAttempted),
            "completeSets" to Json.number(completeSets),
            "failedSets" to Json.number(failedSets),
            "failureRate" to Json.number(round(failureRate)),
            "failureRateCeiling" to Json.number(MeasurementBudget.FAILURE_RATE_CEILING),
            "setsRequired" to Json.number(MeasurementBudget.SETS_PER_RUN),
            "truncated" to Json.bool(truncated),
            "meetsExitCriterion" to Json.bool(meetsExitCriterion),
            "dominantLayer" to Json.nullableString(dominantLayer?.wireName),
            "resolver" to Json.nullableString(resolver?.toString()),
            "targets" to Json.arrayOf(targets.map { Json.quote(it.label) }, 1),
            "waterfall" to Json.arrayOf(waterfall.map { waterfallJson(it, 2) }, 1),
            "failureModes" to Json.arrayOf(failureModes.map { failureModeJson(it, 2) }, 1),
            "osDiagnostics" to osDiagnosticsJson(osDiagnostics, 1),
            "osDiagnosticsUnavailableReason" to Json.nullableString(osDiagnosticsUnavailableReason),
            "notes" to Json.arrayOf(notes.map { Json.quote(it) }, 1),
        ),
        indent = 0,
    )

    private fun waterfallJson(row: LayerStats, indent: Int): String = Json.objectOf(
        listOf(
            "layer" to Json.quote(row.layer.wireName),
            "observed" to Json.number(row.observed),
            "failures" to Json.number(row.failures),
            "skipped" to Json.number(row.skipped),
            "count" to Json.number(row.stats?.count ?: 0),
            "minMillis" to Json.number(row.stats?.minMillis ?: 0L),
            "p50Millis" to Json.number(row.stats?.p50Millis ?: 0L),
            "p95Millis" to Json.number(row.stats?.p95Millis ?: 0L),
            "maxMillis" to Json.number(row.stats?.maxMillis ?: 0L),
        ),
        indent,
    )

    private fun failureModeJson(mode: FailureMode, indent: Int): String = Json.objectOf(
        listOf(
            "layer" to Json.quote(mode.layer.wireName),
            "detail" to Json.quote(mode.detail),
            "count" to Json.number(mode.count),
        ),
        indent,
    )

    private fun osDiagnosticsJson(diagnostics: OsDiagnostics?, indent: Int): String {
        if (diagnostics == null) return "null"
        val stall = diagnostics.dataStall
        return Json.objectOf(
            listOf(
                "reportTimestampMillis" to Json.number(diagnostics.reportTimestampMillis),
                "interfaceName" to Json.nullableString(diagnostics.interfaceName),
                "mtu" to Json.number(diagnostics.mtu ?: 0),
                "dnsServers" to Json.arrayOf(diagnostics.dnsServers.map { Json.quote(it) }, indent + 1),
                "privateDnsActive" to (diagnostics.privateDnsActive?.let { Json.bool(it) } ?: "null"),
                "privateDnsServerName" to Json.nullableString(diagnostics.privateDnsServerName),
                "transports" to Json.arrayOf(diagnostics.transports.map { Json.quote(it) }, indent + 1),
                "linkDownstreamBandwidthKbps" to Json.number(diagnostics.linkDownstreamBandwidthKbps ?: 0),
                "linkUpstreamBandwidthKbps" to Json.number(diagnostics.linkUpstreamBandwidthKbps ?: 0),
                "validationResult" to Json.number(diagnostics.validationResult ?: -1),
                "probesAttemptedBitmask" to Json.number(diagnostics.probesAttemptedBitmask ?: -1),
                "probesSucceededBitmask" to Json.number(diagnostics.probesSucceededBitmask ?: -1),
                "dataStallSuspected" to Json.bool(stall != null),
                "dataStallTimestampMillis" to Json.number(stall?.timestampMillis ?: 0L),
                "dataStallDetectionMethod" to Json.number(stall?.detectionMethod ?: -1),
                "additionalInfo" to Json.objectOf(
                    diagnostics.additionalInfo.entries.map { (k, v) -> k to quoteUnlessNumeric(v) },
                    indent + 1,
                ),
            ),
            indent,
        )
    }

    /** Renders bare numbers where the platform reported numbers, and quoted strings otherwise. */
    private fun quoteUnlessNumeric(value: String): String =
        if (value.toLongOrNull() != null) value else Json.quote(value)

    private fun round(value: Double): Double = kotlin.math.round(value * 10_000.0) / 10_000.0

    public companion object {
        /** Schema version of the waterfall report. */
        public const val SCHEMA_VERSION: Int = 1
    }
}

package dev.extranet.netdiag.core.report

import dev.extranet.netdiag.core.json.Json

/** Schema version of the machine-readable capability report. Bump on any breaking shape change. */
public const val CAPABILITY_REPORT_SCHEMA_VERSION: Int = 1

/**
 * B0's deliverable: a machine-readable statement of which platform APIs this handset supports.
 *
 * The report is the input to the S7 capability registry, so it is designed to be aggregated
 * across many devices: findings are keyed by stable ids, and the summary lets a fleet view
 * answer "what fraction of our users can we even read timing advance from?" without replaying
 * every raw report.
 */
public data class CapabilityReport(
    public val device: DeviceDescriptor,
    public val generatedAtEpochMillis: Long,
    public val findings: List<CapabilityFinding>,
    public val notes: List<String> = emptyList(),
) {
    /** Count of findings per status. Statuses with no findings are present with a count of 0. */
    public fun countByStatus(): Map<SupportStatus, Int> {
        val counts = findings.groupingBy { it.status }.eachCount()
        return SupportStatus.entries.associateWith { counts[it] ?: 0 }
    }

    /** Findings belonging to one system, in report order. */
    public fun findingsFor(system: SystemId): List<CapabilityFinding> =
        findings.filter { it.system == system }

    /** Ids whose status is exactly [status]. */
    public fun idsWithStatus(status: SupportStatus): List<String> =
        findings.filter { it.status == status }.map { it.id }

    /** One-line human summary, used in logs and in the debug screen header. */
    public fun summaryLine(): String {
        val counts = countByStatus()
        val supported = counts[SupportStatus.SUPPORTED] ?: 0
        val unavailable = counts[SupportStatus.UNAVAILABLE] ?: 0
        val total = findings.size
        return "$supported/$total supported, $unavailable unavailable on " +
            "${device.manufacturer} ${device.model} (API ${device.sdkInt})"
    }

    /** Machine-readable rendering. This is the artifact the device run produces. */
    public fun toJson(): String = Json.objectOf(
        listOf(
            "schemaVersion" to Json.number(CAPABILITY_REPORT_SCHEMA_VERSION),
            "generatedAtEpochMillis" to Json.number(generatedAtEpochMillis),
            "device" to device.toJson(1),
            "summary" to summaryJson(1),
            "notes" to Json.stringArray(notes),
            "findings" to Json.arrayOf(findings.map { it.toJson(2) }, 1),
        ),
        indent = 0,
    )

    private fun summaryJson(indent: Int): String {
        val counts = countByStatus()
        val entries = buildList {
            add("total" to Json.number(findings.size))
            for (status in SupportStatus.entries) {
                add(status.name to Json.number(counts[status] ?: 0))
            }
        }
        return Json.objectOf(entries, indent)
    }
}

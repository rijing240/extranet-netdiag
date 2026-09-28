package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.json.Json
import dev.extranet.netdiag.core.report.CapabilityFinding
import dev.extranet.netdiag.core.report.CapabilityReport
import dev.extranet.netdiag.core.report.SupportStatus
import dev.extranet.netdiag.core.report.SystemId

/**
 * One capability aggregated across many device reports.
 *
 * @property devicesReporting how many reports contained this probe id.
 * @property supportedCount reports where the API returned a usable value.
 * @property unavailableCount reports where the API existed but returned nothing usable.
 * @property missingCount reports where the API or hardware is not present at all.
 * @property notProbedCount reports where the harness never asked, so the devices behind them
 *   say nothing either way about support.
 */
public data class SupportMatrixRow(
    public val id: String,
    public val api: String,
    public val system: SystemId,
    public val devicesReporting: Int,
    public val supportedCount: Int,
    public val unavailableCount: Int,
    public val missingCount: Int,
    public val notProbedCount: Int,
) {
    /**
     * Devices that actually got an answer for this capability.
     *
     * Fewer than [devicesReporting] whenever the harness skipped the probe on some devices.
     */
    public val probedCount: Int
        get() = devicesReporting - notProbedCount

    /**
     * Fraction of *probed* devices on which this capability worked.
     *
     * Devices the harness never asked are left out of the denominator: a gap in the harness is
     * not a limit of the hardware, and counting it as a failure would make the matrix report
     * the size of the test run as if it were a property of the radio.
     */
    public val supportRate: Double
        get() = if (probedCount == 0) 0.0 else supportedCount.toDouble() / probedCount
}

/**
 * S7's seed: the device support matrix.
 *
 * The question this answers is the one that decides the roadmap - "what fraction of users can
 * we read timing advance from at all?" - without replaying every raw report. It is deliberately
 * a pure function over reports so it can run in the Worker as well as in tests.
 */
public object SupportMatrix {

    /** Aggregates reports into rows, preserving the order ids first appear in. */
    public fun build(reports: List<CapabilityReport>): List<SupportMatrixRow> {
        if (reports.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<CapabilityFinding>>()
        for (report in reports) {
            for (finding in report.findings) {
                grouped.getOrPut(finding.id) { mutableListOf() }.add(finding)
            }
        }

        return grouped.map { (id, findings) ->
            val first = findings.first()
            SupportMatrixRow(
                id = id,
                api = first.api,
                system = first.system,
                devicesReporting = findings.size,
                supportedCount = findings.count { it.status == SupportStatus.SUPPORTED },
                unavailableCount = findings.count { it.status == SupportStatus.UNAVAILABLE },
                missingCount = findings.count {
                    it.status == SupportStatus.BELOW_API_LEVEL || it.status == SupportStatus.FEATURE_ABSENT
                },
                notProbedCount = findings.count { it.status == SupportStatus.NOT_PROBED },
            )
        }
    }

    /** Rows whose support rate falls below [threshold], worst first. */
    public fun problemRows(
        rows: List<SupportMatrixRow>,
        threshold: Double = 0.5,
    ): List<SupportMatrixRow> =
        rows.filter { it.supportRate < threshold }.sortedBy { it.supportRate }

    /** Mean support rate across the rows belonging to one system. */
    public fun supportRateFor(system: SystemId, rows: List<SupportMatrixRow>): Double {
        val relevant = rows.filter { it.system == system }
        if (relevant.isEmpty()) return 0.0
        return relevant.sumOf { it.supportRate } / relevant.size
    }

    /**
     * Machine-readable rendering, matching the style of the capability report.
     *
     * @param deviceCount number of reports the rows were aggregated from. Passed explicitly
     *   rather than inferred, so a partial matrix cannot silently claim full coverage.
     */
    public fun toJson(deviceCount: Int, rows: List<SupportMatrixRow>): String = Json.objectOf(
        listOf(
            "schemaVersion" to Json.number(1),
            "deviceCount" to Json.number(deviceCount),
            "rows" to Json.arrayOf(rows.map { rowJson(it, 2) }, 1),
        ),
        indent = 0,
    )

    private fun rowJson(row: SupportMatrixRow, indent: Int): String = Json.objectOf(
        listOf(
            "id" to Json.quote(row.id),
            "api" to Json.quote(row.api),
            "system" to Json.quote(row.system.name),
            "devicesReporting" to Json.number(row.devicesReporting),
            "supportedCount" to Json.number(row.supportedCount),
            "unavailableCount" to Json.number(row.unavailableCount),
            "missingCount" to Json.number(row.missingCount),
            "notProbedCount" to Json.number(row.notProbedCount),
            "supportRate" to Json.number(kotlin.math.round(row.supportRate * 10_000.0) / 10_000.0),
        ),
        indent,
    )
}

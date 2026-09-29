package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import dev.extranet.netdiag.core.ledger.ByteBudget

/**
 * The session log as CSV: the black-box export a user attaches to a carrier complaint.
 *
 * One row per sampled second, the ledger's column order, and nothing else - no device
 * identifiers, no coordinates, no cell identity beyond what the sample itself already holds
 * in anonymizable numeric form. The header is written even for an empty session, because a
 * file a spreadsheet refuses to open is worse than an empty one.
 */
public object TimelineCsv {

    /** Renders the whole session, header included. */
    public fun export(samples: List<RadioSample>): String = buildString {
        append(TimelineBudget.CSV_COLUMNS.joinToString(","))
        append('\n')
        for (sample in samples) {
            appendRow(sample)
        }
    }

    /** Byte size of the export, for budget reporting before the string is built. */
    public fun approxBytes(sampleCount: Int): Long =
        (TimelineBudget.CSV_COLUMNS.sumOf { it.length } + TimelineBudget.CSV_COLUMNS.size)
            .toLong() * (sampleCount + 1)

    private fun StringBuilder.appendRow(sample: RadioSample) {
        append(sample.epochMillis)
        append(',')
        append(sample.networkType.orEmpty())
        append(',')
        append(sample.rsrpDbm.orEmpty())
        append(',')
        append(sample.rsrqDb.orEmpty())
        append(',')
        append(sample.rssnrDb.orEmpty())
        append(',')
        append(sample.rssiDbm.orEmpty())
        append(',')
        append(when {
            sample.timingAdvance == null -> ""
            sample.timingAdvanceUnknown -> ""
            else -> sample.timingAdvance.toString()
        })
        append(',')
        append(sample.taDistanceMeters?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }.orEmpty())
        append(',')
        append(sample.level.orEmpty())
        append(',')
        append(sample.headingDegrees?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }.orEmpty())
        append(',')
        append(csvEscape(sample.networkEvent))
        append('\n')
    }

    /** Quotes a field only when the CSV grammar requires it; events are plain tokens. */
    private fun csvEscape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun Int?.orEmpty(): String = this?.toString() ?: ""
}

package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the CSV export: ledger column order, empty-session header, escaping, estimate.
 */
class TimelineCsvTest {

    private fun sample(epoch: Long, event: String? = null) = RadioSample(
        epochMillis = epoch, networkType = "LTE",
        rsrpDbm = -95, rsrqDb = -10, rssnrDb = 20, rssiDbm = -85,
        timingAdvance = RadioSample.TIMING_ADVANCE_UNKNOWN, level = 4,
        headingDegrees = 123.4, networkEvent = event,
    )

    @Test
    fun theHeaderIsTheLedgerColumnOrder() {
        val lines = TimelineCsv.export(emptyList()).lines()
        assertEquals(2, lines.size) // header + trailing newline split
        assertEquals(TimelineBudget.CSV_COLUMNS.joinToString(","), lines.first())
    }

    @Test
    fun anEmptySessionStillProducesAValidHeaderOnlyFile() {
        val csv = TimelineCsv.export(emptyList())
        assertTrue(csv.endsWith("\n"))
        assertEquals(1, csv.trimEnd().lines().size)
    }

    @Test
    fun theTaSentimentBecomesAnEmptyCellNotTheSentinel() {
        val csv = TimelineCsv.export(listOf(sample(1)))
        val cells = csv.trimEnd().lines()[1].split(",")
        val taColumn = TimelineBudget.CSV_COLUMNS.indexOf("timingAdvance")
        assertEquals("", cells[taColumn], "the Int.MAX sentinel must not leak into the CSV")
        val taDistanceColumn = TimelineBudget.CSV_COLUMNS.indexOf("taDistanceMeters")
        assertEquals("", cells[taDistanceColumn])
    }

    @Test
    fun aKnownTaWritesBothRawStepsAndMeters() {
        val withTa = sample(1).copy(timingAdvance = 12)
        val cells = TimelineCsv.export(listOf(withTa)).trimEnd().lines()[1].split(",")
        val taColumn = TimelineBudget.CSV_COLUMNS.indexOf("timingAdvance")
        val metersColumn = TimelineBudget.CSV_COLUMNS.indexOf("taDistanceMeters")
        assertEquals("12", cells[taColumn])
        assertEquals("936.9", cells[metersColumn])
    }

    @Test
    fun nullFieldsAreEmptyCellsSoSpreadsheetsShowGaps() {
        val blind = RadioSample(
            epochMillis = 5, networkType = null,
            rsrpDbm = null, rsrqDb = null, rssnrDb = null, rssiDbm = null,
            timingAdvance = null, level = null, headingDegrees = null, networkEvent = null,
        )
        val cells = TimelineCsv.export(listOf(blind)).trimEnd().lines()[1].split(",")
        assertEquals(TimelineBudget.CSV_COLUMNS.size, cells.size)
        assertEquals("5", cells[0])
        assertEquals("", cells[TimelineBudget.CSV_COLUMNS.indexOf("rsrpDbm")])
        assertEquals("", cells[TimelineBudget.CSV_COLUMNS.indexOf("networkType")])
    }

    @Test
    fun anEventContainingACommaIsQuotedAndDoubled() {
        val csv = TimelineCsv.export(listOf(sample(1, event = "lost, then regained \"bolt\"")))
        val row = csv.trimEnd().lines()[1]
        assertTrue(row.contains("\"lost, then regained \"\"bolt\"\"\""))
    }

    @Test
    fun aPlainEventPassesThroughUnquoted() {
        val csv = TimelineCsv.export(listOf(sample(1, event = "wifi_lost")))
        assertTrue(csv.trimEnd().lines()[1].endsWith("wifi_lost"))
    }

    @Test
    fun oneHourOfRowsEstimatesInsideTheSessionBudget() {
        // 3600 rows at ~60 bytes/row is ~216 kB - the reason the upload compresses.
        val estimate = TimelineCsv.approxBytes(3600)
        assertTrue(estimate in 100_000L..500_000L, "estimate was $estimate")
    }
}

package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.CapabilityFinding
import dev.extranet.netdiag.core.report.CapabilityReport
import dev.extranet.netdiag.core.report.SupportStatus
import dev.extranet.netdiag.core.report.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the cross-device aggregation that turns individual reports into the S7 matrix. */
class SupportMatrixTest {

    private fun report(
        sdkInt: Int,
        deviceName: String,
        statuses: Map<String, SupportStatus>,
    ) = CapabilityReport(
        device = FakePlatformSource.device(sdkInt, device = deviceName),
        generatedAtEpochMillis = 0L,
        findings = statuses.map { (id, status) ->
            CapabilityFinding(
                id = id,
                api = "android.example.$id",
                system = SystemId.SENSOR_CORE,
                requiredApiLevel = 29,
                status = status,
            )
        },
    )

    private val reports = listOf(
        report(
            34, "alpha",
            mapOf(
                "lte.signal.timingAdvance" to SupportStatus.SUPPORTED,
                "nr.signal.timingAdvance" to SupportStatus.UNAVAILABLE,
                "wifi.rtt.ranging" to SupportStatus.FEATURE_ABSENT,
            ),
        ),
        report(
            34, "beta",
            mapOf(
                "lte.signal.timingAdvance" to SupportStatus.SUPPORTED,
                "nr.signal.timingAdvance" to SupportStatus.UNAVAILABLE,
                "wifi.rtt.ranging" to SupportStatus.SUPPORTED,
            ),
        ),
        report(
            26, "gamma",
            mapOf(
                "lte.signal.timingAdvance" to SupportStatus.BELOW_API_LEVEL,
                "nr.signal.timingAdvance" to SupportStatus.BELOW_API_LEVEL,
                "wifi.rtt.ranging" to SupportStatus.NOT_PROBED,
            ),
        ),
    )

    @Test
    fun `no reports produces no rows`() {
        assertEquals(emptyList(), SupportMatrix.build(emptyList()))
    }

    @Test
    fun `rows are keyed by probe id in first-seen order`() {
        val rows = SupportMatrix.build(reports)
        assertEquals(
            listOf("lte.signal.timingAdvance", "nr.signal.timingAdvance", "wifi.rtt.ranging"),
            rows.map { it.id },
        )
    }

    @Test
    fun `support rates are computed per capability`() {
        val rows = SupportMatrix.build(reports).associateBy { it.id }

        val lteTa = rows.getValue("lte.signal.timingAdvance")
        assertEquals(3, lteTa.devicesReporting)
        assertEquals(2, lteTa.supportedCount)
        assertEquals(1, lteTa.missingCount)
        assertEquals(0.6667, lteTa.supportRate, 0.0001)

        val nrTa = rows.getValue("nr.signal.timingAdvance")
        assertEquals(0, nrTa.supportedCount)
        assertEquals(2, nrTa.unavailableCount)
        assertEquals(1, nrTa.missingCount)
        assertEquals(0.0, nrTa.supportRate, 0.0)
    }

    @Test
    fun `not-probed is not counted as missing because it is a harness gap not a hardware limit`() {
        val rtt = SupportMatrix.build(reports).first { it.id == "wifi.rtt.ranging" }
        assertEquals(3, rtt.devicesReporting)
        assertEquals(1, rtt.supportedCount)
        assertEquals(1, rtt.missingCount) // the FEATURE_ABSENT device
        // The third device's NOT_PROBED contributes to neither count.
        assertEquals(1, rtt.devicesReporting - rtt.supportedCount - rtt.missingCount)
    }

    @Test
    fun `problem rows are worst first and respect the threshold`() {
        val rows = SupportMatrix.build(reports)
        val problems = SupportMatrix.problemRows(rows, threshold = 0.5)
        assertEquals(listOf("nr.signal.timingAdvance"), problems.map { it.id })

        val both = SupportMatrix.problemRows(rows, threshold = 0.8)
        assertEquals(
            listOf("nr.signal.timingAdvance", "wifi.rtt.ranging", "lte.signal.timingAdvance"),
            both.map { it.id },
        )
    }

    @Test
    fun `problem rows tolerate an empty matrix`() {
        assertEquals(emptyList(), SupportMatrix.problemRows(emptyList()))
    }

    @Test
    fun `support rate for a system averages its rows`() {
        val rows = SupportMatrix.build(reports)
        assertEquals(
            (0.6667 + 0.0 + 0.3333) / 3.0,
            SupportMatrix.supportRateFor(SystemId.SENSOR_CORE, rows),
            0.001,
        )
    }

    @Test
    fun `support rate for an absent system is zero rather than an error`() {
        val rows = SupportMatrix.build(reports)
        assertEquals(0.0, SupportMatrix.supportRateFor(SystemId.COLLECTIVE, rows), 0.0)
    }

    @Test
    fun `a single device aggregates trivially`() {
        val row = SupportMatrix.build(listOf(reports[0])).first()
        assertEquals(1, row.devicesReporting)
        assertEquals(1.0, row.supportRate, 0.0)
    }

    @Test
    fun `json carries the device count and per-row counts`() {
        val rows = SupportMatrix.build(reports)
        val json = SupportMatrix.toJson(deviceCount = reports.size, rows = rows)
        assertTrue(json.contains("\"deviceCount\": 3"), json)
        assertTrue(json.contains("\"id\": \"lte.signal.timingAdvance\""), json)
        assertTrue(json.contains("\"supportedCount\": 2"), json)
        assertTrue(json.contains("\"supportRate\": 0.6667"), json)
    }

    @Test
    fun `json is valid for an empty matrix`() {
        val json = SupportMatrix.toJson(deviceCount = 0, rows = emptyList())
        assertTrue(json.contains("\"rows\": []"), json)
    }

    @Test
    fun `support rate is undefined-safe for a zero-device row`() {
        val row = SupportMatrixRow(
            id = "x",
            api = "a",
            system = SystemId.SENSOR_CORE,
            devicesReporting = 0,
            supportedCount = 0,
            unavailableCount = 0,
            missingCount = 0,
        )
        assertEquals(0.0, row.supportRate, 0.0)
    }

    @Test
    fun `the full catalog matrix reports every capability once`() {
        val source = FakePlatformSource.permissive()
        val runner = CapabilityProbeRunner(source, clock = { 0L })
        val rows = SupportMatrix.build(listOf(runner.run()))
        assertEquals(ProbeCatalog.ALL.size, rows.size)
        assertTrue(rows.all { it.devicesReporting == 1 })
    }
}

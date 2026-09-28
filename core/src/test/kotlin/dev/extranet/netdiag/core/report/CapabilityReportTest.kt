package dev.extranet.netdiag.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The report is B0's deliverable and the input to the S7 registry, so both its aggregate
 * behaviour and the shape of its serialized form are pinned here.
 */
class CapabilityReportTest {

    private val device = DeviceDescriptor(
        manufacturer = "Google",
        model = "Pixel 6",
        device = "oriole",
        sdkInt = 33,
        releaseVersion = "13",
        socManufacturer = "Google",
        socModel = "Tensor",
    )

    private fun finding(
        id: String,
        status: SupportStatus,
        observed: String? = null,
        detail: String? = null,
        system: SystemId = SystemId.SENSOR_CORE,
        api: String = "android.example.$id",
        requiredApiLevel: Int = 24,
    ) = CapabilityFinding(
        id = id,
        api = api,
        system = system,
        requiredApiLevel = requiredApiLevel,
        status = status,
        observedValue = observed,
        detail = detail,
    )

    private fun sampleReport(): CapabilityReport = CapabilityReport(
        device = device,
        generatedAtEpochMillis = 1_700_000_000_000L,
        findings = listOf(
            finding("lte.signal.rsrp", SupportStatus.SUPPORTED, observed = "-97"),
            finding(
                id = "lte.signal.timingAdvance",
                status = SupportStatus.SUPPORTED,
                observed = "16",
                api = "android.telephony.CellSignalStrengthLte#getTimingAdvance",
                requiredApiLevel = 29,
            ),
            finding(
                id = "nr.signal.timingAdvance",
                status = SupportStatus.UNAVAILABLE,
                detail = "returned UNAVAILABLE",
                api = "android.telephony.CellSignalStrengthNr#getTimingAdvance",
                requiredApiLevel = 30,
            ),
            finding(
                id = "wifi.rtt.ranging",
                status = SupportStatus.THROWS,
                detail = "IllegalArgumentException",
                system = SystemId.MEASUREMENT_ENGINE,
                api = "android.net.wifi.rtt.RangingResult#getDistanceMm",
                requiredApiLevel = 28,
            ),
            finding(
                id = "gnss.measurements",
                status = SupportStatus.NOT_PROBED,
                detail = "requires a live callback registration",
                system = SystemId.INFERENCE,
                api = "android.location.GnssMeasurementsEvent.Callback#onGnssMeasurementsReceived",
                requiredApiLevel = 24,
            ),
            finding("lte.identity.pci", SupportStatus.SUPPORTED, observed = "123"),
        ),
    )

    @Test
    fun `status counts include zeroes for statuses with no findings`() {
        val counts = sampleReport().countByStatus()
        assertEquals(SupportStatus.entries.size, counts.size)
        assertEquals(3, counts[SupportStatus.SUPPORTED])
        assertEquals(1, counts[SupportStatus.UNAVAILABLE])
        assertEquals(1, counts[SupportStatus.THROWS])
        assertEquals(1, counts[SupportStatus.NOT_PROBED])
        assertEquals(0, counts[SupportStatus.BELOW_API_LEVEL])
        assertEquals(0, counts[SupportStatus.PERMISSION_DENIED])
        assertEquals(0, counts[SupportStatus.FEATURE_ABSENT])
    }

    @Test
    fun `summary line reports the headline ratio and device`() {
        val line = sampleReport().summaryLine()
        assertTrue(line.contains("3/6 supported"), "unexpected summary: $line")
        assertTrue(line.contains("Google Pixel 6"))
        assertTrue(line.contains("API 33"))
    }

    @Test
    fun `findings can be filtered by system`() {
        val report = sampleReport()
        // rsrp, lte timing advance, nr timing advance and pci all default to SENSOR_CORE;
        // the RTT finding is MEASUREMENT_ENGINE and the GNSS finding is INFERENCE.
        assertEquals(4, report.findingsFor(SystemId.SENSOR_CORE).size)
        assertEquals(1, report.findingsFor(SystemId.MEASUREMENT_ENGINE).size)
        assertEquals(1, report.findingsFor(SystemId.INFERENCE).size)
        assertEquals(0, report.findingsFor(SystemId.COLLECTIVE).size)
    }

    @Test
    fun `ids can be selected by status`() {
        val report = sampleReport()
        assertEquals(
            listOf("lte.signal.rsrp", "lte.signal.timingAdvance", "lte.identity.pci"),
            report.idsWithStatus(SupportStatus.SUPPORTED),
        )
        assertEquals(listOf("gnss.measurements"), report.idsWithStatus(SupportStatus.NOT_PROBED))
        assertEquals(emptyList(), report.idsWithStatus(SupportStatus.FEATURE_ABSENT))
    }

    @Test
    fun `hardware family keys on soc rather than brand`() {
        assertEquals("Google:oriole", device.hardwareFamily())
        // Two different brands on the same chipset must land in the same family.
        val other = device.copy(manufacturer = "Xiaomi", device = "oriole")
        assertEquals(device.hardwareFamily(), other.hardwareFamily())
    }

    @Test
    fun `hardware family falls back to manufacturer when soc is unknown`() {
        val unknown = device.copy(socManufacturer = null)
        assertEquals("Google:oriole", unknown.hardwareFamily())
    }

    @Test
    fun `json carries the schema version and device block`() {
        val json = sampleReport().toJson()
        assertTrue(json.contains("\"schemaVersion\": $CAPABILITY_REPORT_SCHEMA_VERSION"), json)
        assertTrue(json.contains("\"sdkInt\": 33"), json)
        assertTrue(json.contains("\"hardwareFamily\": \"Google:oriole\""), json)
    }

    @Test
    fun `json records statuses verbatim so downstream parsers stay stable`() {
        val json = sampleReport().toJson()
        assertTrue(json.contains("\"status\": \"SUPPORTED\""), json)
        assertTrue(json.contains("\"status\": \"UNAVAILABLE\""), json)
        assertTrue(json.contains("\"status\": \"THROWS\""), json)
        assertTrue(json.contains("\"status\": \"NOT_PROBED\""), json)
    }

    @Test
    fun `json keeps the required api level so below-api-level results are auditable`() {
        val json = sampleReport().toJson()
        assertTrue(json.contains("\"requiredApiLevel\": 30"), json)
    }

    @Test
    fun `absent observed values serialize as null rather than an empty string`() {
        assertTrue(sampleReport().toJson().contains("\"observedValue\": null"))
    }

    @Test
    fun `empty notes serialize as an empty array`() {
        assertTrue(sampleReport().toJson().contains("\"notes\": []"))
    }

    @Test
    fun `notes serialize when present`() {
        val report = sampleReport().copy(notes = listOf("emulator run", "no radio hardware"))
        val json = report.toJson()
        assertTrue(json.contains("\"notes\": [\"emulator run\", \"no radio hardware\"]"), json)
        assertBalanced(json)
    }

    @Test
    fun `generated json is structurally balanced`() {
        assertBalanced(sampleReport().toJson())
    }

    @Test
    fun `an empty report is still valid json`() {
        val empty = CapabilityReport(device, 0L, emptyList())
        assertBalanced(empty.toJson())
        assertEquals("0/0 supported, 0 unavailable on Google Pixel 6 (API 33)", empty.summaryLine())
    }

    @Test
    fun `detail strings containing quotes and newlines do not break the document`() {
        val nasty = CapabilityReport(
            device = device,
            generatedAtEpochMillis = 1L,
            findings = listOf(
                finding(
                    id = "weird",
                    status = SupportStatus.THROWS,
                    detail = "IllegalStateException: \"boom\"\nat line 1",
                    requiredApiLevel = 29,
                ),
            ),
        )
        val json = nasty.toJson()
        assertTrue(json.contains("\\\"boom\\\""), json)
        assertTrue(json.contains("\\n"), json)
        assertBalanced(json)
    }

    /** Minimal structural check: balanced braces and brackets outside of string literals. */
    private fun assertBalanced(json: String) {
        var depthCurly = 0
        var depthSquare = 0
        var inString = false
        var escaped = false
        for (ch in json) {
            when {
                escaped -> escaped = false
                inString && ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depthCurly++
                ch == '}' -> depthCurly--
                ch == '[' -> depthSquare++
                ch == ']' -> depthSquare--
            }
            assertTrue(depthCurly >= 0 && depthSquare >= 0, "unbalanced json: $json")
        }
        assertTrue(!inString, "unterminated string in json")
        assertEquals(0, depthCurly, "unbalanced braces in: $json")
        assertEquals(0, depthSquare, "unbalanced brackets in: $json")
    }
}

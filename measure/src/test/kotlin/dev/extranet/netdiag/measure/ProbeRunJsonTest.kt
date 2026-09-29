package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the shape of the artifact a device run produces. */
class ProbeRunJsonTest {

    private val target = ProbeTarget(host = "example.com")

    private fun setOf(sequence: Int, samples: List<LayerSample>): ProbeSet =
        ProbeSet(sequence = sequence, target = target, startedAtEpochMillis = 0L, samples = samples)

    private fun run(
        sets: List<ProbeSet>,
        truncated: Boolean = false,
        osDiagnostics: OsDiagnostics? = null,
    ): ProbeRun = ProbeRun(
        startedAtEpochMillis = 1_700_000_000_000L,
        finishedAtEpochMillis = 1_700_000_012_000L,
        setsRequested = 100,
        sets = sets,
        truncated = truncated,
        resolver = ResolverAddress("10.0.2.3"),
        osDiagnostics = osDiagnostics,
        osDiagnosticsUnavailableReason = if (osDiagnostics == null) "the platform stayed silent" else null,
        notes = listOf("B1 probe", "raw coordinates and cell identities are never included in this report"),
    )

    private fun cleanRun(): ProbeRun =
        run((0 until 100).map { setOf(it, okSamples(dnsMillis = 5, tcpMillis = 10, tlsMillis = 20, ttfbMillis = 30)) })

    @Test
    fun `the report is balanced json with the verdict fields a reader needs`() {
        val json = cleanRun().toJson()

        assertTrue(json.startsWith("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        for (key in listOf(
            "\"schemaVersion\"",
            "\"kind\": \"latency-waterfall\"",
            "\"setsAttempted\"",
            "\"failureRate\"",
            "\"failureRateCeiling\"",
            "\"setsRequired\"",
            "\"truncated\"",
            "\"meetsExitCriterion\"",
            "\"dominantLayer\"",
            "\"waterfall\"",
            "\"failureModes\"",
            "\"osDiagnostics\"",
        )) {
            assertTrue(json.contains(key), "report is missing $key:\n$json")
        }

        var curly = 0
        var square = 0
        var inString = false
        var escaped = false
        for (ch in json) {
            when {
                escaped -> escaped = false
                inString && ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> curly++
                ch == '}' -> curly--
                ch == '[' -> square++
                ch == ']' -> square--
            }
        }
        assertFalse(inString, "unterminated string in the report")
        assertEquals(0, curly, "unbalanced braces in the report")
        assertEquals(0, square, "unbalanced brackets in the report")
    }

    @Test
    fun `a clean run reports every stage and a met criterion`() {
        val json = cleanRun().toJson()

        assertTrue(json.contains("\"setsAttempted\": 100"), json)
        assertTrue(json.contains("\"failureRate\": 0"), json)
        assertTrue(json.contains("\"meetsExitCriterion\": true"), json)
        assertTrue(json.contains("\"truncated\": false"), json)
        assertTrue(json.contains("\"p50Millis\": 20"), "the TLS stage carries the median in this fixture")
        assertTrue(json.contains("\"dominantLayer\": \"ttfb\""), json)
        for (layer in listOf("dns", "tcp", "tls", "ttfb")) {
            assertTrue(json.contains("\"layer\": \"$layer\""), "missing $layer row")
        }
    }

    @Test
    fun `failure reasons are grouped so a report says what went wrong and how often`() {
        val sets = (0 until 6).map { setOf(it, failedAt(Layer.TLS, "SSLHandshakeException: reset")) } +
            (0 until 94).map { setOf(it, okSamples()) }
        val json = run(sets).toJson()

        assertTrue(json.contains("\"failureModes\": ["), json)
        assertTrue(json.contains("\"detail\": \"SSLHandshakeException: reset\""), json)
        assertTrue(json.contains("\"count\": 6"), json)
        assertTrue(json.contains("\"failureRate\": 0.06"), json)
        assertTrue(json.contains("\"meetsExitCriterion\": false"), json)
    }

    @Test
    fun `a truncated run cannot claim the criterion even with no failures`() {
        val json = run((0 until 3).map { setOf(it, okSamples()) }, truncated = true).toJson()
        assertTrue(json.contains("\"truncated\": true"), json)
        assertTrue(json.contains("\"meetsExitCriterion\": false"), json)
        assertTrue(json.contains("\"setsAttempted\": 3"), json)
    }

    @Test
    fun `an absent platform report is stated rather than rendered as an empty object`() {
        val json = run(emptyList()).toJson()
        assertTrue(json.contains("\"osDiagnostics\": null"), json)
        assertTrue(json.contains("\"osDiagnosticsUnavailableReason\": \"the platform stayed silent\""), json)
    }

    @Test
    fun `the platform's own verdict is carried through with its raw counts intact`() {
        val diagnostics = OsDiagnostics(
            reportTimestampMillis = 1_700_000_000_000L,
            interfaceName = "rmnet_data0",
            mtu = 1_500,
            dnsServers = listOf("10.0.2.3"),
            privateDnsActive = true,
            privateDnsServerName = "dns.google",
            transports = listOf("CELLULAR"),
            linkDownstreamBandwidthKbps = 150_000,
            linkUpstreamBandwidthKbps = 50_000,
            validationResult = 0,
            probesAttemptedBitmask = 23,
            probesSucceededBitmask = 21,
            additionalInfo = mapOf("networkValidationResult" to "0"),
            dataStall = OsDiagnostics.DataStall(
                timestampMillis = 1_700_000_001_000L,
                detectionMethod = 1,
                details = mapOf("stallDurationMs" to "5000"),
            ),
        )
        val json = run((0 until 100).map { setOf(it, okSamples()) }, osDiagnostics = diagnostics).toJson()

        assertTrue(json.contains("\"interfaceName\": \"rmnet_data0\""), json)
        assertTrue(json.contains("\"privateDnsActive\": true"), json)
        assertTrue(json.contains("\"probesAttemptedBitmask\": 23"), json)
        assertTrue(json.contains("\"probesSucceededBitmask\": 21"), json)
        assertTrue(json.contains("\"dataStallSuspected\": true"), json)
        assertTrue(json.contains("\"dataStallDetectionMethod\": 1"), json)
        assertTrue(json.contains("\"osDiagnosticsUnavailableReason\": null"), json)
    }

    @Test
    fun `the one-line summary names the rate, the dominant stage and the verdict`() {
        val summary = cleanRun().summaryLine()
        assertTrue(summary.contains("100/100 sets"), summary)
        assertTrue(summary.contains("0 failed"), summary)
        assertTrue(summary.contains("ttfb"), summary)
        assertTrue(summary.contains("exit criterion met"), summary)

        val failed = run((0 until 100).map { setOf(it, failedAt(Layer.DNS, "servfail")) })
        assertTrue(failed.summaryLine().contains("exit criterion NOT met"), failed.summaryLine())
    }

    @Test
    fun `stage notes travel with the sample rather than being dropped`() {
        val json = run(
            listOf(
                setOf(
                    0,
                    listOf(
                        LayerSample(Layer.DNS, LayerOutcome.Ok, 5L, note = "10.0.2.3"),
                        LayerSample(Layer.TCP, LayerOutcome.Ok, 10L, note = "93.184.216.34"),
                        LayerSample(Layer.TLS, LayerOutcome.Ok, 20L, note = "negotiated TLSv1.3"),
                        LayerSample(Layer.TTFB, LayerOutcome.Ok, 30L, note = "first byte 'H'"),
                    ),
                ),
            ),
        ).toJson()

        // Notes are diagnostic detail, not part of the aggregate shape: the waterfall reports the
        // numbers and the per-set notes stay on the sets. Pin that, so nobody assumes the upload
        // carries per-set detail it does not.
        assertFalse(json.contains("negotiated TLSv1.3"), json)
    }
}

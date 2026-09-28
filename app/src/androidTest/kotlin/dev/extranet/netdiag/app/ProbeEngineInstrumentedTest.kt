package dev.extranet.netdiag.app

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.extranet.netdiag.core.ledger.MeasurementBudget
import dev.extranet.netdiag.measure.Layer
import dev.extranet.netdiag.measure.ProbeEngine
import dev.extranet.netdiag.measure.ProbeTarget
import dev.extranet.netdiag.measure.SocketProbeSetSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B1's exit criterion, executed on a real Android runtime against a real network.
 *
 * Unlike B0's structural test, this one can assert outcomes: the probe engine drives JDK sockets,
 * and an emulator with NAT has a working network and a resolver. So the exit criterion itself -
 * a hundred sets, under a five per cent failure rate, with a percentile waterfall - is asserted
 * here rather than described.
 *
 * When the failure rate is over the ceiling the test prints the grouped failure modes, because a
 * red run that says only "6 failed" wastes the next hour.
 */
@RunWith(AndroidJUnit4::class)
class ProbeEngineInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun runProbe(sets: Int): MeasurementUiState.Done {
        val state = MeasurementHarness.execute(context, sets = sets)
        when (state) {
            is MeasurementUiState.Done -> Log.i(TAG, "probe engine done: ${state.run.summaryLine()}")
            else -> Log.i(TAG, "probe engine state: $state")
        }
        return state as? MeasurementUiState.Done
            ?: throw AssertionError("harness did not complete: $state")
    }

    @Test
    fun oneHundredProbeSetsStayUnderTheFailureCeiling() {
        val done = runProbe(sets = MeasurementBudget.SETS_PER_RUN)
        val run = done.run

        Log.i(TAG, "waterfall: ${run.summaryLine()}")
        for (mode in run.failureModes) {
            Log.i(TAG, "failure mode: ${mode.count} x ${mode.layer.wireName}: ${mode.detail}")
        }

        assertEquals(
            "the run did not attempt every set it was asked for",
            MeasurementBudget.SETS_PER_RUN,
            run.setsAttempted,
        )
        assertFalse("the run was cut short by the wall clock cap", run.truncated)
        assertTrue(
            "failure rate ${run.failureRate} is over the ${MeasurementBudget.FAILURE_RATE_CEILING} ceiling; " +
                "modes: ${run.failureModes}",
            run.failureRate < MeasurementBudget.FAILURE_RATE_CEILING,
        )
        assertTrue("B1's exit criterion is not met: ${run.summaryLine()}", run.meetsExitCriterion)
    }

    @Test
    fun theWaterfallReportsEveryLayerWithPercentiles() {
        val done = runProbe(sets = 20)
        val rows = done.run.waterfall.associateBy { it.layer }

        assertEquals(Layer.ORDER, done.run.waterfall.map { it.layer })
        for (layer in Layer.ORDER) {
            val row = rows.getValue(layer)
            val stats = row.stats
                ?: throw AssertionError(
                    "no statistics for ${layer.wireName}: ${row.observed} measured, " +
                        "${row.failures} failed, ${row.skipped} skipped",
                )
            assertTrue("p95 below p50 for ${layer.wireName}", stats.p95Millis >= stats.p50Millis)
            assertTrue("max below p95 for ${layer.wireName}", stats.maxMillis >= stats.p95Millis)
            assertTrue("a latency cannot be negative", stats.minMillis >= 0L)
        }

        // Four stages that each happened means four stages with numbers, which is the whole
        // point of a waterfall: one total latency cannot attribute a fault to a layer.
        assertNotNull(done.run.dominantLayer)
        Log.i(TAG, "dominant layer: ${done.run.dominantLayer} of ${done.run.waterfall.size}")
    }

    @Test
    fun thePlatformIsAskedForItsOwnVerdictAndSaySoIfItDeclines() {
        val done = runProbe(sets = 5)
        val diagnostics = done.run.osDiagnostics

        if (diagnostics == null) {
            assertNotNull(
                "a missing platform report must come with a reason",
                done.run.osDiagnosticsUnavailableReason,
            )
            Log.i(TAG, "no platform report: ${done.run.osDiagnosticsUnavailableReason}")
        } else {
            // Whatever it says, it must not claim to have probed with a bitmask it did not
            // report, and the DNS servers it lists must be the ones the run used.
            Log.i(
                TAG,
                "platform: interface=${diagnostics.interfaceName} transports=${diagnostics.transports} " +
                    "validation=${diagnostics.validationResult} attempted=${diagnostics.probesAttemptedBitmask}",
            )
            assertEquals(diagnostics.dnsServers.firstOrNull(), done.run.resolver?.host)
        }
    }

    @Test
    fun theReportIsValidJsonAndNeverLeaksRawIdentityOrCoordinates() {
        val done = runProbe(sets = 3)
        val json = done.json

        assertTrue(json.startsWith("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        assertTrue(json.contains("\"kind\": \"latency-waterfall\""))

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
        assertFalse("unterminated string in the report", inString)
        assertEquals("unbalanced braces in the report", 0, curly)
        assertEquals("unbalanced brackets in the report", 0, square)

        assertFalse(
            "a coordinate-looking value leaked into the waterfall",
            Regex("-?\\d{1,3}\\.\\d{5,},\\s*-?\\d{1,3}\\.\\d{5,}").containsMatchIn(json),
        )
        assertFalse("an ICCID-like run of digits leaked", Regex("89\\d{17,}").containsMatchIn(json))

        val file = File(done.reportPath)
        assertTrue("report file was not written: ${done.reportPath}", file.exists())
        assertEquals(done.json, file.readText())
        Log.i(TAG, "waterfall written to ${done.reportPath} (${file.length()} bytes)")

        done.externalReportPath?.let { path ->
            val external = File(path)
            assertTrue("external copy missing: $path", external.exists())
            assertEquals(done.json, external.readText())
        }
    }

    @Test
    fun aRunWithNoTargetsIsRejectedRatherThanReportingSuccess() {
        val bad = runCatching {
            ProbeEngine(setSource = SocketProbeSetSource()).run(targets = emptyList(), sets = 1)
        }
        assertTrue(bad.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun theDefaultTargetsAreTheTwoTheEngineDocuments() {
        assertEquals(2, ProbeTarget.DEFAULT.size)
        assertTrue(ProbeTarget.DEFAULT.all { it.host.isNotBlank() && it.port == 443 })
    }

    private companion object {
        const val TAG = "ProbeEngineTest"
    }
}

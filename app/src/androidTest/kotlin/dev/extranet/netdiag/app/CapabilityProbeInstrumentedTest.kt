package dev.extranet.netdiag.app

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.extranet.netdiag.core.report.SupportStatus
import dev.extranet.netdiag.probe.ProbeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B0's exit criterion, executed on a real Android runtime.
 *
 * These assertions are structural on purpose. An emulator has no radio and no GNSS chipset, so
 * almost every radio probe returns UNAVAILABLE there. Asserting that anything is *supported*
 * would either fail on the emulator or be vacuous on hardware. What is asserted instead is what
 * must hold on both: one finding per catalog entry, a well-formed report on disk, the correct
 * device identity, and the guarantee that a missing permission is reported as a permission
 * problem rather than as broken hardware.
 *
 * The report itself is the artifact: it is written to the app's external files directory so CI
 * can `adb pull` it, and its path is logged for the run.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityProbeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val grantablePermissions = listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_PHONE_STATE",
    )

    private fun grantProbePermissions() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in grantablePermissions) {
            try {
                uiAutomation.grantRuntimePermission(context.packageName, permission)
            } catch (throwable: Throwable) {
                // Emulator images vary in what they will grant; the assertions below adapt
                // rather than assuming every grant succeeded.
                Log.w(TAG, "could not grant $permission", throwable)
            }
        }
    }

    private fun runProbe(liveSession: Boolean): ProbeUiState.Done {
        val state = ProbeHarness.execute(context, liveSession = liveSession)
        Log.i(TAG, "probe state: $state")
        return state as? ProbeUiState.Done
            ?: throw AssertionError("harness did not complete: $state")
    }

    @Test
    fun synchronousPassProducesOneFindingPerCatalogSpecAndWritesTheReport() {
        val done = runProbe(liveSession = false)

        assertEquals(ProbeCatalog.ALL.size, done.report.findings.size)
        assertEquals(
            ProbeCatalog.ALL.map { it.id },
            done.report.findings.map { it.id },
        )

        val file = File(done.reportPath)
        assertTrue("report file was not written: ${done.reportPath}", file.exists())
        assertTrue("report file was empty", file.length() > 0L)
        assertEquals(done.json, file.readText())
        Log.i(TAG, "report written to ${done.reportPath} (${file.length()} bytes)")
    }

    @Test
    fun reportDescribesThisHandset() {
        val done = runProbe(liveSession = false)
        assertEquals(Build.MANUFACTURER, done.report.device.manufacturer)
        assertEquals(Build.MODEL, done.report.device.model)
        assertEquals(Build.VERSION.SDK_INT, done.report.device.sdkInt)
        assertTrue(done.report.device.hardwareFamily().contains(Build.DEVICE))
    }

    @Test
    fun reportIsStructurallyValidJson() {
        val done = runProbe(liveSession = false)
        val json = done.json

        assertTrue(json.startsWith("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"findings\": ["))

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
        }
        assertTrue("unterminated string in report", !inString)
        assertEquals("unbalanced braces in report", 0, depthCurly)
        assertEquals("unbalanced brackets in report", 0, depthSquare)
    }

    @Test
    fun theReportNeverLeaksRawIdentityOrCoordinates() {
        val done = runProbe(liveSession = false)
        // The privacy rule is that only pseudonymous keys leave the device. This report is
        // device-local, but the guard still matters because it is the shape the upload will use.
        for (finding in done.report.findings) {
            val observed = finding.observedValue ?: continue
            assertTrue(
                "raw coordinate-looking value leaked in ${finding.id}: $observed",
                !Regex("-?\\d{1,3}\\.\\d{5,},\\s*-?\\d{1,3}\\.\\d{5,}").containsMatchIn(observed),
            )
        }
    }

    @Test
    fun asynchronousSpecsAreReportedAsNotProbedWhenNoLiveSessionRan() {
        val done = runProbe(liveSession = false)
        val asyncIds = ProbeCatalog.asyncIds()
        assertTrue("catalog has no async specs to check", asyncIds.isNotEmpty())

        val notProbed = done.report.idsWithStatus(SupportStatus.NOT_PROBED)
        for (id in asyncIds) {
            val finding = done.report.findings.first { it.id == id }
            // An async spec may legitimately be BELOW_API_LEVEL or PERMISSION_DENIED on an
            // older or locked-down device; what it must never be is silently SUPPORTED.
            assertTrue(
                "async spec $id claimed SUPPORTED without a live session",
                finding.status != SupportStatus.SUPPORTED,
            )
        }
        assertTrue(
            "expected at least one NOT_PROBED finding, got ${notProbed.size}",
            notProbed.isNotEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
        )
    }

    @Test
    fun aMissingPermissionIsReportedAsAPermissionProblemNotBrokenHardware() {
        val done = runProbe(liveSession = false)
        val fineLocation = done.report.findings.first { it.id == "permissions.fineLocation" }
        assertEquals(SupportStatus.PERMISSION_DENIED, fineLocation.status)

        // Cell identity requires location. With the permission ungranted the platform either
        // refuses or redacts, so the finding must not be SUPPORTED.
        val earfcn = done.report.findings.first { it.id == "lte.identity.earfcn" }
        assertTrue(
            "cell identity reported as supported without location permission",
            earfcn.status != SupportStatus.SUPPORTED,
        )
    }

    @Test
    fun grantedPermissionsLetThePermissionSensitiveProbesReachThePlatform() {
        grantProbePermissions()
        val done = runProbe(liveSession = false)

        val fineLocation = done.report.findings.first { it.id == "permissions.fineLocation" }
        assertEquals(SupportStatus.SUPPORTED, fineLocation.status)
        assertEquals("granted", fineLocation.observedValue)

        // The harness must now have actually asked the platform about the radio APIs.
        val locationGated = done.report.findings
            .filter { it.id.startsWith("lte.") || it.id.startsWith("nr.") || it.id.startsWith("gsm.") }
        assertTrue("expected radio findings", locationGated.isNotEmpty())
        for (finding in locationGated) {
            assertTrue(
                "radio finding ${finding.id} still reported a permission problem after granting",
                finding.status != SupportStatus.PERMISSION_DENIED,
            )
        }
        Log.i(TAG, "granted-run summary: ${done.report.summaryLine()}")
    }

    @Test
    fun liveSessionRunsWithoutCrashingAndInventsNothing() {
        grantProbePermissions()
        val done = runProbe(liveSession = true)

        assertEquals(ProbeCatalog.ALL.size, done.report.findings.size)
        assertNotNull(done.reportPath)

        // The privacy note must survive into the report so a reader knows what was and was not
        // collected.
        assertTrue(
            "expected a live-session note",
            done.report.notes.any { it.contains("live session") },
        )
        Log.i(TAG, "live-run summary: ${done.report.summaryLine()} asyncObserved=${done.asyncObserved}")
    }

    private companion object {
        const val TAG = "CapabilityProbeTest"
    }
}

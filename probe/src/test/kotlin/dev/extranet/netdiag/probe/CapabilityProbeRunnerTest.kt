package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.SupportStatus
import dev.extranet.netdiag.core.report.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the classification table. Every row here is a decision the report depends on, and the
 * guard-ordering tests exist because getting the order wrong would make a device with no
 * permissions look like a device with broken hardware.
 */
class CapabilityProbeRunnerTest {

    private fun spec(
        id: String = "x",
        api: String = "android.example.X",
        kind: ProbeKind = ProbeKind.SYNC,
        requiredApiLevel: Int = 1,
        requiredPermission: String? = null,
        requiredFeature: String? = null,
        system: SystemId = SystemId.SENSOR_CORE,
        note: String? = null,
    ) = ProbeSpec(id, api, system, kind, requiredApiLevel, requiredPermission, requiredFeature, note)

    private fun run(
        source: FakePlatformSource,
        specs: List<ProbeSpec>,
        resolvedAsync: Map<String, ProbeOutcome> = emptyMap(),
        notes: List<String> = emptyList(),
        clock: () -> Long = { 42L },
    ) = CapabilityProbeRunner(source, clock).run(specs, resolvedAsync, notes)

    // --- outcome mapping -------------------------------------------------------------------

    @Test
    fun `a value is supported and carries the rendered observation`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("ta" to ProbeOutcome.Value("16 basic units")),
        )
        val finding = run(source, listOf(spec(id = "ta"))).findings.single()
        assertEquals(SupportStatus.SUPPORTED, finding.status)
        assertEquals("16 basic units", finding.observedValue)
    }

    @Test
    fun `an unavailable sentinel is reported as unavailable`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("nr.ta" to ProbeOutcome.Unavailable),
        )
        val finding = run(source, listOf(spec(id = "nr.ta"))).findings.single()
        assertEquals(SupportStatus.UNAVAILABLE, finding.status)
        assertEquals("platform returned UNAVAILABLE", finding.detail)
    }

    @Test
    fun `an exception is reported as throws with the reason preserved`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("bad" to ProbeOutcome.Failed("IllegalStateException: no modem")),
        )
        val finding = run(source, listOf(spec(id = "bad"))).findings.single()
        assertEquals(SupportStatus.THROWS, finding.status)
        assertEquals("IllegalStateException: no modem", finding.detail)
    }

    @Test
    fun `a platform refusal is reported as permission denied`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("p" to ProbeOutcome.Denied),
        )
        assertEquals(
            SupportStatus.PERMISSION_DENIED,
            run(source, listOf(spec(id = "p"))).findings.single().status,
        )
    }

    @Test
    fun `a platform absent result is reported as feature absent`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("f" to ProbeOutcome.Absent),
        )
        assertEquals(
            SupportStatus.FEATURE_ABSENT,
            run(source, listOf(spec(id = "f"))).findings.single().status,
        )
    }

    @Test
    fun `an unknown api returns unavailable rather than aborting the report`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val report = run(source, listOf(spec(id = "never-implemented")))
        assertEquals(SupportStatus.UNAVAILABLE, report.findings.single().status)
    }

    // --- guard ordering --------------------------------------------------------------------

    @Test
    fun `below api level is decided without touching the platform`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(26))
        val finding = run(source, listOf(spec(id = "nr.ta", requiredApiLevel = 30))).findings.single()
        assertEquals(SupportStatus.BELOW_API_LEVEL, finding.status)
        assertTrue(finding.detail!!.contains("26 < required 30"), finding.detail!!)
        assertEquals(emptyList(), source.queried, "platform must not be called below the API level")
    }

    @Test
    fun `a missing permission is decided without touching the platform`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val finding = run(
            source,
            listOf(spec(id = "cells", requiredPermission = "android.permission.ACCESS_FINE_LOCATION")),
        ).findings.single()
        assertEquals(SupportStatus.PERMISSION_DENIED, finding.status)
        assertTrue(finding.detail!!.contains("ACCESS_FINE_LOCATION"), finding.detail!!)
        assertEquals(emptyList(), source.queried)
    }

    @Test
    fun `a missing feature is decided without touching the platform`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val finding = run(
            source,
            listOf(spec(id = "rtt", requiredFeature = "android.hardware.wifi.rtt")),
        ).findings.single()
        assertEquals(SupportStatus.FEATURE_ABSENT, finding.status)
        assertEquals(emptyList(), source.queried)
    }

    @Test
    fun `api level outranks permission which outranks feature`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(26))
        val finding = run(
            source,
            listOf(
                spec(
                    id = "everything-wrong",
                    requiredApiLevel = 34,
                    requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
                    requiredFeature = "android.hardware.wifi.rtt",
                ),
            ),
        ).findings.single()
        assertEquals(SupportStatus.BELOW_API_LEVEL, finding.status)

        val newer = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val finding2 = run(
            newer,
            listOf(
                spec(
                    id = "permission-and-feature",
                    requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
                    requiredFeature = "android.hardware.wifi.rtt",
                ),
            ),
        ).findings.single()
        assertEquals(SupportStatus.PERMISSION_DENIED, finding2.status)
    }

    @Test
    fun `a granted permission spec reports supported without a platform call`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            grantedPermissions = setOf("android.permission.ACCESS_FINE_LOCATION"),
        )
        val finding = run(
            source,
            listOf(
                spec(
                    id = "permissions.fineLocation",
                    kind = ProbeKind.PERMISSION,
                    requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
                ),
            ),
        ).findings.single()
        assertEquals(SupportStatus.SUPPORTED, finding.status)
        assertEquals("granted", finding.observedValue)
        assertEquals(emptyList(), source.queried)
    }

    @Test
    fun `a present feature spec reports supported without a platform call`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            availableFeatures = setOf("android.hardware.wifi.rtt"),
        )
        val finding = run(
            source,
            listOf(
                spec(
                    id = "wifi.rtt.feature",
                    kind = ProbeKind.FEATURE,
                    requiredFeature = "android.hardware.wifi.rtt",
                ),
            ),
        ).findings.single()
        assertEquals(SupportStatus.SUPPORTED, finding.status)
        assertEquals("present", finding.observedValue)
    }

    // --- asynchronous specs ----------------------------------------------------------------

    @Test
    fun `an unresolved async spec is reported as not probed`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val finding = run(source, listOf(spec(id = "gnss", kind = ProbeKind.ASYNC))).findings.single()
        assertEquals(SupportStatus.NOT_PROBED, finding.status)
        assertEquals(emptyList(), source.queried)
    }

    @Test
    fun `a resolved async spec is classified from its live-session outcome`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val report = run(
            source,
            listOf(spec(id = "gnss", kind = ProbeKind.ASYNC)),
            resolvedAsync = mapOf("gnss" to ProbeOutcome.Value("9 satellites, carrier phase present")),
        )
        val finding = report.findings.single()
        assertEquals(SupportStatus.SUPPORTED, finding.status)
        assertEquals("9 satellites, carrier phase present", finding.observedValue)
    }

    @Test
    fun `async guards still apply before a live result would be used`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(26))
        val report = run(
            source,
            listOf(spec(id = "gnss", kind = ProbeKind.ASYNC, requiredApiLevel = 31)),
            resolvedAsync = mapOf("gnss" to ProbeOutcome.Value("should be ignored")),
        )
        assertEquals(SupportStatus.BELOW_API_LEVEL, report.findings.single().status)
    }

    // --- report metadata -------------------------------------------------------------------

    @Test
    fun `the injected clock sets the report timestamp`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val report = run(source, listOf(spec()), clock = { 1_700_000_000_000L })
        assertEquals(1_700_000_000_000L, report.generatedAtEpochMillis)
    }

    @Test
    fun `notes are carried into the report`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val report = run(source, listOf(spec()), notes = listOf("emulator"))
        assertEquals(listOf("emulator"), report.notes)
    }

    @Test
    fun `spec notes become finding details when the platform offers none`() {
        val source = FakePlatformSource(descriptor = FakePlatformSource.descriptor(34))
        val finding = run(source, listOf(spec(note = "why this matters"))).findings.single()
        assertEquals("why this matters", finding.detail)
    }

    @Test
    fun `a platform detail outranks the spec note`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("x" to ProbeOutcome.Failed("real reason")),
        )
        val finding = run(source, listOf(spec(note = "spec note"))).findings.single()
        assertEquals("real reason", finding.detail)
    }

    @Test
    fun `one finding is produced per spec in catalog order`() {
        val specs = listOf(spec(id = "a"), spec(id = "b"), spec(id = "c"))
        val report = run(FakePlatformSource(descriptor = FakePlatformSource.descriptor(34)), specs)
        assertEquals(listOf("a", "b", "c"), report.findings.map { it.id })
    }

    @Test
    fun `a failing api does not prevent the rest of the report`() {
        val source = FakePlatformSource(
            descriptor = FakePlatformSource.descriptor(34),
            outcomes = mapOf("b" to ProbeOutcome.Failed("boom")),
        )
        val report = run(
            source,
            listOf(spec(id = "a"), spec(id = "b"), spec(id = "c")),
            resolvedAsync = emptyMap(),
        )
        assertEquals(3, report.findings.size)
        assertEquals(SupportStatus.THROWS, report.findings[1].status)
    }
}

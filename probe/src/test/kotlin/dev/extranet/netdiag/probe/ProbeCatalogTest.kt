package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.SystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integrity tests for the catalog.
 *
 * The last group is a regression test on the architectural critique that produced this
 * project: several high-value platform APIs were missing from the original source material,
 * and they are asserted to be present here so the omission cannot be reintroduced.
 */
class ProbeCatalogTest {

    @Test
    fun `ids are unique`() {
        val ids = ProbeCatalog.ALL.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet(), duplicates, "duplicate probe ids: $duplicates")
    }

    @Test
    fun `the catalog is substantial enough to be the deliverable`() {
        assertTrue(ProbeCatalog.ALL.size >= 55, "catalog only had ${ProbeCatalog.ALL.size} specs")
    }

    @Test
    fun `every spec is fully described`() {
        for (spec in ProbeCatalog.ALL) {
            assertTrue(spec.id.isNotBlank(), "blank id")
            assertTrue(spec.id.matches(Regex("[a-z0-9]+(\\.[a-zA-Z0-9]+)*")), "bad id format: ${spec.id}")
            assertTrue(spec.api.isNotBlank(), "blank api for ${spec.id}")
            assertTrue(spec.requiredApiLevel >= 1, "bad api level for ${spec.id}")
            assertTrue(
                spec.requiredPermission == null || spec.requiredPermission.startsWith("android.permission."),
                "malformed permission for ${spec.id}",
            )
        }
    }

    @Test
    fun `byId round trips every entry and rejects unknowns`() {
        for (spec in ProbeCatalog.ALL) {
            assertEquals(spec, ProbeCatalog.byId(spec.id))
        }
        assertNull(ProbeCatalog.byId("nope"))
    }

    @Test
    fun `forSystem partitions the catalog without loss or duplication`() {
        val covered = SystemId.entries.flatMap { ProbeCatalog.forSystem(it) }
        assertEquals(ProbeCatalog.ALL.size, covered.size)
        assertEquals(ProbeCatalog.ALL.toSet(), covered.toSet())
    }

    @Test
    fun `catalog covers every platform-facing system`() {
        // S3 Inference, S4 Decision SDK, S5 Collective and S6 Presentation own no platform
        // capability, so they legitimately contribute no probes. The five below do.
        val expected = setOf(
            SystemId.SENSOR_CORE,
            SystemId.MEASUREMENT_ENGINE,
            SystemId.CAPABILITY_REGISTRY,
            SystemId.BUDGET_GUARD,
            SystemId.PRIVACY,
        )
        val present = ProbeCatalog.ALL.map { it.system }.toSet()
        assertEquals(expected, present)
    }

    @Test
    fun `async ids are exactly the async specs`() {
        val asyncSpecs = ProbeCatalog.ALL.filter { it.kind == ProbeKind.ASYNC }
        assertTrue(asyncSpecs.isNotEmpty(), "an all-synchronous catalog would make NOT_PROBED dead code")
        assertEquals(asyncSpecs.map { it.id }, ProbeCatalog.asyncIds())
    }

    @Test
    fun `permission specs name the permission they report on`() {
        for (spec in ProbeCatalog.ALL.filter { it.kind == ProbeKind.PERMISSION }) {
            assertNotNull(spec.requiredPermission, "${spec.id} must declare its permission")
        }
    }

    @Test
    fun `feature specs name the feature they report on`() {
        for (spec in ProbeCatalog.ALL.filter { it.kind == ProbeKind.FEATURE }) {
            assertNotNull(spec.requiredFeature, "${spec.id} must declare its feature")
        }
    }

    // --- the eight data sources from the source material ------------------------------------

    @Test
    fun `all eight baseline data sources are represented`() {
        val required = listOf(
            "lte.signal.timingAdvance",        // 1 timing advance
            "lte.signal.rsrq",                 // 2 signal quality
            "lte.signal.rssnr",
            "lte.identity.earfcn",             // 3 neighbour cell / band identity
            "lte.identity.pci",
            "wifi.rtt.ranging",                // 4 wifi rtt
            "gnss.measurements",               // 5 raw gnss
            "connectivity.networkCapabilities", // 6 active probes / layer attribution
            "sensors.accelerometer",           // 7 sensor fusion
            "networkstats.packageUsage",       // 8 per-app traffic
        )
        for (id in required) {
            assertNotNull(ProbeCatalog.byId(id), "missing baseline data source: $id")
        }
    }

    // --- the APIs the source material omitted ----------------------------------------------

    @Test
    fun `catalog includes the diagnostics manager the source material never mentioned`() {
        // The OS already performs the DNS/TCP/TLS/HTTP chain; this is the highest-value
        // capability that was absent from the original data-source list.
        val spec = assertNotNull(ProbeCatalog.byId("connectivity.diagnosticsManager"))
        assertEquals(ProbeCatalog.API_30, spec.requiredApiLevel)
    }

    @Test
    fun `catalog includes the display info load proxy`() {
        val spec = assertNotNull(ProbeCatalog.byId("telephony.displayInfo"))
        assertEquals(ProbeKind.ASYNC, spec.kind)
    }

    @Test
    fun `catalog includes wifi link rate rather than only wifi rssi`() {
        assertNotNull(ProbeCatalog.byId("wifi.info.rxLinkSpeed"))
        assertNotNull(ProbeCatalog.byId("wifi.info.txLinkSpeed"))
        assertNotNull(ProbeCatalog.byId("wifi.info.wifiStandard"))
    }

    @Test
    fun `catalog includes the thermal status that otherwise disguises throttling as a fault`() {
        val spec = assertNotNull(ProbeCatalog.byId("power.thermalStatus"))
        assertEquals(SystemId.BUDGET_GUARD, spec.system)
    }

    @Test
    fun `catalog includes dual sim subscriptions`() {
        assertNotNull(ProbeCatalog.byId("telephony.subscriptions"))
    }

    @Test
    fun `catalog includes the push-based telephony callbacks that replace polling`() {
        assertNotNull(ProbeCatalog.byId("telephony.signalStrengthsCallback"))
        assertNotNull(ProbeCatalog.byId("telephony.cellInfoCallback"))
    }

    @Test
    fun `catalog includes the lte bandwidth term needed by any capacity estimate`() {
        // Without CellIdentityLte.getBandwidth the Shannon figure has no B, which is the
        // reason CQI tables were unusable in the first place.
        assertNotNull(ProbeCatalog.byId("lte.identity.bandwidth"))
    }

    @Test
    fun `the rtt feature spec reuses the ledger constant rather than a copied string`() {
        val spec = assertNotNull(ProbeCatalog.byId("wifi.rtt.feature"))
        assertEquals(dev.extranet.netdiag.core.ledger.WifiRtt.REQUIRED_FEATURE, spec.requiredFeature)
        assertEquals(dev.extranet.netdiag.core.ledger.WifiRtt.REQUIRED_FEATURE, spec.api)
    }

    @Test
    fun `permissions catalog covers the settings-only grant that leaks users`() {
        val spec = assertNotNull(ProbeCatalog.byId("permissions.packageUsageStats"))
        assertTrue(spec.note!!.contains("Settings"), spec.note!!)
    }
}

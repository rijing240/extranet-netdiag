package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.DeviceDescriptor

/**
 * A scripted [PlatformReportSource].
 *
 * Keeping the probe engine behind this seam is what lets B0's classification table be pinned on
 * the JVM, on a machine with no handset and no radio. It also records every id that was actually
 * queried, so the tests can prove that guard clauses short-circuit before touching the platform.
 *
 * The constructor parameter is named `descriptor` rather than `device` so that it does not
 * collide with the inherited [PlatformReportSource.device] property or with the companion
 * factory below.
 */
class FakePlatformSource(
    private val descriptor: DeviceDescriptor,
    private val grantedPermissions: Set<String> = emptySet(),
    private val availableFeatures: Set<String> = emptySet(),
    private val outcomes: Map<String, ProbeOutcome> = emptyMap(),
    private val defaultOutcome: ProbeOutcome = ProbeOutcome.Unavailable,
) : PlatformReportSource {

    /** Ids the runner asked the platform about, in order. */
    val queried: MutableList<String> = mutableListOf()

    override val device: DeviceDescriptor get() = descriptor

    override fun isPermissionGranted(permission: String): Boolean =
        permission in grantedPermissions

    override fun hasFeature(feature: String): Boolean = feature in availableFeatures

    override fun query(specId: String): ProbeOutcome {
        queried += specId
        return outcomes[specId] ?: defaultOutcome
    }

    companion object {
        /** A modern handset with everything granted and every feature present. */
        fun permissive(sdkInt: Int = 34): FakePlatformSource = FakePlatformSource(
            descriptor = descriptor(sdkInt),
            grantedPermissions = ALL_PERMISSIONS,
            availableFeatures = setOf(RTT_FEATURE),
            defaultOutcome = ProbeOutcome.Value("stub"),
        )

        /** A device with no permissions granted, which is the realistic first-run state. */
        fun lockedDown(sdkInt: Int = 34): FakePlatformSource = FakePlatformSource(
            descriptor = descriptor(sdkInt),
        )

        /** A descriptor for a named hardware family. */
        fun descriptor(
            sdkInt: Int,
            manufacturer: String = "Test",
            device: String = "test",
        ): DeviceDescriptor = DeviceDescriptor(
            manufacturer = manufacturer,
            model = "Model $sdkInt",
            device = device,
            sdkInt = sdkInt,
            releaseVersion = "sdk-$sdkInt",
            socManufacturer = manufacturer,
            socModel = "soc-$sdkInt",
        )

        /** Every permission the catalog can ask for. */
        val ALL_PERMISSIONS: Set<String> = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_PHONE_STATE",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.PACKAGE_USAGE_STATS",
        )

        /** The one hardware feature the catalog gates on. */
        const val RTT_FEATURE: String = "android.hardware.wifi.rtt"
    }
}

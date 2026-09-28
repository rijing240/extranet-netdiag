package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.ledger.WifiRtt
import dev.extranet.netdiag.core.report.SystemId

/**
 * Every platform capability the blueprint depends on, in one reviewable list.
 *
 * This is B0's central artifact. It is the union of the eight data sources in the source
 * material plus the platform APIs identified as missing from that list, notably
 * `ConnectivityDiagnosticsManager` (the OS already performs the DNS/TCP/TLS/HTTP probe chain),
 * `TelephonyDisplayInfo` (the 5G+/mmWave marker, a useful load proxy), the Wi-Fi link-rate
 * accessors (more informative than Wi-Fi RSSI), and the thermal status (which otherwise makes
 * throttling look like a network fault).
 *
 * Ordered so the report reads top-down by physical layer: radio, then Wi-Fi, then GNSS, then
 * the network stack, then device context, then permissions.
 */
public object ProbeCatalog {

    /** Android 13. Runtime permission introduced for nearby Wi-Fi device access. */
    public const val API_33: Int = 33

    /** Android 12. `TelephonyCallback` and `TelephonyDisplayInfo`. */
    public const val API_31: Int = 31

    /** Android 11. Timing advance on NR, nat64 prefix, ConnectivityDiagnosticsManager. */
    public const val API_30: Int = 30

    /** Android 10. Timing advance on LTE and GSM, link-rate accessors, thermal status. */
    public const val API_29: Int = 29

    /** Android 8.0. `NetworkStatsManager`, `getSignalStrength`. */
    public const val API_26: Int = 26

    /** Android 7.0. `LinkProperties`, `NetworkCapabilities`. */
    public const val API_24: Int = 24

    // --- Radio: signal strength and quality -------------------------------------------------

    private val radioSignal: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "telephony.allCellInfo",
            api = "android.telephony.TelephonyManager#getAllCellInfo",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "the container for every cell measurement below; without it nothing else fires",
        ),
        ProbeSpec(
            id = "lte.signal.rsrp",
            api = "android.telephony.CellSignalStrengthLte#getRsrp",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            note = "coverage axis of the coverage/interference/load triple",
        ),
        ProbeSpec(
            id = "lte.signal.rsrq",
            api = "android.telephony.CellSignalStrengthLte#getRsrq",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            note = "load axis: RSRQ = N * RSRP / RSSI, so it falls as neighbours get busy",
        ),
        ProbeSpec(
            id = "lte.signal.rssnr",
            api = "android.telephony.CellSignalStrengthLte#getRssnr",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            note = "interference axis, and the SINR input to the Shannon capacity figure",
        ),
        ProbeSpec(
            id = "lte.signal.rssi",
            api = "android.telephony.CellSignalStrengthLte#getRssi",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
        ),
        ProbeSpec(
            id = "lte.signal.level",
            api = "android.telephony.CellSignalStrengthLte#getLevel",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            note = "what the status-bar bars actually show; needed for the '3 bars but dead' copy",
        ),
        ProbeSpec(
            id = "lte.signal.timingAdvance",
            api = "android.telephony.CellSignalStrengthLte#getTimingAdvance",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
            note = "serving-cell range band; the highest-value radio primitive in the plan",
        ),
        ProbeSpec(
            id = "nr.signal.ssRsrp",
            api = "android.telephony.CellSignalStrengthNr#getSsRsrp",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
        ),
        ProbeSpec(
            id = "nr.signal.ssRsrq",
            api = "android.telephony.CellSignalStrengthNr#getSsRsrq",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
        ),
        ProbeSpec(
            id = "nr.signal.ssSinr",
            api = "android.telephony.CellSignalStrengthNr#getSsSinr",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
        ),
        ProbeSpec(
            id = "nr.signal.csiRsrp",
            api = "android.telephony.CellSignalStrengthNr#getCsiRsrp",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
            note = "channel-state rather than beam-reference measurement; better throughput proxy",
        ),
        ProbeSpec(
            id = "nr.signal.timingAdvance",
            api = "android.telephony.CellSignalStrengthNr#getTimingAdvance",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
            note = "NR timing advance; frequently UNAVAILABLE outside vendor firmware",
        ),
        ProbeSpec(
            id = "gsm.signal.timingAdvance",
            api = "android.telephony.CellSignalStrengthGsm#getTimingAdvance",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
            note = "553.46 m per step, 2G fallback detection only. The getter is not in the " +
                "public SDK at compileSdk 35, so this is expected to report UNAVAILABLE: " +
                "the answer is that an app cannot read GSM timing advance at all.",
        ),
        ProbeSpec(
            id = "wcdma.signal.dbm",
            api = "android.telephony.CellSignalStrengthWcdma#getDbm",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            note = "3G fallback path; a RAT downgrade here is a strong degradation signal. " +
                "RSCP and EcNo are not public API, so only the aggregate dBm is available.",
        ),
        ProbeSpec(
            id = "cdma.signal",
            api = "android.telephony.CellSignalStrengthCdma#getCdmaDbm",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
            note = "legacy CDMA2000 networks; relevant only for a few remaining carriers",
        ),
        ProbeSpec(
            id = "telephony.signalStrength",
            api = "android.telephony.TelephonyManager#getSignalStrength",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_26,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "cheaper than getAllCellInfo; useful for high-rate sampling within the X2 budget",
        ),
    )

    // --- Radio: identity --------------------------------------------------------------------

    private val radioIdentity: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "lte.identity.earfcn",
            api = "android.telephony.CellIdentityLte#getEarfcn",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 24,
            note = "band derivation, and part of the hashed Atlas cell key",
        ),
        ProbeSpec(
            id = "lte.identity.pci",
            api = "android.telephony.CellIdentityLte#getPci",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 24,
        ),
        ProbeSpec(
            id = "lte.identity.tac",
            api = "android.telephony.CellIdentityLte#getTac",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
        ),
        ProbeSpec(
            id = "lte.identity.ci",
            api = "android.telephony.CellIdentityLte#getCi",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 17,
        ),
        ProbeSpec(
            id = "lte.identity.bandwidth",
            api = "android.telephony.CellIdentityLte#getBandwidth",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 24,
            note = "the B term in the Shannon capacity figure; needed for any capacity estimate",
        ),
        ProbeSpec(
            id = "nr.identity.nrarfcn",
            api = "android.telephony.CellIdentityNr#getNrarfcn",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
        ),
        ProbeSpec(
            id = "nr.identity.bands",
            api = "android.telephony.CellIdentityNr#getBands",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
            note = "low-band shift indoors is one of the environmental classifier inputs",
        ),
        ProbeSpec(
            id = "nr.identity.tac",
            api = "android.telephony.CellIdentityNr#getTac",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
        ),
        ProbeSpec(
            id = "telephony.dataNetworkType",
            api = "android.telephony.TelephonyManager#getDataNetworkType",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_24,
            requiredPermission = "android.permission.READ_PHONE_STATE",
            note = "RAT downgrade events are a leading indicator in the drop model",
        ),
        ProbeSpec(
            id = "telephony.serviceState",
            api = "android.telephony.TelephonyManager#getServiceState",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_26,
            requiredPermission = "android.permission.READ_PHONE_STATE",
            note = "the label source for NO_SERVICE transitions in B8",
        ),
        ProbeSpec(
            id = "telephony.displayInfo",
            api = "android.telephony.TelephonyDisplayInfo#getOverrideNetworkType",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = API_30,
            note = "distinguishes NR advanced / mmWave; a useful load and capacity proxy",
        ),
        ProbeSpec(
            id = "telephony.cellInfoCallback",
            api = "android.telephony.TelephonyCallback.CellInfoListener",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = API_31,
            note = "push-based replacement for polling getAllCellInfo, within the X2 budget",
        ),
        ProbeSpec(
            id = "telephony.signalStrengthsCallback",
            api = "android.telephony.TelephonyCallback.SignalStrengthsListener",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = API_31,
        ),
        ProbeSpec(
            id = "telephony.subscriptions",
            api = "android.telephony.SubscriptionManager#getActiveSubscriptionInfoList",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 22,
            requiredPermission = "android.permission.READ_PHONE_STATE",
            note = "dual-SIM means every radio measurement is per-subscription, not per-device",
        ),
    )

    // --- Wi-Fi ------------------------------------------------------------------------------

    private val wifi: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "wifi.rtt.feature",
            api = WifiRtt.REQUIRED_FEATURE,
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.FEATURE,
            requiredFeature = WifiRtt.REQUIRED_FEATURE,
            note = "802.11mc FTM capability; gates every RTT ranging call",
        ),
        ProbeSpec(
            id = "wifi.rtt.ranging",
            api = "android.net.wifi.rtt.RangingResult#getDistanceMm",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = 28,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "the only sub-2 m ranging primitive available; needs an FTM responder AP",
        ),
        ProbeSpec(
            id = "wifi.info.rxLinkSpeed",
            api = "android.net.wifi.WifiInfo#getRxLinkSpeedMbps",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
            note = "far more informative than Wi-Fi RSSI for predicting throughput",
        ),
        ProbeSpec(
            id = "wifi.info.txLinkSpeed",
            api = "android.net.wifi.WifiInfo#getTxLinkSpeedMbps",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
        ),
        ProbeSpec(
            id = "wifi.info.wifiStandard",
            api = "android.net.wifi.WifiInfo#getWifiStandard",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
            note = "distinguishes 802.11a/b/g/n/ac/ax/be; sets the expected capacity envelope",
        ),
        ProbeSpec(
            id = "wifi.info.rssi",
            api = "android.net.wifi.WifiInfo#getRssi",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 1,
        ),
        ProbeSpec(
            id = "wifi.scan.neighborAps",
            api = "android.net.wifi.ScanResult",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 1,
            note = "Wi-Fi fingerprinting is where real indoor accuracy lives, not cell trilateration",
        ),
        ProbeSpec(
            id = "wifi.scan.wifiStandard",
            api = "android.net.wifi.ScanResult#getWifiStandard",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
        ),
        ProbeSpec(
            id = "wifi.scan.channelWidth",
            api = "android.net.wifi.ScanResult#channelWidth",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 23,
        ),
    )

    // --- GNSS ------------------------------------------------------------------------------

    private val gnss: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "gnss.measurements",
            api = "android.location.GnssMeasurementsEvent.Callback#onGnssMeasurementsReceived",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = 24,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "raw measurement access; the only route to Doppler velocity",
        ),
        ProbeSpec(
            id = "gnss.measurements.pseudorangeRate",
            api = "android.location.GnssMeasurement#getPseudorangeRateMetersPerSecond",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = 24,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "already-converted Doppler; feeds the 4-unknown velocity solve directly",
        ),
        ProbeSpec(
            id = "gnss.measurements.carrierPhase",
            api = "android.location.GnssMeasurement#getCarrierPhase",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = 24,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "frequently all-zero on mid-range firmware; the plan's biggest hardware lottery",
        ),
        ProbeSpec(
            id = "gnss.measurements.accumulatedDeltaRange",
            api = "android.location.GnssMeasurement#getAccumulatedDeltaRangeMeters",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = 24,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
        ),
        ProbeSpec(
            id = "gnss.capabilities",
            api = "android.location.LocationManager#getGnssCapabilities",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_31,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "declares which measurement types the chipset claims to support",
        ),
        ProbeSpec(
            id = "gnss.antennaInfo",
            api = "android.location.LocationManager#getGnssAntennaInfos",
            system = SystemId.CAPABILITY_REGISTRY,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
        ),
        ProbeSpec(
            id = "gnss.status",
            api = "android.location.GnssStatus.Callback#onSatelliteStatusChanged",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = 24,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "C/N0 collapse is the primary indoor/outdoor classifier input",
        ),
        ProbeSpec(
            id = "gnss.providers",
            api = "android.location.LocationManager#getProviders",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 1,
        ),
    )

    // --- Network stack and active probes ---------------------------------------------------

    private val networkStack: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "connectivity.networkCapabilities",
            api = "android.net.ConnectivityManager#getNetworkCapabilities",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 21,
            requiredPermission = "android.permission.ACCESS_NETWORK_STATE",
        ),
        ProbeSpec(
            id = "connectivity.transport",
            api = "android.net.NetworkCapabilities#getTransportInfo",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 21,
            note = "which transport is actually carrying traffic right now",
        ),
        ProbeSpec(
            id = "connectivity.linkBandwidthEstimates",
            api = "android.net.NetworkCapabilities#getLinkDownstreamBandwidthKbps",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 21,
            note = "operator-declared envelope; compare against measured throughput",
        ),
        ProbeSpec(
            id = "connectivity.diagnosticsManager",
            api = "android.net.ConnectivityDiagnosticsManager",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
            note = "the OS already runs the DNS/TCP/TLS/HTTP chain; highest value per line of code",
        ),
        ProbeSpec(
            id = "connectivity.dataStallReport",
            api = "android.net.ConnectivityDiagnosticsManager.DataStallReport",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = API_30,
            note = "stall detection with the OS's own timers, free of our probe overhead",
        ),
        ProbeSpec(
            id = "connectivity.defaultNetworkCallback",
            api = "android.net.ConnectivityManager#registerDefaultNetworkCallback",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.ASYNC,
            requiredApiLevel = API_24,
            requiredPermission = "android.permission.ACCESS_NETWORK_STATE",
            note = "the sub-second transition signal the drop predictor is trained against",
        ),
        ProbeSpec(
            id = "link.mtu",
            api = "android.net.LinkProperties#getMtu",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 21,
            note = "tunnels and MSS clamping are a common cause of mysterious stalls",
        ),
        ProbeSpec(
            id = "link.dnsServers",
            api = "android.net.LinkProperties#getDnsServers",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 21,
            note = "identifies the resolver, so DNS latency can be attributed to a specific server",
        ),
        ProbeSpec(
            id = "link.nat64Prefix",
            api = "android.net.LinkProperties#getNat64Prefix",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_30,
            note = "IPv6-only carriers add translation latency that looks like server latency",
        ),
        ProbeSpec(
            id = "networkstats.packageUsage",
            api = "android.net.NetworkStatsManager",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 23,
            requiredPermission = "android.permission.PACKAGE_USAGE_STATS",
            note = "special Settings grant, not a runtime prompt; expect heavy funnel loss",
        ),
        ProbeSpec(
            id = "trafficstats.uidBytes",
            api = "android.net.TrafficStats",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 8,
        ),
        ProbeSpec(
            id = "connectivity.keepalive",
            api = "android.net.SocketKeepalive",
            system = SystemId.MEASUREMENT_ENGINE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 26,
        ),
    )

    // --- Device context --------------------------------------------------------------------

    private val deviceContext: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "power.thermalStatus",
            api = "android.os.PowerManager#getCurrentThermalStatus",
            system = SystemId.BUDGET_GUARD,
            kind = ProbeKind.SYNC,
            requiredApiLevel = API_29,
            note = "thermal throttling depresses modem throughput and otherwise looks like a fault",
        ),
        ProbeSpec(
            id = "battery.property",
            api = "android.os.BatteryManager#getIntProperty",
            system = SystemId.BUDGET_GUARD,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 1,
        ),
        ProbeSpec(
            id = "sensors.accelerometer",
            api = "android.hardware.SensorManager#getDefaultSensor(TYPE_ACCELEROMETER)",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 3,
            note = "movement gate for duty cycling; without it background sampling is wasteful",
        ),
        ProbeSpec(
            id = "sensors.gyroscope",
            api = "android.hardware.SensorManager#getDefaultSensor(TYPE_GYROSCOPE)",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 3,
        ),
        ProbeSpec(
            id = "sensors.magnetometer",
            api = "android.hardware.SensorManager#getDefaultSensor(TYPE_MAGNETIC_FIELD)",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 3,
            note = "heading; noisy near vehicles, so it informs rather than decides",
        ),
        ProbeSpec(
            id = "sensors.barometer",
            api = "android.hardware.SensorManager#getDefaultSensor(TYPE_PRESSURE)",
            system = SystemId.SENSOR_CORE,
            kind = ProbeKind.SYNC,
            requiredApiLevel = 3,
            note = "floor-level change detection; the strongest single indoor indicator",
        ),
    )

    // --- Permissions -----------------------------------------------------------------------

    private val permissions: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "permissions.fineLocation",
            api = "android.permission.ACCESS_FINE_LOCATION",
            system = SystemId.PRIVACY,
            kind = ProbeKind.PERMISSION,
            requiredApiLevel = 23,
            requiredPermission = "android.permission.ACCESS_FINE_LOCATION",
            note = "gates every cell and GNSS measurement; the single most important grant",
        ),
        ProbeSpec(
            id = "permissions.coarseLocation",
            api = "android.permission.ACCESS_COARSE_LOCATION",
            system = SystemId.PRIVACY,
            kind = ProbeKind.PERMISSION,
            requiredApiLevel = 23,
            requiredPermission = "android.permission.ACCESS_COARSE_LOCATION",
        ),
        ProbeSpec(
            id = "permissions.phoneState",
            api = "android.permission.READ_PHONE_STATE",
            system = SystemId.PRIVACY,
            kind = ProbeKind.PERMISSION,
            requiredApiLevel = 23,
            requiredPermission = "android.permission.READ_PHONE_STATE",
        ),
        ProbeSpec(
            id = "permissions.nearbyWifiDevices",
            api = "android.permission.NEARBY_WIFI_DEVICES",
            system = SystemId.PRIVACY,
            kind = ProbeKind.PERMISSION,
            requiredApiLevel = API_33,
            requiredPermission = "android.permission.NEARBY_WIFI_DEVICES",
            note = "declared with neverForLocation, so Wi-Fi scanning need not imply location",
        ),
        ProbeSpec(
            id = "permissions.packageUsageStats",
            api = "android.permission.PACKAGE_USAGE_STATS",
            system = SystemId.PRIVACY,
            kind = ProbeKind.PERMISSION,
            requiredApiLevel = 23,
            requiredPermission = "android.permission.PACKAGE_USAGE_STATS",
            note = "special access granted in Settings, not a runtime dialog",
        ),
    )

    /** The whole catalog. Order is preserved into the report. */
    public val ALL: List<ProbeSpec> =
        radioSignal + radioIdentity + wifi + gnss + networkStack + deviceContext + permissions

    /** Lookup by [ProbeSpec.id]. */
    public fun byId(id: String): ProbeSpec? = ALL.firstOrNull { it.id == id }

    /** Ids that need a live session before they can report anything. */
    public fun asyncIds(): List<String> =
        ALL.filter { it.kind == ProbeKind.ASYNC }.map { it.id }

    /** Specs belonging to one system. */
    public fun forSystem(system: SystemId): List<ProbeSpec> = ALL.filter { it.system == system }
}

package dev.extranet.netdiag.android.measurement

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import dev.extranet.netdiag.core.report.DeviceDescriptor
import dev.extranet.netdiag.probe.PlatformReportSource
import dev.extranet.netdiag.probe.ProbeOutcome

/**
 * Answers the probe catalog by asking the real platform.
 *
 * ## Design rules
 * 1. **Nothing throws outward.** Every platform read is wrapped, because a single vendor
 *    firmware bug must not abort a report. Failures become [ProbeOutcome.Failed] and are
 *    surfaced as `THROWS` findings, which are themselves diagnostic.
 * 2. **UNAVAILABLE is a result, not an error.** `CellInfo.UNAVAILABLE` (Integer.MAX_VALUE) is
 *    the modem saying "I do not measure this", which is exactly what B0 exists to discover.
 * 3. **API level is checked before the call**, so `NoSuchMethodError` on old hardware is
 *    replaced by a clear `BELOW_API_LEVEL` finding.
 */
public class AndroidPlatformProbe(
    context: Context,
) : PlatformReportSource {

    private val appContext: Context = context.applicationContext

    private val telephony: TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)
    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val power: PowerManager? = appContext.getSystemService(PowerManager::class.java)
    private val location: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)
    private val sensors: SensorManager? = appContext.getSystemService(SensorManager::class.java)
    private val battery: BatteryManager? = appContext.getSystemService(BatteryManager::class.java)
    private val subscriptions: SubscriptionManager? =
        appContext.getSystemService(SubscriptionManager::class.java)

    override val device: DeviceDescriptor = DeviceDescriptor(
        manufacturer = Build.MANUFACTURER ?: "unknown",
        model = Build.MODEL ?: "unknown",
        device = Build.DEVICE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        releaseVersion = Build.VERSION.RELEASE ?: "unknown",
        socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
    )

    override fun isPermissionGranted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun hasFeature(feature: String): Boolean =
        appContext.packageManager.hasSystemFeature(feature)

    override fun query(specId: String): ProbeOutcome = guarded(specId) {
        when (specId) {
            // --- radio signal -----------------------------------------------------------------
            "telephony.allCellInfo" -> cellsOutcome()
            "lte.signal.rsrp" -> lte { intOutcome(it.cellSignalStrength.rsrp) }
            "lte.signal.rsrq" -> lte { intOutcome(it.cellSignalStrength.rsrq) }
            "lte.signal.rssnr" -> lte { intOutcome(it.cellSignalStrength.rssnr) }
            "lte.signal.rssi" -> lte { intOutcome(it.cellSignalStrength.rssi) }
            "lte.signal.level" -> lte { intOutcome(it.cellSignalStrength.level) }
            "lte.signal.timingAdvance" -> lte {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@lte ProbeOutcome.Unavailable
                intOutcome(it.cellSignalStrength.timingAdvance)
            }

            "nr.signal.ssRsrp" -> nrSignal { intOutcome(it.ssRsrp) }
            "nr.signal.ssRsrq" -> nrSignal { intOutcome(it.ssRsrq) }
            "nr.signal.ssSinr" -> nrSignal { intOutcome(it.ssSinr) }
            "nr.signal.csiRsrp" -> nrSignal { intOutcome(it.csiRsrp) }
            "nr.signal.timingAdvance" -> nrSignal {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@nrSignal ProbeOutcome.Unavailable
                intOutcome(it.timingAdvance)
            }

            // CellSignalStrengthGsm exposes no timing-advance getter in the public SDK, so this
            // is a guaranteed UNAVAILABLE rather than a probe. It stays in the catalog because
            // the answer "you cannot get GSM timing advance from an app" is itself a finding.
            "gsm.signal.timingAdvance" -> ProbeOutcome.Unavailable

            "wcdma.signal.dbm" -> cells()
                .filterIsInstance<CellInfoWcdma>().firstOrNull()
                ?.let { intOutcome(it.cellSignalStrength.dbm) } ?: ProbeOutcome.Unavailable
            "cdma.signal" -> cells()
                .filterIsInstance<android.telephony.CellInfoCdma>().firstOrNull()
                ?.let { intOutcome(it.cellSignalStrength.cdmaDbm) } ?: ProbeOutcome.Unavailable

            "telephony.signalStrength" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@guarded ProbeOutcome.Unavailable
                telephony?.signalStrength?.let { ProbeOutcome.Value(it.toString()) }
                    ?: ProbeOutcome.Unavailable
            }

            // --- radio identity ---------------------------------------------------------------
            "lte.identity.earfcn" -> lteIdentity {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@lteIdentity ProbeOutcome.Unavailable
                intOutcome(it.earfcn)
            }
            "lte.identity.pci" -> lteIdentity {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@lteIdentity ProbeOutcome.Unavailable
                intOutcome(it.pci)
            }
            "lte.identity.tac" -> lteIdentity { intOutcome(it.tac) }
            "lte.identity.ci" -> lteIdentity { intOutcome(it.ci) }
            "lte.identity.bandwidth" -> lteIdentity {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@lteIdentity ProbeOutcome.Unavailable
                val khz = it.bandwidth
                if (khz == CellInfo.UNAVAILABLE || khz <= 0) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("$khz kHz (${khz / 1000} MHz)")
            }

            "nr.identity.nrarfcn" -> nrIdentity { intOutcome(it.nrarfcn) }
            "nr.identity.bands" -> nrIdentity {
                val bands = it.bands
                if (bands == null || bands.isEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(bands.joinToString(prefix = "n", separator = ",n"))
            }
            "nr.identity.tac" -> nrIdentity { intOutcome(it.tac) }

            "telephony.dataNetworkType" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@guarded ProbeOutcome.Unavailable
                val type = telephony?.dataNetworkType ?: return@guarded ProbeOutcome.Unavailable
                if (type == TelephonyManager.NETWORK_TYPE_UNKNOWN) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(networkTypeName(type))
            }

            "telephony.serviceState" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@guarded ProbeOutcome.Unavailable
                telephony?.serviceState?.let {
                    ProbeOutcome.Value("state=${it.state} roaming=${it.roaming}")
                } ?: ProbeOutcome.Unavailable
            }

            "telephony.subscriptions" -> {
                val list = subscriptions?.activeSubscriptionInfoList
                if (list.isNullOrEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("${list.size} active subscription(s)")
            }

            // --- wi-fi -------------------------------------------------------------------------
            "wifi.rtt.feature" -> if (hasFeature("android.hardware.wifi.rtt")) {
                ProbeOutcome.Value("feature present")
            } else {
                ProbeOutcome.Absent
            }

            "wifi.info.rxLinkSpeed" -> wifiInfo {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@wifiInfo ProbeOutcome.Unavailable
                positiveOrUnavailable(it.rxLinkSpeedMbps, "Mbps")
            }
            "wifi.info.txLinkSpeed" -> wifiInfo {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@wifiInfo ProbeOutcome.Unavailable
                positiveOrUnavailable(it.txLinkSpeedMbps, "Mbps")
            }
            "wifi.info.wifiStandard" -> wifiInfo {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@wifiInfo ProbeOutcome.Unavailable
                val standard = it.wifiStandard
                if (standard == ScanResult.WIFI_STANDARD_UNKNOWN) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(wifiStandardName(standard))
            }
            "wifi.info.rssi" -> wifiInfo { intOutcome(it.rssi) }

            "wifi.scan.neighborAps" -> {
                val results = wifi?.scanResults
                if (results.isNullOrEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("${results.size} visible AP(s)")
            }
            "wifi.scan.wifiStandard" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded ProbeOutcome.Unavailable
                val results = wifi?.scanResults
                if (results.isNullOrEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(
                    results.take(3).joinToString { wifiStandardName(it.wifiStandard) },
                )
            }
            "wifi.scan.channelWidth" -> {
                val results = wifi?.scanResults
                if (results.isNullOrEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(
                    results.take(3).joinToString { "${it.channelWidth} MHz" },
                )
            }

            // --- gnss (synchronous surface only) ----------------------------------------------
            "gnss.capabilities" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@guarded ProbeOutcome.Unavailable
                // GnssCapabilities.toString() already enumerates every supported flag, which
                // avoids depending on individual has*() accessors that vary by API level.
                location?.gnssCapabilities?.let { ProbeOutcome.Value(it.toString()) }
                    ?: ProbeOutcome.Unavailable
            }
            "gnss.antennaInfo" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded ProbeOutcome.Unavailable
                val infos = location?.gnssAntennaInfos
                if (infos.isNullOrEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("${infos.size} antenna info object(s)")
            }
            "gnss.providers" -> {
                val providers = location?.getProviders(true)
                if (providers.isNullOrEmpty()) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(providers.sorted().joinToString())
            }

            // --- network stack ----------------------------------------------------------------
            "connectivity.networkCapabilities" -> {
                val caps = activeNetworkCapabilities()
                if (caps == null) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(transportName(caps))
            }
            "connectivity.transport" -> {
                val caps = activeNetworkCapabilities()
                if (caps == null) ProbeOutcome.Unavailable
                else ProbeOutcome.Value(transportName(caps))
            }
            "connectivity.linkBandwidthEstimates" -> {
                val caps = activeNetworkCapabilities() ?: return@guarded ProbeOutcome.Unavailable
                ProbeOutcome.Value(
                    "down=${caps.linkDownstreamBandwidthKbps} kbps up=${caps.linkUpstreamBandwidthKbps} kbps",
                )
            }
            "connectivity.diagnosticsManager" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded ProbeOutcome.Unavailable
                val manager = appContext.getSystemService(
                    android.net.ConnectivityDiagnosticsManager::class.java,
                )
                if (manager == null) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("ConnectivityDiagnosticsManager available")
            }
            "link.mtu" -> linkProperties()?.let { ProbeOutcome.Value("${it.mtu} bytes") }
                ?: ProbeOutcome.Unavailable
            "link.dnsServers" -> linkProperties()?.dnsServers?.takeIf { it.isNotEmpty() }
                ?.let { ProbeOutcome.Value(it.joinToString()) } ?: ProbeOutcome.Unavailable
            "link.nat64Prefix" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded ProbeOutcome.Unavailable
                linkProperties()?.nat64Prefix?.let { ProbeOutcome.Value(it.toString()) }
                    ?: ProbeOutcome.Unavailable
            }
            "networkstats.packageUsage" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@guarded ProbeOutcome.Unavailable
                val manager = appContext.getSystemService(
                    android.app.usage.NetworkStatsManager::class.java,
                ) ?: return@guarded ProbeOutcome.Unavailable
                val end = System.currentTimeMillis()
                val start = end - 60_000L
                val bucket = manager.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    null,
                    start,
                    end,
                )
                if (bucket == null) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("rx=${bucket.rxBytes}B tx=${bucket.txBytes}B over 60 s")
            }
            "trafficstats.uidBytes" -> {
                val uid = android.os.Process.myUid()
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                if (rx < 0 || tx < 0) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("uid=$uid rx=${rx}B tx=${tx}B")
            }
            "connectivity.keepalive" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) ProbeOutcome.Unavailable
                else ProbeOutcome.Value("SocketKeepalive available")
            }

            // --- device context ---------------------------------------------------------------
            "power.thermalStatus" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@guarded ProbeOutcome.Unavailable
                power?.currentThermalStatus?.let { ProbeOutcome.Value("thermal status $it") }
                    ?: ProbeOutcome.Unavailable
            }
            "battery.property" -> battery
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
                ?.let { ProbeOutcome.Value("$it%") }
                ?: ProbeOutcome.Unavailable

            "sensors.accelerometer" -> sensorOutcome(Sensor.TYPE_ACCELEROMETER)
            "sensors.gyroscope" -> sensorOutcome(Sensor.TYPE_GYROSCOPE)
            "sensors.magnetometer" -> sensorOutcome(Sensor.TYPE_MAGNETIC_FIELD)
            "sensors.barometer" -> sensorOutcome(Sensor.TYPE_PRESSURE)

            // --- permissions ------------------------------------------------------------------
            // Handled by the runner's guard, which never reaches here for a granted permission.
            else -> ProbeOutcome.Unavailable
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private inline fun guarded(specId: String, block: () -> ProbeOutcome): ProbeOutcome = try {
        block()
    } catch (throwable: Throwable) {
        // SecurityException is the common case (a redacted identity needs location permission)
        // but vendor firmware throws IllegalStateException and IndexOutOfBounds too, so the
        // net is deliberately wide: one broken API must not cost us the whole report.
        Log.w(TAG, "probe $specId failed", throwable)
        ProbeOutcome.Failed("${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    private fun cells(): List<CellInfo> = telephony?.allCellInfo.orEmpty()

    private fun cellsOutcome(): ProbeOutcome {
        val list = cells()
        if (list.isEmpty()) return ProbeOutcome.Unavailable
        val registered = list.count { it.isRegistered }
        val types = list.map { cellTypeName(it) }.distinct().sorted()
        return ProbeOutcome.Value("${list.size} cell(s), $registered registered, types=${types.joinToString()}")
    }

    private fun lte(block: (CellInfoLte) -> ProbeOutcome): ProbeOutcome =
        cells().filterIsInstance<CellInfoLte>().firstOrNull()?.let(block) ?: ProbeOutcome.Unavailable

    private fun lteIdentity(block: (CellIdentityLte) -> ProbeOutcome): ProbeOutcome =
        lte { block(it.cellIdentity) }

    private fun nr(block: (CellInfoNr) -> ProbeOutcome): ProbeOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ProbeOutcome.Unavailable
        return cells().filterIsInstance<CellInfoNr>().firstOrNull()?.let(block)
            ?: ProbeOutcome.Unavailable
    }

    // CellInfoNr's accessors are declared against the CellSignalStrength and CellIdentity base
    // types, so the narrow types have to be recovered. The casts cannot fail on a CellInfoNr,
    // but a safe cast keeps a vendor implementation from taking the report down with it.
    private fun nrSignal(block: (CellSignalStrengthNr) -> ProbeOutcome): ProbeOutcome =
        nr { cell ->
            val strength = cell.cellSignalStrength as? CellSignalStrengthNr
                ?: return@nr ProbeOutcome.Unavailable
            block(strength)
        }

    private fun nrIdentity(block: (CellIdentityNr) -> ProbeOutcome): ProbeOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ProbeOutcome.Unavailable
        return nr { cell ->
            val identity = cell.cellIdentity as? CellIdentityNr
                ?: return@nr ProbeOutcome.Unavailable
            block(identity)
        }
    }

    private inline fun wifiInfo(block: (WifiInfo) -> ProbeOutcome): ProbeOutcome =
        wifi?.connectionInfo?.let(block) ?: ProbeOutcome.Unavailable

    private fun sensorOutcome(type: Int): ProbeOutcome {
        val sensor = sensors?.getDefaultSensor(type)
        return if (sensor == null) {
            ProbeOutcome.Absent
        } else {
            ProbeOutcome.Value("${sensor.name} (vendor ${sensor.vendor})")
        }
    }

    private fun intOutcome(value: Int): ProbeOutcome =
        if (value == CellInfo.UNAVAILABLE) ProbeOutcome.Unavailable else ProbeOutcome.Value(value.toString())

    private fun positiveOrUnavailable(value: Int, unit: String): ProbeOutcome =
        if (value <= 0 || value == CellInfo.UNAVAILABLE) ProbeOutcome.Unavailable
        else ProbeOutcome.Value("$value $unit")

    private fun activeNetworkCapabilities() = connectivity?.activeNetwork
        ?.let { connectivity.getNetworkCapabilities(it) }

    private fun linkProperties() = connectivity?.activeNetwork
        ?.let { connectivity.getLinkProperties(it) }

    private fun transportName(caps: android.net.NetworkCapabilities): String = buildString {
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) append("wifi ")
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) append("cellular ")
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) append("ethernet ")
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) append("vpn ")
        append("metered=${!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}")
    }.trim()

    private fun cellTypeName(cell: CellInfo): String = when (cell) {
        is CellInfoLte -> "LTE"
        is CellInfoGsm -> "GSM"
        is CellInfoWcdma -> "WCDMA"
        is android.telephony.CellInfoCdma -> "CDMA"
        is android.telephony.CellInfoTdscdma -> "TDSCDMA"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) "NR" else "OTHER"
    }

    private fun wifiStandardName(standard: Int): String = when (standard) {
        ScanResult.WIFI_STANDARD_LEGACY -> "legacy"
        ScanResult.WIFI_STANDARD_11N -> "11n"
        ScanResult.WIFI_STANDARD_11AC -> "11ac"
        ScanResult.WIFI_STANDARD_11AX -> "11ax"
        ScanResult.WIFI_STANDARD_11AD -> "11ad"
        else -> "unknown($standard)"
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "type($type)"
    }

    private companion object {
        const val TAG = "CapabilityProbe"
    }
}

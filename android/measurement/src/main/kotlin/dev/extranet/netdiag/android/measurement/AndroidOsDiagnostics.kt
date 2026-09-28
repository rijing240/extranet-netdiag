package dev.extranet.netdiag.android.measurement

import android.content.Context
import android.net.ConnectivityDiagnosticsManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import dev.extranet.netdiag.measure.OsDiagnostics
import dev.extranet.netdiag.measure.OsDiagnosticsSource
import dev.extranet.netdiag.measure.ResolverAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The platform half of S2's engine: the active network's resolver, and the OS's own verdict on
 * whether that network works.
 *
 * ## Why [collect] blocks
 *
 * `ConnectivityDiagnosticsManager` only delivers a report through a callback, on an executor the
 * caller supplies. Wrapping that in a bounded latch is the simplest thing that keeps the engine's
 * shape synchronous and therefore testable: the alternatives - a suspending engine, or a callback
 * threaded through the run - would spread concurrency across every layer of the module for one
 * platform call. Callers run the engine on a worker thread.
 *
 * Waiting is bounded by the caller's budget, and a report that never arrives is reported as never
 * having arrived. That distinction matters: "the platform refused to answer" and "the platform
 * answered that everything is fine" are different facts, and the report keeps them apart.
 *
 * ## Design rules
 *
 * 1. **Nothing throws outward.** Every platform read is wrapped, matching
 *    [AndroidPlatformProbe]'s rule, so one vendor firmware bug cannot take a run down.
 * 2. **API level is checked before the call**, so an old device gets an explained null rather
 *    than a `NoSuchMethodError`.
 * 3. **Counts are quoted, not interpreted.** The platform's probe bitmasks are reported as the
 *    raw numbers it produced, because the constants that would name the bits are deprecated and
 *    a confidently wrong decode is worse than an honest number.
 */
public class AndroidOsDiagnostics(context: Context) : OsDiagnosticsSource {

    private val appContext: Context = context.applicationContext

    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    override fun resolver(): ResolverAddress? {
        return try {
            val manager = connectivity ?: return null
            val network = manager.activeNetwork ?: return null
            val link = manager.getLinkProperties(network) ?: return null
            // The first server is the one the OS itself would use for the first query, so it is
            // the one whose latency a user would feel.
            val literal = link.dnsServers.firstOrNull()?.hostAddress ?: return null
            ResolverAddress(literal)
        } catch (throwable: Throwable) {
            Log.w(TAG, "could not read the active network's resolver", throwable)
            null
        }
    }

    override fun collect(waitMillis: Long): OsDiagnostics? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "connectivity diagnostics need API 30; this device reports ${Build.VERSION.SDK_INT}")
            return null
        }
        val manager = connectivity ?: return null
        val diagnostics = appContext.getSystemService(ConnectivityDiagnosticsManager::class.java)
            ?: return null

        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        var capturedReport: ConnectivityDiagnosticsManager.ConnectivityReport? = null
        var capturedStall: ConnectivityDiagnosticsManager.DataStallReport? = null

        val callback = object : ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback() {
            override fun onConnectivityReportAvailable(
                report: ConnectivityDiagnosticsManager.ConnectivityReport,
            ) {
                capturedReport = report
                latch.countDown()
            }

            override fun onDataStallSuspected(
                report: ConnectivityDiagnosticsManager.DataStallReport,
            ) {
                // Not counted down: a stall verdict can arrive before or after the report, and
                // waiting for both would spend the whole budget on a report that already arrived.
                capturedStall = report
                Log.i(TAG, "platform suspects a data stall on ${report.network}")
            }
        }

        return try {
            // A transport type is required for the request to match anything, and which one the
            // device is on is not knowable in advance, so all three the plan cares about are
            // accepted. A device on satellite or a VPN-only network simply gets no report, which
            // the run records rather than inventing one.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            diagnostics.registerConnectivityDiagnosticsCallback(callback, executor, request)
            latch.await(waitMillis, TimeUnit.MILLISECONDS)

            val report = capturedReport
            val stall = capturedStall
            if (report == null && stall == null) {
                Log.i(TAG, "no connectivity report within $waitMillis ms")
                null
            } else {
                toDiagnostics(report, stall)
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "platform connectivity diagnostics failed", throwable)
            null
        } finally {
            runCatching { diagnostics.unregisterConnectivityDiagnosticsCallback(callback) }
            executor.shutdown()
        }
    }

    /** Translates the platform's answer into the engine's neutral model. */
    private fun toDiagnostics(
        report: ConnectivityDiagnosticsManager.ConnectivityReport?,
        stall: ConnectivityDiagnosticsManager.DataStallReport?,
    ): OsDiagnostics {
        val info = report?.additionalInfo
        val link = report?.linkProperties
        val capabilities = report?.networkCapabilities
        val supportsPrivateDns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        return OsDiagnostics(
            reportTimestampMillis = report?.reportTimestamp ?: 0L,
            interfaceName = link?.interfaceName,
            mtu = link?.mtu,
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            privateDnsActive = if (supportsPrivateDns) link?.isPrivateDnsActive else null,
            privateDnsServerName = if (supportsPrivateDns) link?.privateDnsServerName else null,
            transports = transportsOf(capabilities),
            linkDownstreamBandwidthKbps = capabilities?.linkDownstreamBandwidthKbps,
            linkUpstreamBandwidthKbps = capabilities?.linkUpstreamBandwidthKbps,
            validationResult = info?.intOrNull(
                ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_VALIDATION_RESULT,
            ),
            probesAttemptedBitmask = info?.intOrNull(
                ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_PROBES_ATTEMPTED_BITMASK,
            ),
            probesSucceededBitmask = info?.intOrNull(
                ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_PROBES_SUCCEEDED_BITMASK,
            ),
            additionalInfo = info?.asText() ?: emptyMap(),
            dataStall = stall?.let { stallReport ->
                OsDiagnostics.DataStall(
                    timestampMillis = stallReport.timestampMillis,
                    detectionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        stallReport.detectionMethod
                    } else {
                        null
                    },
                    details = stallReport.stallDetails.asText(),
                )
            },
        )
    }

    private fun transportsOf(capabilities: NetworkCapabilities?): List<String> {
        if (capabilities == null) return emptyList()
        val names = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names += "WIFI"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names += "CELLULAR"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names += "ETHERNET"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names += "VPN"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) names += "BLUETOOTH"
        return names
    }

    /** An integer the platform actually provided, or null when the key was absent. */
    private fun PersistableBundle.intOrNull(key: String): Int? =
        if (containsKey(key)) getInt(key, 0) else null

    /**
     * Every key the platform chose to include, as text.
     *
     * Dumping the bundle rather than reading a fixed list of keys means a future platform version
     * adding a probe result shows up in the report instead of being silently dropped.
     */
    private fun PersistableBundle.asText(): Map<String, String> =
        keySet().associateWith { key -> get(key)?.toString() ?: "null" }

    private companion object {
        const val TAG = "ProbeEngine"
    }
}

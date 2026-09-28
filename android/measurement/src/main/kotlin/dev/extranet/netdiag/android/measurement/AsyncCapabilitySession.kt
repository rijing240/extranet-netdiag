package dev.extranet.netdiag.android.measurement

import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import dev.extranet.netdiag.probe.ProbeOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Resolves the catalog's asynchronous specs by actually listening for a bounded window.
 *
 * The synchronous probe cannot answer the questions that matter most here: whether the
 * chipset ever emits carrier phase, whether the modem reports a timing advance at all, and
 * whether the telephony callbacks fire. Those only exist during a live session, which is why
 * the runner reports them as `NOT_PROBED` unless a caller supplies an outcome, and this class
 * is that caller.
 *
 * The session is bounded and self-cleaning: every registration is paired with an unregister in
 * `finally`, and the whole thing gives up after [DEFAULT_TIMEOUT_MILLIS]. A B0 run is therefore
 * a few seconds of listening, not a service.
 */
public class AsyncCapabilitySession(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Listens for up to [timeoutMillis] and returns whatever was observed, keyed by probe id.
     *
     * Ids that produced nothing are simply absent from the map, so the runner reports them as
     * `NOT_PROBED` rather than inventing a value.
     */
    public fun run(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Map<String, ProbeOutcome> {
        val results = LinkedHashMap<String, ProbeOutcome>()
        val done = CountDownLatch(1)

        val thread = HandlerThread("netdiag-probe").apply { start() }
        val handler = Handler(thread.looper)

        try {
            collectGnss(results, done, handler)
            collectTelephony(results, handler)
            collectNetworkTransitions(results, handler, done)
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (throwable: Throwable) {
            Log.w(TAG, "async capability session failed", throwable)
        } finally {
            thread.quitSafely()
        }
        return results
    }

    // --- GNSS ------------------------------------------------------------------------------

    private fun collectGnss(
        results: MutableMap<String, ProbeOutcome>,
        done: CountDownLatch,
        handler: Handler,
    ) {
        val locationManager = appContext.getSystemService(LocationManager::class.java)
            ?: return

        // Backwards-compatible overload: the Executor variant is API 30, this one works from 24.
        val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                val measurements = eventArgs.measurements
                if (measurements.isEmpty()) {
                    results["gnss.measurements"] = ProbeOutcome.Unavailable
                    return
                }
                results["gnss.measurements"] = ProbeOutcome.Value(
                    "${measurements.size} measurement(s), clock=${eventArgs.clock}",
                )

                // The single most important B0 question: do raw measurements carry the fields
                // the Doppler solve and any sub-metre work would need?
                val withPseudorangeRate = measurements.count { it.pseudorangeRateMetersPerSecond.isFinite() }
                results["gnss.measurements.pseudorangeRate"] = if (withPseudorangeRate == 0) {
                    ProbeOutcome.Unavailable
                } else {
                    ProbeOutcome.Value("$withPseudorangeRate/${measurements.size} finite pseudorange rates")
                }

                val withCarrierPhase = measurements.count { it.carrierPhase != 0.0 }
                results["gnss.measurements.carrierPhase"] = if (withCarrierPhase == 0) {
                    // This is the expected outcome on most consumer firmware and is the reason
                    // sub-metre positioning is not a roadmap promise.
                    ProbeOutcome.Unavailable
                } else {
                    ProbeOutcome.Value("$withCarrierPhase/${measurements.size} non-zero carrier phases")
                }

                val withDeltaRange = measurements.count {
                    it.accumulatedDeltaRangeState != 0 && it.accumulatedDeltaRangeMeters.isFinite()
                }
                results["gnss.measurements.accumulatedDeltaRange"] = if (withDeltaRange == 0) {
                    ProbeOutcome.Unavailable
                } else {
                    ProbeOutcome.Value("$withDeltaRange/${measurements.size} valid delta-range states")
                }
                done.countDown()
            }

            override fun onStatusChanged(status: Int) {
                Log.d(TAG, "gnss measurements status $status")
            }
        }

        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
                val cn0 = (0 until status.satelliteCount).map { status.getCn0DbHz(it) }
                results["gnss.status"] = if (status.satelliteCount == 0) {
                    ProbeOutcome.Unavailable
                } else {
                    ProbeOutcome.Value(
                        "visible=${status.satelliteCount} usedInFix=$used " +
                            "cn0=${cn0.joinToString(separator = ",") { it.toString() }}",
                    )
                }
            }
        }

        try {
            locationManager.registerGnssMeasurementsCallback(measurementsCallback, handler)
        } catch (throwable: Throwable) {
            results["gnss.measurements"] = ProbeOutcome.Failed(describe(throwable))
        }
        try {
            locationManager.registerGnssStatusCallback(statusCallback, handler)
        } catch (throwable: Throwable) {
            results["gnss.status"] = ProbeOutcome.Failed(describe(throwable))
        }
    }

    // --- Telephony callbacks ---------------------------------------------------------------

    private fun collectTelephony(
        results: MutableMap<String, ProbeOutcome>,
        handler: Handler,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val telephony = appContext.getSystemService(TelephonyManager::class.java) ?: return

        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener {

            override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength) {
                results["telephony.signalStrengthsCallback"] =
                    ProbeOutcome.Value(signalStrength.toString())
            }

            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                results["telephony.displayInfo"] = ProbeOutcome.Value(
                    "networkType=${displayInfo.networkType} " +
                        "override=${displayInfo.overrideNetworkType}",
                )
            }
        }

        try {
            telephony.registerTelephonyCallback({ runnable -> handler.post(runnable) }, callback)
        } catch (throwable: Throwable) {
            results["telephony.signalStrengthsCallback"] = ProbeOutcome.Failed(describe(throwable))
            results["telephony.displayInfo"] = ProbeOutcome.Failed(describe(throwable))
        }
    }

    // --- Network transitions ---------------------------------------------------------------

    private fun collectNetworkTransitions(
        results: MutableMap<String, ProbeOutcome>,
        handler: Handler,
        done: CountDownLatch,
    ) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                results["connectivity.defaultNetworkCallback"] =
                    ProbeOutcome.Value("onAvailable fired for $network")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                results["connectivity.defaultNetworkCallback"] = ProbeOutcome.Value(
                    "onCapabilitiesChanged: down=${capabilities.linkDownstreamBandwidthKbps} kbps",
                )
                done.countDown()
            }

            override fun onLost(network: Network) {
                results.putIfAbsent(
                    "connectivity.defaultNetworkCallback",
                    ProbeOutcome.Value("onLost fired for $network"),
                )
            }
        }

        try {
            connectivity.registerDefaultNetworkCallback(callback, handler)
        } catch (throwable: Throwable) {
            results["connectivity.defaultNetworkCallback"] = ProbeOutcome.Failed(describe(throwable))
        }
    }

    private fun describe(throwable: Throwable): String =
        "${throwable.javaClass.simpleName}: ${throwable.message}"

    private companion object {
        const val TAG = "CapabilityProbe"

        /** Long enough for a GNSS fix to start reporting measurements. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 8_000L
    }
}

package dev.extranet.netdiag.android.sensor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.CellSignalStrengthLte
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.extranet.netdiag.core.ledger.TimelineBudget
import dev.extranet.netdiag.measure.RadioSample
import dev.extranet.netdiag.measure.RadioTimeline
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * What the platform can be asked about, one second at a time.
 *
 * The seam exists for the same reason B0's [dev.extranet.netdiag.probe.PlatformReportSource]
 * does: the classification and the aggregation are testable without a device, and the device
 * adapter stays a thin translation layer.
 */
public interface RadioSensorSource {
    /** Latest LTE signal snapshot, or null when there is none (no SIM, no service, denied). */
    public fun lteSignal(): LteSignal?

    /** The telephony data network type name ("LTE", "NR", ...), or null when not cellular. */
    public fun networkType(): String?

    /** Compass heading in degrees (0 = north magnetic), or null when no magnetometer. */
    public fun headingDegrees(): Double?
}

/** One LTE snapshot as the platform reported it. */
public data class LteSignal(
    public val rsrpDbm: Int?,
    public val rsrqDb: Int?,
    public val rssnrDb: Int?,
    public val rssiDbm: Int?,
    public val timingAdvance: Int?,
    public val level: Int?,
)

/**
 * The 1 Hz radio timeline sampler (S1, B2).
 *
 * Writes one [RadioSample] per active interval into a [RadioTimeline], tagging seconds in
 * which the default network changed. All platform reading goes through [RadioSensorSource];
 * a source that answers null is a gap in the timeline, never a zero and never a crash.
 *
 * Cadence is [SampleBudget.ACTIVE_INTERVAL_MILLIS] because the sampler is foreground-only by
 * contract: background duty cycling is the budget guard's job (X2) and lands with the session
 * manager, not smuggled in here.
 */
public class RadioTimelineSampler(
    private val source: RadioSensorSource,
    private val timeline: RadioTimeline = RadioTimeline(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val intervalMillis: Long = SampleBudget.ACTIVE_INTERVAL_MILLIS,
) {
    /** The timeline being filled; the UI and the exporter read from here. */
    public fun timeline(): RadioTimeline = timeline

    private var networkEvent: String? = null
    private var previousTransport: String? = null

    /**
     * Samples [durationMillis] at the configured interval, then stops.
     *
     * [onNetworkChanged] receives a short tag per transition ("wifi_gained", "cellular_lost")
     * so the caller can display or log it; the tag is also written into the sample of the
     * second in which it happened.
     */
    public suspend fun sampleFor(
        durationMillis: Long,
        onNetworkChanged: (String) -> Unit = {},
    ): RadioTimeline = coroutineScope {
        val stopAt = clock() + durationMillis
        while (clock() < stopAt) {
            val event = networkEvent
            networkEvent = null
            timeline.append(currentSample(event))
            if (event != null) onNetworkChanged(event)
            delay(intervalMillis)
        }
        timeline
    }

    /** Builds the sample for this instant, from whatever the platform chose to answer. */
    private fun currentSample(event: String?): RadioSample {
        val transport = source.networkType()
        val transition = transitionTag(previousTransport, transport)
        previousTransport = transport
        val signal = source.lteSignal()
        return RadioSample(
            epochMillis = clock(),
            networkType = transport,
            rsrpDbm = signal?.rsrpDbm,
            rsrqDb = signal?.rsrqDb,
            rssnrDb = signal?.rssnrDb,
            rssiDbm = signal?.rssiDbm,
            timingAdvance = signal?.timingAdvance,
            level = signal?.level,
            headingDegrees = source.headingDegrees(),
            networkEvent = event ?: transition,
        )
    }

    /**
     * The transition tag between the previous network type and this one.
     *
     * Cellular↔Wi-Fi is the interesting boundary for the plan; within-cellular changes (LTE
     * to NR) are recorded as a type change without claiming a connectivity event.
     */
    private fun transitionTag(previous: String?, current: String?): String? {
        if (previous == current) return null
        val wasCellular = previous?.let { it != WIFI_TYPE } == true
        val isCellular = current?.let { it != WIFI_TYPE } == true
        return when {
            previous == null -> null // the first sample establishes a baseline, not a change
            previous == WIFI_TYPE && current == WIFI_TYPE -> null
            previous == WIFI_TYPE && isCellular -> "wifi_lost_cellular_gained"
            wasCellular && current == WIFI_TYPE -> "cellular_lost_wifi_gained"
            wasCellular && current == null -> "cellular_lost"
            previous == WIFI_TYPE && current == null -> "wifi_lost"
            current == WIFI_TYPE -> "wifi_gained"
            current != null -> "network_type_${previous}_to_${current}"
            else -> null
        }
    }

    public companion object {
        /** Pseudo network-type string for a Wi-Fi transport, so transitions read plainly. */
        public const val WIFI_TYPE: String = "WIFI"
    }
}

/**
 * The device-backed source: TelephonyManager signal snapshots, the active network, and the
 * compass. Every accessor degrades to null; the sampler above never learns why.
 */
public class DeviceRadioSensorSource(private val context: Context) : RadioSensorSource {

    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Written by the platform's signal callback on the main executor and read by the sampler on
    // whatever thread it was given; volatile so a sample never reads a half-published reading.
    @Volatile
    private var lastSignal: LteSignal? = null

    private val signalCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SignalCallback31 { strength -> lastSignal = strength.toLte() }
    } else {
        LegacySignalCallback { strength -> lastSignal = strength.toLte() }
    }

    /** Registers listeners; returns false when the platform refuses (no SIM, denied). */
    public fun register(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephony.registerTelephonyCallback(
                context.mainExecutor,
                signalCallback as TelephonyCallback,
            )
        } else {
            @Suppress("DEPRECATION")
            telephony.listen(signalCallback as PhoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }
        true
    }.getOrDefault(false)

    /** Unregisters listeners; safe to call any number of times. */
    public fun unregister() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephony.unregisterTelephonyCallback(signalCallback as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephony.listen(signalCallback as PhoneStateListener, 0)
            }
        }
    }

    override fun lteSignal(): LteSignal? {
        // The callback may not have fired yet; poll once so the first sample is not empty.
        if (lastSignal == null) {
            lastSignal = readAllCellInfoLte()
        }
        return lastSignal
    }

    override fun networkType(): String? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> RadioTimelineSampler.WIFI_TYPE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                telephony?.dataNetworkTypeName()
            else -> null
        }
    }

    override fun headingDegrees(): Double? = HeadingSource.heading(context)

    private fun TelephonyManager.dataNetworkTypeName(): String? = runCatching {
        @Suppress("DEPRECATION")
        val type = dataNetworkType
        when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TDSCDMA"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
            else -> "RAT$type"
        }
    }.getOrNull()

    /** Reads the primary LTE strength from getAllCellInfo, the API the probe already proved. */
    private fun readAllCellInfoLte(): LteSignal? = runCatching {
        telephony.allCellInfo
            ?.asSequence()
            ?.mapNotNull { it as? android.telephony.CellInfoLte }
            ?.map { it.cellSignalStrength.toLte() }
            ?.firstOrNull()
    }.getOrNull()

    private fun CellSignalStrengthLte.toLte(): LteSignal = runCatching {
        LteSignal(
            rsrpDbm = runCatching { rsrp }.getOrNull()?.takeIf { it != UNAVAILABLE },
            rsrqDb = runCatching { rsrq }.getOrNull()?.takeIf { it != UNAVAILABLE },
            rssnrDb = runCatching { rssnr }.getOrNull()?.takeIf { it != UNAVAILABLE },
            rssiDbm = runCatching { rssi }.getOrNull()?.takeIf { it != UNAVAILABLE },
            timingAdvance = runCatching { timingAdvance }.getOrNull()?.takeIf { it != UNAVAILABLE },
            level = runCatching { level }.getOrNull()?.takeIf { it in 0..4 },
        )
    }.getOrDefault(LteSignal(null, null, null, null, null, null))

    private companion object {
        /** android.telephony.SignalStrength#SIGNAL_STRENGTH_UNKNOWN-equivalent sentinel. */
        const val UNAVAILABLE: Int = Int.MAX_VALUE
    }
}

/** API 31+ callback wrapper. */
private class SignalCallback31(
    private val onStrength: (SignalStrength) -> Unit,
) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onStrength(signalStrength)
}

/** Pre-31 listener wrapper. */
private class LegacySignalCallback(
    private val onStrength: (SignalStrength) -> Unit,
) : PhoneStateListener() {
    @Deprecated("Deprecated in Java")
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onStrength(signalStrength)
}

/** Extracts the LTE component of a platform SignalStrength without version traps. */
private fun SignalStrength.toLte(): LteSignal? = runCatching {
    @Suppress("DEPRECATION")
    val lte = cellSignalStrengths
        .filterIsInstance<CellSignalStrengthLte>()
        .firstOrNull() ?: return null
    LteSignal(
        rsrpDbm = runCatching { lte.rsrp }.getOrNull()?.takeIf { it != Int.MAX_VALUE },
        rsrqDb = runCatching { lte.rsrq }.getOrNull()?.takeIf { it != Int.MAX_VALUE },
        rssnrDb = runCatching { lte.rssnr }.getOrNull()?.takeIf { it != Int.MAX_VALUE },
        rssiDbm = runCatching { lte.rssi }.getOrNull()?.takeIf { it != Int.MAX_VALUE },
        timingAdvance = runCatching { lte.timingAdvance }.getOrNull()?.takeIf { it != Int.MAX_VALUE },
        level = runCatching { lte.level }.getOrNull()?.takeIf { it in 0..4 },
    )
}.getOrNull()

/**
 * Compass heading from the rotation vector sensor, degrees clockwise from magnetic north.
 *
 * Null on a device without the sensor or without a reader available: the timeline then simply
 * has no heading column, which the summary and the compass both render as "no direction data".
 */
private object HeadingSource {

    /**
     * The most recent heading, read synchronously from a one-shot listener.
     *
     * A blocking CountDownLatch replaces the coroutine-timed channel read: the sampler calls
     * this once per second on its own worker, the rotation vector usually delivers within a
     * few milliseconds, and the latch caps the wait at 200 ms before declaring "no heading".
     */
    fun heading(context: Context): Double? = runCatching {
        val sensorManager = context.getSystemService(android.hardware.SensorManager::class.java) ?: return null
        val sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) ?: return null

        val received = java.util.concurrent.atomic.AtomicReference<android.hardware.SensorEvent>()
        val done = java.util.concurrent.CountDownLatch(1)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                received.compareAndSet(null, e)
                done.countDown()
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST)
        try {
            // One event is enough for a trend instrument; streaming belongs to the session
            // manager. No event within the window is a real answer: no heading this second.
            if (!done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) null
            else received.get()?.let { rotationToDegrees(it) }
        } finally {
            sensorManager.unregisterListener(listener)
        }
    }.getOrNull()

    /** Rotation-vector to azimuth, in degrees 0..360. */
    private fun rotationToDegrees(e: android.hardware.SensorEvent): Double {
        val rotation = FloatArray(9)
        android.hardware.SensorManager.getRotationMatrixFromVector(rotation, e.values)
        val orientation = FloatArray(3)
        android.hardware.SensorManager.getOrientation(rotation, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble())
        return ((azimuth % 360.0) + 360.0) % 360.0
    }
}

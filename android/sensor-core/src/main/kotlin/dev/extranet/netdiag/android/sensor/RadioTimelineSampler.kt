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

    /**
     * The active link's strength snapshot, or null when there is none (no service, denied).
     *
     * "Active" means the network the phone is actually using: on Wi-Fi this is the Wi-Fi
     * link's RSSI, because the cell's reading would describe a network no data is flowing
     * through - which is exactly how a SIM-less tablet, or a phone idling on Wi-Fi, ended up
     * with a screen full of gaps.
     */
    public fun linkSignal(): LinkSignal?

    /** The active network type name ("WIFI", "LTE", "WCDMA", ...), or null when unknown. */
    public fun networkType(): String?

    /** Compass heading in degrees (0 = north magnetic), or null when no magnetometer. */
    public fun headingDegrees(): Double?
}

/**
 * One strength snapshot as the platform reported it.
 *
 * [rsrpDbm] carries the active link's strength in dBm whatever the technology: RSRP on LTE,
 * the technology's own dBm reading elsewhere (WCDMA, GSM, NR), and the Wi-Fi RSSI when the
 * phone is on Wi-Fi. The narrower columns ([rsrqDb], [rssnrDb], [timingAdvance]) stay LTE-only
 * and are null on every other link, because inventing their equivalents would dress one
 * technology's numbers in another's names.
 */
public data class LinkSignal(
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
        val signal = source.linkSignal()
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
    private var lastSignal: LinkSignal? = null

    private val signalCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SignalCallback31 { strength -> lastSignal = strength.toActiveLink() }
    } else {
        LegacySignalCallback { strength -> lastSignal = strength.toActiveLink() }
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

    override fun linkSignal(): LinkSignal? {
        // The active network decides what "signal" even means here: a Wi-Fi phone's cell
        // reading would describe a link nothing is using, so it is not a fallback, it is a
        // different network's answer.
        if (isWifi()) {
            return wifiSignal()
        }
        // The callback may not have fired yet; poll once so the first sample is not empty.
        if (lastSignal == null) {
            lastSignal = readAllCellInfoStrength()
        }
        return lastSignal
    }

    /** True when the network data is flowing over right now is Wi-Fi. */
    private fun isWifi(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /**
     * The Wi-Fi link's strength: RSSI in dBm and the platform's 0..4 level.
     *
     * Nulls are honest per column - a right-to-left read of a router's signal is still a
     * reading; "unknown" in a column the platform did not report is not.
     */
    private fun wifiSignal(): LinkSignal? = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching null
        val rssi = connectivity
            .getNetworkCapabilities(network)
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?.signalStrength
            ?: return@runCatching null
        // The capabilities RSSI is signed dBm already (e.g. -52); anything at the 0..4 scale
        // the older WifiManager path produced would be a level, not a dBm, and is refused.
        if (rssi > 0) return@runCatching null
        LinkSignal(
            rsrpDbm = rssi,
            rsrqDb = null,
            rssnrDb = null,
            rssiDbm = null,
            timingAdvance = null,
            level = wifiLevelOf(rssi),
        )
    }.getOrNull()

    /** The Wi-Fi scale in plain terms: above -50 is excellent, below -85 is the edge. */
    private fun wifiLevelOf(rssi: Int): Int? = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
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

    /**
     * The pre-first-callback reading.
     *
     * LTE comes from getAllCellInfo because that is where its five columns live. The
     * any-technology fallback reads the primary [SignalStrength] instead of parsing more
     * CellInfo subclasses: their per-technology classes do not all exist at minSdk 26, and a
     * class literal for one that does not loads no class and answers nothing on an older phone.
     */
    private fun readAllCellInfoStrength(): LinkSignal? = runCatching {
        val infos = telephony.allCellInfo ?: return@runCatching null
        infos.filterIsInstance<android.telephony.CellInfoLte>()
            .firstOrNull()
            ?.let { it.cellSignalStrength.toLte() }
    }.getOrNull()
        ?: runCatching {
            @Suppress("DEPRECATION")
            telephony.signalStrength?.toActiveLink()
        }.getOrNull()

    private fun CellSignalStrengthLte.toLte(): LinkSignal = runCatching {
        LinkSignal(
            rsrpDbm = runCatching { rsrp }.getOrNull()?.takeIf { it != UNAVAILABLE },
            rsrqDb = runCatching { rsrq }.getOrNull()?.takeIf { it != UNAVAILABLE },
            rssnrDb = runCatching { rssnr }.getOrNull()?.takeIf { it != UNAVAILABLE },
            rssiDbm = runCatching { rssi }.getOrNull()?.takeIf { it != UNAVAILABLE },
            timingAdvance = runCatching { timingAdvance }.getOrNull()?.takeIf { it != UNAVAILABLE },
            level = runCatching { level }.getOrNull()?.takeIf { it in 0..4 },
        )
    }.getOrDefault(LinkSignal(null, null, null, null, null, null))

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

/**
 * The primary technology's reading from a platform SignalStrength, without version traps.
 *
 * Every entry of `cellSignalStrengths` speaks the same three numbers at minSdk - level, dBm
 * and, on LTE, the wider columns - so the read dispatches on nothing: the first entry carrying
 * a real reading wins, and its dBm lands in [LinkSignal.rsrpDbm]. Naming the per-technology
 * subclasses (WCDMA, NR, TD-SCDMA) would reference classes above minSdk and throw on the
 * phones they were meant to include. The dBm column means "the active link's strength in dBm",
 * which is what every consumer of a sample already reads.
 */
private fun SignalStrength.toActiveLink(): LinkSignal? = runCatching {
    val entry = cellSignalStrengths.firstOrNull { strength ->
        val level = runCatching { strength.level }.getOrNull()
        val dbm = runCatching { strength.dbm }.getOrNull()
        (level != null && level in 0..4) || (dbm != null && dbm != Int.MAX_VALUE)
    } ?: return@runCatching null
    val isLte = entry is CellSignalStrengthLte
    LinkSignal(
        rsrpDbm = runCatching { entry.dbm }.getOrNull()?.takeIf { it != Int.MAX_VALUE },
        rsrqDb = if (isLte) {
            runCatching { (entry as CellSignalStrengthLte).rsrq }.getOrNull()?.takeIf { it != Int.MAX_VALUE }
        } else null,
        rssnrDb = if (isLte) {
            runCatching { (entry as CellSignalStrengthLte).rssnr }.getOrNull()?.takeIf { it != Int.MAX_VALUE }
        } else null,
        rssiDbm = if (isLte) {
            runCatching { (entry as CellSignalStrengthLte).rssi }.getOrNull()?.takeIf { it != Int.MAX_VALUE }
        } else null,
        timingAdvance = if (isLte) {
            runCatching { (entry as CellSignalStrengthLte).timingAdvance }.getOrNull()
                ?.takeIf { it != Int.MAX_VALUE }
        } else null,
        level = runCatching { entry.level }.getOrNull()?.takeIf { it in 0..4 },
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

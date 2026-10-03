package dev.extranet.netdiag.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The signal strength the radar steers by, in one unit, whatever the phone is connected to.
 *
 * The timeline reads LTE only, which leaves a phone on 3G, 5G or Wi-Fi with nothing to draw. This
 * feed takes the strength in dBm from whichever link is carrying the data: the cell's own report
 * for mobile data (any generation), the Wi-Fi link's RSSI for Wi-Fi. The two are not on the same
 * scale, and they do not need to be: the radar only compares readings from one session against
 * each other, so what matters is that every reading in it came from the same kind of link.
 *
 * Each reading carries a freshness, because the radio reports a new strength only about once a
 * second and sometimes much more slowly. A reading that arrived just now describes the direction
 * the phone is facing now; one that arrived three seconds ago describes wherever the phone was
 * then. The radar weights by freshness, which is how it stays accurate while the heading is read
 * sixty times a second and the signal is not.
 */
public class RadioStrengthFeed(context: Context) {

    /** The kind of link the strength came from. */
    public enum class Kind { CELLULAR, WIFI, NONE }

    /** One strength reading and how much to trust it. */
    public data class Reading(
        public val dbm: Int,
        public val kind: Kind,
        /** 1.0 for a reading that has just arrived, falling to 0.15 as it ages. */
        public val freshness: Double,
        public val ageMillis: Long,
    )

    /**
     * One report from the radio, stamped when it arrived.
     *
     * This is the radar's unit of evidence. A reading that merely stays on screen between
     * reports is not a new measurement, so only a report that is new is counted: a changed
     * value, or the same value re-reported after a pause. The radar files each one under the
     * heading the phone had when the radio measured it, not under every heading it passes
     * while the number sits there.
     */
    public data class Measurement(
        public val timeMillis: Long,
        public val dbm: Int,
        public val kind: Kind,
    )

    private val appContext: Context = context.applicationContext
    private val telephony: TelephonyManager? = appContext.getSystemService(TelephonyManager::class.java)
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    private val lock = Any()
    private var callback: Any? = null

    private var cellDbm: Int? = null
    private var cellUpdatedAt = 0L
    private val cellUpdates = ArrayDeque<Long>()

    private var wifiDbm: Int? = null
    private var wifiLastPoll = 0L
    private val wifiPolls = ArrayDeque<Long>()

    private var kindCache = Kind.NONE
    private var kindCheckedAt = 0L

    /** The smoothed reporting interval, and the link it belongs to. Zero until first measured. */
    private var smoothedIntervalMillis = 0.0
    private var intervalKind: Kind? = null

    private val events = ConcurrentLinkedQueue<Measurement>()
    private val cellEventTimes = ArrayDeque<Long>()
    private val wifiEventTimes = ArrayDeque<Long>()
    private var lastCellEventDbm = Int.MIN_VALUE
    private var lastCellEventAt = 0L
    private var lastWifiEventDbm = Int.MIN_VALUE
    private var lastWifiEventAt = 0L

    /** Starts listening to the radio; safe to call when permissions are missing, it just stays empty. */
    public fun start() {
        val manager = telephony ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = StrengthCallback31 { strength -> onStrength(strength) }
                callback = listener
                manager.registerTelephonyCallback(appContext.mainExecutor, listener)
            } else {
                val listener = StrengthListenerLegacy { strength -> onStrength(strength) }
                callback = listener
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            }
        }
        // Seed with the platform's cached value so the first frame is not empty.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.signalStrength?.let { onStrength(it) } }
        }
    }

    /** Stops listening; safe to call any number of times. */
    public fun stop() {
        val manager = telephony
        val active = callback
        callback = null
        if (manager == null || active == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.unregisterTelephonyCallback(active as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                manager.listen(active as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    /** The current strength from the link carrying the data, or null when there is none. */
    public fun read(): Reading? {
        val now = SystemClock.elapsedRealtime()
        return when (activeKind(now)) {
            Kind.CELLULAR -> synchronized(lock) {
                cellDbm?.let { dbm ->
                    val age = now - cellUpdatedAt
                    Reading(dbm, Kind.CELLULAR, freshnessOf(age), age)
                }
            }
            Kind.WIFI -> readWifi(now)
            Kind.NONE -> null
        }
    }

    /**
     * The new reports since the last call, oldest first. Called every frame by the engine; the
     * queue is bounded, so a screen that stops asking cannot make it grow.
     */
    public fun drainMeasurements(): List<Measurement> {
        if (events.isEmpty()) return emptyList()
        val drained = ArrayList<Measurement>()
        while (true) {
            drained.add(events.poll() ?: break)
        }
        return drained
    }

    /**
     * How long the link carrying the data takes between new reports, in milliseconds: the median
     * of the last few gaps, so one late report does not move it. Null until three reports have
     * arrived. This is the number that decides how slowly a person must turn: a phone cannot
     * measure directions faster than the radio reports them.
     */
    public fun reportIntervalMillis(): Long? {
        val now = SystemClock.elapsedRealtime()
        val kind = activeKind(now)
        return synchronized(lock) {
            if (kind != intervalKind) {
                intervalKind = kind
                smoothedIntervalMillis = 0.0
            }
            val times = if (kind == Kind.WIFI) wifiEventTimes else cellEventTimes
            while (times.isNotEmpty() && now - times.first() > INTERVAL_WINDOW_MILLIS) times.removeFirst()
            if (times.size < MIN_INTERVAL_SAMPLES) return null
            val span = times.last() - times.first()
            val observed = if (span > 0L) span.toDouble() / (times.size - 1) else 0.0
            if (observed <= 0.0) return null
            smoothedIntervalMillis = if (smoothedIntervalMillis <= 0.0) {
                observed
            } else {
                smoothedIntervalMillis + INTERVAL_SMOOTHING * (observed - smoothedIntervalMillis)
            }
            smoothedIntervalMillis.toLong().coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
        }
    }

    /**
     * How long before a report arrived the radio was measuring what it describes, in
     * milliseconds. A cell averages its reference signal over a few hundred milliseconds and
     * smooths it again before reporting; Wi-Fi hardware smooths less. These are estimates, which
     * is why the radar adds their uncertainty to its error bar instead of pretending they are
     * exact.
     */
    public fun latencyMillis(kind: Kind): Long = when (kind) {
        Kind.CELLULAR -> CELLULAR_LATENCY_MILLIS
        Kind.WIFI -> WIFI_LATENCY_MILLIS
        Kind.NONE -> 0L
    }

    private fun pushEvent(event: Measurement) {
        events.add(event)
        while (events.size > MAX_QUEUED_EVENTS) events.poll()
        val times = if (event.kind == Kind.WIFI) wifiEventTimes else cellEventTimes
        times.addLast(event.timeMillis)
        while (times.size > 12) times.removeFirst()
    }

    /** How often this feed produces a usable reading, per second.
     *
     * For Wi-Fi that is the poll rate, not the rate the number changes: an unchanged RSSI is
     * still the radio's current answer, and turning-speed advice keyed to how often the value
     * happens to move would set the limit near zero.
     */
    public fun updatesPerSecond(): Double {
        val now = SystemClock.elapsedRealtime()
        val updates = if (activeKind(now) == Kind.WIFI) wifiPolls else cellUpdates
        return synchronized(lock) {
            while (updates.isNotEmpty() && now - updates.first() > WINDOW_MILLIS) updates.removeFirst()
            updates.size * 1_000.0 / WINDOW_MILLIS
        }
    }

    private fun onStrength(strength: SignalStrength) {
        val dbm = strength.primaryDbm() ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            cellDbm = dbm
            cellUpdatedAt = now
            cellUpdates.addLast(now)
            while (cellUpdates.isNotEmpty() && now - cellUpdates.first() > WINDOW_MILLIS) cellUpdates.removeFirst()
            if (dbm != lastCellEventDbm || now - lastCellEventAt >= MIN_EVENT_GAP_MILLIS) {
                lastCellEventDbm = dbm
                lastCellEventAt = now
                pushEvent(Measurement(now, dbm, Kind.CELLULAR))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifi(now: Long): Reading? {
        if (now - wifiLastPoll >= WIFI_POLL_MILLIS) {
            wifiLastPoll = now
            val rssi = runCatching { wifi?.connectionInfo?.rssi }.getOrNull()
            if (rssi != null && rssi in -127..-1) {
                synchronized(lock) {
                    if (rssi != lastWifiEventDbm || now - lastWifiEventAt >= WIFI_REPEAT_MILLIS) {
                        lastWifiEventDbm = rssi
                        lastWifiEventAt = now
                        pushEvent(Measurement(now, rssi, Kind.WIFI))
                    }
                    wifiDbm = rssi
                    wifiPolls.addLast(now)
                    while (wifiPolls.isNotEmpty() && now - wifiPolls.first() > WINDOW_MILLIS) wifiPolls.removeFirst()
                }
            }
        }
        val dbm = synchronized(lock) { wifiDbm } ?: return null
        // A Wi-Fi link reports its strength when asked, so an unchanged value is still current.
        return Reading(dbm, Kind.WIFI, WIFI_FRESHNESS, 0L)
    }

    private fun activeKind(now: Long): Kind {
        if (now - kindCheckedAt > KIND_CACHE_MILLIS) {
            kindCheckedAt = now
            kindCache = runCatching {
                val capabilities = connectivity?.let { manager ->
                    manager.activeNetwork?.let { network -> manager.getNetworkCapabilities(network) }
                }
                when {
                    capabilities == null -> Kind.NONE
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.CELLULAR
                    else -> Kind.NONE
                }
            }.getOrDefault(Kind.NONE)
        }
        return kindCache
    }

    private fun freshnessOf(ageMillis: Long): Double =
        (1.0 - (ageMillis - FRESH_FOR_MILLIS) / FADE_OVER_MILLIS).coerceIn(MIN_FRESHNESS, 1.0)

    /** The strength of the cell carrying the data, in dBm, on any generation of mobile network. */
    private fun SignalStrength.primaryDbm(): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (cell in cellSignalStrengths) {
                val dbm = cell.dbm
                if (dbm in -140..-30) return dbm
            }
            return null
        }
        @Suppress("DEPRECATION")
        val asu = gsmSignalStrength
        return if (asu in 0..31) -113 + 2 * asu else null
    }

    private companion object {
        const val WINDOW_MILLIS: Long = 10_000L
        const val WIFI_POLL_MILLIS: Long = 250L
        const val KIND_CACHE_MILLIS: Long = 500L
        const val WIFI_FRESHNESS: Double = 0.8

        /** A reading this young counts in full. */
        const val FRESH_FOR_MILLIS: Double = 1_000.0

        /** Over this much further ageing the weight falls from 1 to its floor. */
        const val FADE_OVER_MILLIS: Double = 2_500.0
        const val MIN_FRESHNESS: Double = 0.15

        /** Reports closer together than this with an unchanged value are one report, not two. */
        const val MIN_EVENT_GAP_MILLIS: Long = 500L

        /**
         * An unchanged Wi-Fi value this long after the last report counts as a new report.
         *
         * A Wi-Fi link does not push strengths at us; we ask `connectionInfo` for the driver's
         * latest reading, every quarter of a second. Two identical answers are therefore one
         * measurement, not two, and counting them as two would fill the radar with copies of a
         * single number. This is how long after one we treat an identical value as a fresh look
         * at the link: long enough that the driver has had time to update, short enough that a
         * phone sitting in a steady spot still yields about one measurement a second. At three
         * seconds, as this was, the radar was being told the radio reports three times a second
         * slower than it does and the turning pace it asked for was half what a person can
         * actually manage.
         */
        const val WIFI_REPEAT_MILLIS: Long = 900L

        const val INTERVAL_WINDOW_MILLIS: Long = 20_000L
        const val MIN_INTERVAL_MILLIS: Long = 400L
        const val MAX_INTERVAL_MILLIS: Long = 5_000L

        /** Reports needed before the feed will estimate how often the link reports at all. */
        const val MIN_INTERVAL_SAMPLES: Int = 4

        /**
         * Weight of each new gap observation in the smoothed reporting interval.
         *
         * Low on purpose: the limit this produces is advice shown while someone is turning, and
         * advice that moves while it is being followed is worse than advice that is slightly
         * behind a change in the link. One sixth of the way per observation reaches a new rate
         * in a handful of readings, which is well inside a scan.
         */
        const val INTERVAL_SMOOTHING: Double = 0.15
        const val MAX_QUEUED_EVENTS: Int = 64

        /** Centre of the delay between the radio measuring and the app hearing about it. */
        const val CELLULAR_LATENCY_MILLIS: Long = 700L
        const val WIFI_LATENCY_MILLIS: Long = 500L
    }
}

/** API 31+ signal listener. Only instantiated behind a version check. */
private class StrengthCallback31(
    private val onStrength: (SignalStrength) -> Unit,
) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onStrength(signalStrength)
}

/** Pre-31 signal listener. */
private class StrengthListenerLegacy(
    private val onStrength: (SignalStrength) -> Unit,
) : PhoneStateListener() {
    @Deprecated("Deprecated in Java")
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onStrength(signalStrength)
}

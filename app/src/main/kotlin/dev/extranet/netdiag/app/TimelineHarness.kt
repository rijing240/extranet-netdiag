package dev.extranet.netdiag.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import dev.extranet.netdiag.android.sensor.DeviceRadioSensorSource
import dev.extranet.netdiag.android.sensor.RadioTimelineSampler
import dev.extranet.netdiag.measure.MiniThroughputProbe
import dev.extranet.netdiag.measure.RadioTimeline
import dev.extranet.netdiag.measure.SignalCompass
import dev.extranet.netdiag.measure.TimelineCsv
import dev.extranet.netdiag.measure.TwoHopProbe
import java.net.InetAddress

/**
 * One observable sampling session, from start to exported log.
 *
 * Like the B0/B1 harnesses, this is the single entry point shared by the screen and the
 * instrumented test, so what the test proves is what the screen shows.
 */
public class TimelineSession(
    private val context: Context,
    public val timeline: RadioTimeline = RadioTimeline(),
) {
    private var sensorSource: DeviceRadioSensorSource? = null
    private var sampler: RadioTimelineSampler? = null

    /** Registers platform listeners; false when telephony refused (no SIM or denied). */
    public fun start(): Boolean {
        val source = DeviceRadioSensorSource(context.applicationContext)
        val registered = source.register()
        sensorSource = source
        sampler = RadioTimelineSampler(source, timeline)
        return registered
    }

    /** Samples for [durationMillis] at the 1 Hz cap; suspends the caller. */
    public suspend fun sample(durationMillis: Long, onNetworkChanged: (String) -> Unit = {}): RadioTimeline {
        val active = sampler ?: error("call start() before sample()")
        return active.sampleFor(durationMillis, onNetworkChanged)
    }

    /** Unregisters listeners; safe to call repeatedly. */
    public fun stop() {
        sensorSource?.unregister()
        sensorSource = null
        sampler = null
    }

    /** The compass verdict over the timeline so far. */
    public fun compassVerdict(): SignalCompass.Verdict = SignalCompass.verdict(timeline.snapshot())

    /**
     * The two-hop attribution: the real first hop when the platform exposes one, otherwise the
     * probe is told it has no first hop to test rather than being handed a stand-in.
     */
    public fun twoHopVerdict(): TwoHopProbe.Verdict {
        val probe = TwoHopProbe()
        return probe.probe(gatewayAddress = firstHopAddress(), internetHost = INTERNET_PROBE_HOST)
    }

    /** The mini-throughput reality check. */
    public fun throughputCheck(): MiniThroughputProbe.Result = MiniThroughputProbe().probe()

    /** The session log as CSV, ready to share. */
    public fun exportCsv(): String = TimelineCsv.export(timeline.snapshot())

    /**
     * The address of the first hop, or null when this network does not expose one.
     *
     * The default route's gateway is the first hop; nothing else is. A DNS server is only a
     * stand-in on Wi-Fi, where the router is nearly always the resolver on the local subnet.
     * On mobile data a resolver belongs to the carrier and sits *past* the first hop, so probing
     * it and calling the answer "your router" blames a box the user does not own - which is what
     * the B3 device run showed: 10.206.136.54 was the carrier's resolver, not a local gateway.
     *
     * Returning null is a real answer here. [TwoHopProbe] reports the hop as untested, the
     * inference layer turns that into Unknown rather than Offline, and the screen says the local
     * link was never asked instead of inventing a verdict about it.
     */
    private fun firstHopAddress(): InetAddress? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val properties: LinkProperties = connectivity.getLinkProperties(network) ?: return null

        val defaultRoute = properties.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }
        defaultRoute?.gateway?.let { return it }

        val onWifi = connectivity.getNetworkCapabilities(network)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        when {
            !onWifi -> null
            // Android hides the literal gateway on some Wi-Fi networks too. The resolver there is
            // the router in the overwhelming majority of home and hotspot networks, so it stays
            // a defensible stand-in - and the evidence on screen names the address either way.
            else -> properties.dnsServers.firstOrNull()
        }
    }.getOrNull()

    private companion object {
        /** The internet-side host the two-hop probe compares the gateway against. */
        const val INTERNET_PROBE_HOST: String = "connectivitycheck.gstatic.com"
    }
}

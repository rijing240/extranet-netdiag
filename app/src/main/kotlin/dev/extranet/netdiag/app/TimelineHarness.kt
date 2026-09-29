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
     * The two-hop attribution: gateway from the active network's link properties when the
     * platform offers one, else the routing-table guess.
     */
    public fun twoHopVerdict(): TwoHopProbe.Verdict {
        val probe = TwoHopProbe()
        val gateway = gatewayAddress() ?: TwoHopProbe.discoverGatewayAddress()
        return probe.probe(gateway = gateway, internetHost = INTERNET_PROBE_HOST)
    }

    /** The mini-throughput reality check. */
    public fun throughputCheck(): MiniThroughputProbe.Result = MiniThroughputProbe().probe()

    /** The session log as CSV, ready to share. */
    public fun exportCsv(): String = TimelineCsv.export(timeline.snapshot())

    private fun gatewayAddress(): InetAddress? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val properties: LinkProperties = connectivity.getLinkProperties(network) ?: return null
        // LinkProperties does not expose the literal gateway on modern Android; a name server
        // on the local subnet is the practical first-hop stand-in when dhcpGateway is absent.
        properties.dnsServers.firstOrNull()
    }.getOrNull()

    private companion object {
        /** The internet-side host the two-hop probe compares the gateway against. */
        const val INTERNET_PROBE_HOST: String = "connectivitycheck.gstatic.com"
    }
}

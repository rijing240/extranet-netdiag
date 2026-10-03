package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget
import dev.extranet.netdiag.core.ledger.TimelineBudget
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * The two-hop probe: is the local link at fault, or the road beyond it?
 *
 * "Full Wi-Fi bars, no internet" has exactly two suspects: the first hop (the router or the
 * hotspot providing the network) and everything after it (the carrier, the backbone, the
 * target). The probe separates them by timing TCP connects to two destinations that differ
 * only in hop: the gateway, which sits on the local link, and a public internet host. When the
 * gateway answers and the internet host does not, the local link has demonstrated health and
 * the fault lies beyond it - and vice versa.
 *
 * TCP connect rather than ICMP: Android forbids unprivileged ICMP without a native helper, a
 * connect to the gateway's listening port measures the same first hop, and a refused
 * connection is still a *reachability* answer (the packet made the round trip).
 *
 * **With an echo fallback, because a cellular gateway has nothing listening.** On mobile data
 * the first hop is the carrier's own router, which does not serve port 80 to anybody. Probing
 * only that port produced a guaranteed silence on every cellular phone, and since a silence
 * cannot be told apart from a failure the hop was reported as unmeasured - "Unknown" on a
 * perfectly working connection, and a hole in the middle of the path diagram. When the port
 * probe gets nothing, the hop is asked with `InetAddress.isReachable`, which uses an echo
 * request where the platform allows one and which routers - including carrier gateways -
 * generally answer. It is a weaker answer (the hop is alive, but no round trip was timed), so
 * it is recorded as such rather than being passed off as a timed connect.
 */
public class TwoHopProbe(
    private val clock: () -> Long = { System.nanoTime() },
) {
    /** Result of one round against one hop. */
    public data class HopResult(
        public val address: String,
        public val reachable: Boolean,
        public val latencyMillis: Long?,
        /**
         * True when the hop answered an echo request rather than its port. It is alive, but no
         * round trip was timed, so [latencyMillis] is null and the evidence must not invent one.
         */
        public val answeredEcho: Boolean = false,
    )

    /** The verdict after all rounds. */
    public data class Verdict(
        public val gateway: HopResult,
        public val internet: HopResult,
        public val gatewayMedianMillis: Long?,
        public val internetMedianMillis: Long?,
    ) {
        /** The plain-language answer to "who is to blame". */
        public fun statement(): String = when {
            gateway.reachable && !internet.reachable ->
                "The local link is healthy; the fault is beyond the gateway (carrier or upstream)."
            !gateway.reachable && !internet.reachable ->
                "The local link itself is unreachable. Start with the router or hotspot."
            !gateway.reachable && internet.reachable ->
                "Unusual: the gateway did not answer but the internet did. The first hop may filter probes."
            else -> {
                val local = gatewayMedianMillis ?: 0L
                val remote = internetMedianMillis ?: 0L
                "Both hops are up. First hop $local ms, internet $remote ms beyond it."
            }
        }
    }

    /**
     * Runs the probe.
     *
     * @param gatewayAddress the active network's gateway, resolved by the caller from
     *   `LinkProperties`/`DhcpInfo`; null means no gateway could be determined, in which case
     *   the first hop is reported unreachable with that fact preserved in the address field.
     * @param internetHost a public host to compare against, e.g. the waterfall's usual target.
     * @param internetPort the port to connect to on the internet host.
     */
    public fun probe(
        gatewayAddress: InetAddress?,
        internetHost: String,
        internetPort: Int = MeasurementBudget.HTTPS_PORT,
        rounds: Int = TimelineBudget.TWO_HOP_ROUNDS,
    ): Verdict {
        val gatewayResults = (1..rounds).map { _ ->
            probeHop(gatewayAddress?.hostAddress ?: UNKNOWN_GATEWAY_ADDRESS, gatewayAddress, TimelineBudget.GATEWAY_PROBE_TIMEOUT_MILLIS)
        }
        val internetResults = (1..rounds).map { _ ->
            probeHop(internetHost, resolve(internetHost), TimelineBudget.INTERNET_PROBE_TIMEOUT_MILLIS, internetPort)
        }

        val gateway = aggregate(gatewayResults)
        val internet = aggregate(internetResults)

        return Verdict(
            gateway = gateway,
            internet = internet,
            gatewayMedianMillis = median(gatewayResults.mapNotNull { it.latencyMillis }),
            internetMedianMillis = median(internetResults.mapNotNull { it.latencyMillis }),
        )
    }

    private fun resolve(host: String): InetAddress? =
        runCatching { InetAddress.getByName(host) }.getOrNull()

    private fun probeHop(
        addressText: String,
        address: InetAddress?,
        timeoutMillis: Int,
        port: Int = TimelineBudget.LOCAL_HOP_PORT,
    ): HopResult {
        if (address == null) return HopResult(addressText, reachable = false, latencyMillis = null)
        val start = clock()
        val socket = runCatching { Socket() }.getOrNull() ?: return HopResult(addressText, reachable = false, latencyMillis = null)
        return try {
            socket.connect(java.net.InetSocketAddress(address, port), timeoutMillis)
            HopResult(addressText, reachable = true, latencyMillis = (clock() - start) / 1_000_000L)
        } catch (e: java.net.ConnectException) {
            // A refused connection still proves the host answered, measuring the round trip.
            HopResult(addressText, reachable = true, latencyMillis = (clock() - start) / 1_000_000L)
        } catch (_: Exception) {
            // Nothing on the port. That is expected on a cellular gateway, so before calling the
            // hop dead, ask it directly.
            val answered = runCatching {
                address.isReachable(ECHO_TIMEOUT_MILLIS)
            }.getOrDefault(false)
            HopResult(
                address = addressText,
                reachable = answered,
                latencyMillis = null,
                answeredEcho = answered,
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun aggregate(results: List<HopResult>): HopResult {
        val reached = results.filter { it.reachable }
        return if (reached.isNotEmpty()) {
            HopResult(
                address = results.first().address,
                reachable = true,
                latencyMillis = median(reached.mapNotNull { it.latencyMillis }),
                answeredEcho = reached.none { it.latencyMillis != null },
            )
        } else {
            HopResult(results.first().address, reachable = false, latencyMillis = null)
        }
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    public companion object {
        /**
         * How long the echo fallback waits before calling a hop silent.
         *
         * Half the per-round port timeout, and deliberately small: this runs only after the port
         * probe has already spent its own budget, three rounds deep, inside a checkup a person
         * is waiting on. A gateway that cannot answer half a second is not going to answer at all.
         */
        public const val ECHO_TIMEOUT_MILLIS: Int = 1_500
        /**
         * What the first hop's address field says when no gateway could be determined.
         *
         * Public because the inference layer must not read this as a dead link: an untested
         * hop and a hop that refused to answer are different facts, and only one of them is
         * evidence that the router is at fault.
         */
        public const val UNKNOWN_GATEWAY_ADDRESS: String = "gateway unknown"

        /**
         * The best gateway guess from the device's routing table: the source address of the
         * default route's interface. Not perfect - Android hides the literal gateway from
         * public APIs on some networks - but it addresses the local link, which is what the
         * first hop needs to test.
         */
        public fun discoverGatewayAddress(): InetAddress? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            }.getOrNull()
        }
    }
}

package dev.extranet.netdiag.measure

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/**
 * The three network questions Diagnose asks, and how it asks them.
 *
 * Each one is a single small request to a public endpoint, made only when the user taps the
 * button, and nothing about the user goes with it. There is no payload, no identifier and no
 * analytics: the only things that leave the phone are the request the question needs, and the
 * answers come back to be judged on the device.
 *
 * Every method returns facts rather than verdicts. What the facts *mean* is
 * [DiagnoseRules]'s business, which is what makes that decision table testable without a network.
 */
public object DiagnoseProbe {

    /**
     * Asks for a known empty page and watches what comes back.
     *
     * This is the one plain-HTTP request in the app, and it is plain for a reason: a carrier that
     * has stopped serving a line, or wants the user to top up, does it by *intercepting* the
     * request - answering with its own page or its own redirect. Over TLS that interception is
     * either impossible or a certificate error, and neither tells you what happened. Over HTTP it
     * is exactly what it looks like, which is the whole point.
     *
     * Redirects are not followed. Following them would produce a success, and the success would be
     * the carrier's page rather than the internet's.
     */
    public fun httpProbe(config: DiagnoseConfig = DiagnoseConfig.Default): DiagnoseRules.HttpFacts {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URI(config.probeUrl).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = config.probeTimeoutMillis
                readTimeout = config.probeTimeoutMillis
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
                // Nothing is cached and nothing is remembered: this is a measurement of the
                // network right now, and a cached answer would measure the last network instead.
                useCaches = false
            }
            val code = connection.responseCode
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            val location = runCatching { connection.getHeaderField("Location") }.getOrNull()
            val redirectHost = location?.let { hostOf(it) }
            val isRedirect = code in 300..399
            val differentHost = redirectHost != null && redirectHost != config.probeHost
            // The endpoint exists to answer 204 with no content. Anything else is the network
            // saying something of its own, whether by pointing somewhere or by answering itself.
            val unexpected = isRedirect || code !in 200..299 || code != 204
            DiagnoseRules.HttpFacts(
                attempted = true,
                reached = true,
                statusCode = code,
                // Only a redirect to somewhere else is worth naming: a 302 back to the same host
                // is odd but it is not the carrier taking the user anywhere.
                redirectedToHost = redirectHost?.takeIf { differentHost },
                unexpectedContent = unexpected && !differentHost,
                elapsedMillis = elapsed,
            )
        } catch (timeout: SocketTimeoutException) {
            DiagnoseRules.HttpFacts(
                attempted = true,
                reached = false,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
            )
        } catch (refused: ConnectException) {
            // Refused immediately rather than swallowed: this is the shape of a network that has
            // decided not to serve this line, and the elapsed time is what tells it apart from a
            // timeout.
            DiagnoseRules.HttpFacts(
                attempted = true,
                reached = false,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
            )
        } catch (failed: Exception) {
            DiagnoseRules.HttpFacts(
                attempted = true,
                reached = false,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * Tells "names are broken" apart from "nothing is reachable".
     *
     * The two failures look identical from the outside - nothing loads - and need opposite advice:
     * one is fixed in the phone's network settings and the other is not fixed by the user at all.
     * So a hostname is resolved, and an address is then connected to *without resolving anything*,
     * and the pair of answers is the diagnosis.
     */
    public fun addressProbe(config: DiagnoseConfig = DiagnoseConfig.Default): DiagnoseRules.AddressFacts {
        val resolved = try {
            val addresses = InetAddress.getAllByName(config.dnsHost)
            addresses.isNotEmpty()
        } catch (unknown: UnknownHostException) {
            false
        } catch (failed: Exception) {
            false
        }

        // Always measured, even when the name resolved. A lookup only proves the resolver
        // answered - it says nothing about whether a connection can be made, and the pair of
        // facts is only useful if both halves are real measurements rather than assumptions.
        val rawReached = reachable(config.rawIpAddress, config.probePort, config.connectTimeoutMillis)

        return DiagnoseRules.AddressFacts(nameResolved = resolved, rawAddressReached = rawReached)
    }

    /**
     * Median time to open a connection to a few well-known addresses.
     *
     * Several endpoints, and the median rather than the mean, for the same reason the radar uses
     * a median: one endpoint having a bad day is not a fact about the user's connection, and a
     * single slow sample would drag a mean far enough to change the verdict. A plain TCP connect
     * is used rather than a request, because it measures the round trip without waiting for
     * anybody's server to think.
     */
    public fun latencyProbe(config: DiagnoseConfig = DiagnoseConfig.Default): DiagnoseRules.PerformanceFacts {
        val samples = ArrayList<Long>(config.latencyProbeCount)
        var tried = 0
        var failed = 0
        for (endpoint in config.latencyEndpoints) {
            tried++
            val started = System.nanoTime()
            if (reachable(endpoint, config.probePort, config.connectTimeoutMillis)) {
                samples.add((System.nanoTime() - started) / 1_000_000L)
            } else {
                failed++
            }
        }
        return DiagnoseRules.PerformanceFacts(
            latencyMedianMillis = medianOrNull(samples),
            // Throughput is measured by the app's existing probe, not here: this one is about how
            // long the round trip takes, which a connection time answers and a download does not.
            throughputBitsPerSecond = null,
            endpointsTried = tried,
            endpointsFailed = failed,
        )
    }

    /** Opens and closes one connection; true when it opened. Never throws. */
    private fun reachable(host: String, port: Int, timeoutMillis: Int): Boolean {
        val socket = runCatching { Socket() }.getOrNull() ?: return false
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            true
        } catch (failed: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The host a Location header points at, or null when it names none (a relative redirect). */
    private fun hostOf(location: String): String? = runCatching { URI(location).host }.getOrNull()

    /** The median of a handful of samples, or null when there are none. */
    private fun medianOrNull(samples: List<Long>): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
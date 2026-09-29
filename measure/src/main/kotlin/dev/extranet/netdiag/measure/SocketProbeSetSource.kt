package dev.extranet.netdiag.measure

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The real waterfall, implemented with sockets rather than with an HTTP client.
 *
 * Each stage is driven by hand so that the four numbers mean exactly what they say:
 *
 *  1. **DNS** - one UDP query to the resolver the active network gave us, timed to the reply.
 *     The A record it returns is then used for the later stages, so the connect stage cannot
 *     silently re-resolve and hide resolution time inside the TCP row.
 *  2. **TCP** - `connect` to that address.
 *  3. **TLS** - the handshake layered over the connection TCP just opened, with SNI and hostname
 *     verification, because a verification failure is a real finding and waving it through would
 *     turn a broken certificate into a healthy measurement.
 *  4. **TTFB** - one HTTP/1.1 GET, timed to the first response byte.
 *
 * There is no Android dependency here and there does not need to be: this is the same code a
 * host-side run would use, which is what makes it possible to reason about the waterfall without
 * a device. What the platform adds - the resolver to aim at, and its own connectivity verdict -
 * arrives through [OsDiagnosticsSource].
 *
 * Once a stage fails, the stages after it are recorded as skipped rather than failed. That is the
 * difference between "the handshake was slow" and "there was no connection to handshake over",
 * and only the first is information about the network.
 */
public class SocketProbeSetSource(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random(),
) : ProbeSetSource {

    override fun probe(
        sequence: Int,
        target: ProbeTarget,
        resolver: ResolverAddress?,
        budget: ProbeBudget,
    ): ProbeSet {
        val startedAt = clock()
        val samples = mutableListOf<LayerSample>()

        // --- 1. DNS ------------------------------------------------------------------------
        val (dnsSample, resolvedLiteral) = if (resolver == null) {
            LayerSample(Layer.DNS, LayerOutcome.Skipped(NO_RESOLVER_REASON), 0L) to null
        } else {
            val (sample, literal) = timed(Layer.DNS, budget.dnsTimeoutMillis) {
                resolveOnce(target.host, resolver, budget.dnsTimeoutMillis)
            }
            // Which resolver was asked belongs with the number: a slow DNS row means nothing
            // without knowing whether it was the carrier's resolver or a public one.
            sample.copy(note = resolver.toString()) to literal
        }
        samples += dnsSample

        val address = resolvedLiteral?.let { literal ->
            runCatching { InetAddress.getByName(literal) }.getOrNull()
        }
        if (address == null) {
            samples += skippedAfter(Layer.DNS, dnsSample.outcome.detail ?: "no address was resolved")
            return ProbeSet(sequence, target, startedAt, samples)
        }

        // --- 2. TCP ------------------------------------------------------------------------
        val plain = runCatching { Socket() }.getOrNull()
        if (plain == null) {
            samples += LayerSample(Layer.TCP, LayerOutcome.Failed("could not create a socket"), 0L)
            samples += skippedAfter(Layer.TCP, "no socket to connect with")
            return ProbeSet(sequence, target, startedAt, samples)
        }

        val (tcpSample, connected) = timed(Layer.TCP, budget.tcpTimeoutMillis) {
            plain.connect(InetSocketAddress(address, target.port), budget.tcpTimeoutMillis)
        }
        samples += tcpSample.copy(note = address.hostAddress)
        if (connected == null) {
            closeQuietly(plain)
            samples += skippedAfter(Layer.TCP, "connection was not established")
            return ProbeSet(sequence, target, startedAt, samples)
        }

        // --- 3. TLS ------------------------------------------------------------------------
        val secure = try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // autoClose: closing the TLS socket closes the socket underneath it, so the file
            // descriptor has exactly one owner.
            factory.createSocket(plain, target.host, target.port, true) as SSLSocket
        } catch (throwable: Throwable) {
            closeQuietly(plain)
            samples += LayerSample(Layer.TLS, LayerOutcome.Failed(describe(throwable)), 0L)
            samples += skippedAfter(Layer.TLS, "no TLS session")
            return ProbeSet(sequence, target, startedAt, samples)
        }

        val (tlsSample, protocol) = timed(Layer.TLS, budget.tlsTimeoutMillis) {
            secure.soTimeout = budget.tlsTimeoutMillis
            val parameters = secure.sslParameters
            parameters.serverNames = listOf(SNIHostName(target.host))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            secure.sslParameters = parameters
            secure.startHandshake()
            secure.session.protocol
        }
        samples += tlsSample.copy(note = protocol?.let { "negotiated $it" })
        if (protocol == null) {
            closeQuietly(secure)
            samples += skippedAfter(Layer.TLS, "handshake did not complete")
            return ProbeSet(sequence, target, startedAt, samples)
        }

        // --- 4. TTFB -----------------------------------------------------------------------
        val request = target.httpGetRequest().toByteArray(Charsets.US_ASCII)
        val (ttfbSample, firstByte) = timed(Layer.TTFB, budget.ttfbTimeoutMillis) {
            secure.soTimeout = budget.ttfbTimeoutMillis
            val out = secure.outputStream
            out.write(request)
            out.flush()
            val first = secure.inputStream.read()
            if (first < 0) throw IOException("server closed the connection before sending a byte")
            first
        }
        samples += ttfbSample.copy(note = firstByte?.let { "first byte '${it.toChar()}'" })
        closeQuietly(secure)

        return ProbeSet(sequence, target, startedAt, samples)
    }

    /** Every stage after [after], recorded as unattempted rather than as a failure. */
    private fun skippedAfter(after: Layer, reason: String): List<LayerSample> =
        Layer.ORDER.dropWhile { it != after }.drop(1).map { layer ->
            LayerSample(layer, LayerOutcome.Skipped("not attempted: $reason"), 0L)
        }

    /**
     * One UDP query, timed to the moment the reply arrives.
     *
     * The socket is connected to the resolver before use so that only datagrams from that address
     * are delivered: an unconnected socket accepts whatever else is talking on the same port, and
     * the first stray datagram would otherwise end the stage early and be timed as an answer.
     */
    private fun resolveOnce(hostname: String, resolver: ResolverAddress, timeoutMillis: Int): String {
        val transactionId = random.nextInt(TRANSACTION_ID_BOUND)
        val query = DnsWire.query(hostname, transactionId)
        val resolverAddress = InetAddress.getByName(resolver.host)

        datagramSocketFor(resolverAddress).use { socket ->
            socket.connect(resolverAddress, resolver.port)
            socket.soTimeout = timeoutMillis
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MAX_DNS_RESPONSE_BYTES)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val reply = DnsWire.parse(buffer.copyOf(response.length), transactionId, hostname)
                ?: throw IOException("the datagram was not a reply to this query")
            if (!reply.answered) {
                throw IOException(
                    "resolver answered ${reply.responseCodeName()} (answers=${reply.answerCount})",
                )
            }
            return reply.firstIpv4Answer
                ?: throw IOException("resolver answered ${reply.responseCodeName()} with no A record")
        }
    }

    /**
     * A datagram socket of the right address family.
     *
     * `DatagramSocket()` binds IPv4, and connecting that to an IPv6 resolver throws. Mobile
     * networks hand out IPv6 resolvers routinely, so the family is chosen explicitly.
     */
    private fun datagramSocketFor(address: InetAddress): DatagramSocket {
        val wildcard = if (address is Inet6Address) {
            InetAddress.getByName(IPV6_WILDCARD)
        } else {
            InetAddress.getByName(IPV4_WILDCARD)
        }
        // Binding in the constructor rather than with bind() afterwards: the parameterless
        // constructor binds IPv4, and rebinding later is a second failure mode for nothing.
        return DatagramSocket(InetSocketAddress(wildcard, 0))
    }

    /**
     * Runs [block] under [layer]'s budget.
     *
     * A probe must never take the run down with it, so any throwable becomes an outcome and its
     * message becomes the failure detail, which is usually the most useful part of a failure.
     */
    private fun <T> timed(layer: Layer, timeoutMillis: Int, block: () -> T): Pair<LayerSample, T?> {
        val start = System.nanoTime()
        return try {
            // The block runs first and the duration is read after it returns. Evaluating the
            // duration in the sample's argument list would read the timer before the work ran
            // and record every stage as 0 ms - which is exactly the regression this order, and
            // SocketProbeSetSourceTest's delayed loopback responder, exist to prevent.
            val result = block()
            LayerSample(layer, LayerOutcome.Ok, elapsedMillis(start)) to result
        } catch (timeout: SocketTimeoutException) {
            LayerSample(layer, LayerOutcome.Timeout(timeoutMillis), elapsedMillis(start)) to null
        } catch (throwable: Throwable) {
            LayerSample(layer, LayerOutcome.Failed(describe(throwable)), elapsedMillis(start)) to null
        }
    }

    /** Monotonic elapsed time: a wall clock adjustment cannot make a latency negative. */
    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun describe(throwable: Throwable): String =
        "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"

    private fun closeQuietly(closeable: Closeable?) {
        if (closeable == null) return
        runCatching { closeable.close() }
    }

    private companion object {
        const val NO_RESOLVER_REASON: String = "no resolver on this network"
        const val TRANSACTION_ID_BOUND: Int = 0x1_0000
        const val MAX_DNS_RESPONSE_BYTES: Int = 1_232
        const val IPV4_WILDCARD: String = "0.0.0.0"
        const val IPV6_WILDCARD: String = "::"
    }
}

package dev.extranet.netdiag.measure

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins that the socket source measures what it runs, with a loopback DNS responder that delays
 * on purpose.
 *
 * The rest of this module's tests drive the engine through [FakeProbeSetSource], which hands the
 * engine finished samples and never executes the timing code. That gap is exactly how the one
 * real regression this class exists to prevent got through the first device run: the timer was
 * read before the work ran, every stage reported 0 ms, and the 10 seconds of real network activity
 * behind the run went untimed while the percentile assertions stayed green. A responder that
 * sleeps a known interval cannot be measured as anything but late, so this file catches it.
 */
class SocketProbeSetSourceTest {

    private var responder: LoopbackDnsResponder? = null

    @AfterTest
    fun closeResponder() {
        responder?.close()
    }

    @Test
    fun aDelayedResolverIsMeasuredAsDelayed() {
        val source = SocketProbeSetSource()
        val started = responderFor(delayMillis = 150L, address = byteArrayOf(127, 0, 0, 1))

        val set = source.probe(
            sequence = 0,
            target = ProbeTarget(host = "loopback.invalid", port = UNREACHABLE_PORT),
            resolver = ResolverAddress(host = "127.0.0.1", port = started.port),
            budget = ProbeBudget(),
        )

        val dns = set.sample(Layer.DNS)
        assertTrue(dns?.outcome is LayerOutcome.Ok, "the DNS stage did not run: $dns")
        assertTrue(
            dns!!.durationMillis >= 150L,
            "the resolver delayed 150 ms but the stage measured ${dns.durationMillis} ms - " +
                "durations are being read before the work runs",
        )
        assertTrue(
            set.attributedMillis >= dns.durationMillis,
            "attributed time ${set.attributedMillis} is smaller than the DNS stage alone",
        )
        assertEquals(
            "127.0.0.1:${started.port}",
            dns.note,
            "the resolver used belongs with the number",
        )
    }

    @Test
    fun aFastResolverIsMeasuredAsFast() {
        val source = SocketProbeSetSource()
        val started = responderFor(delayMillis = 0L, address = byteArrayOf(127, 0, 0, 1))

        val set = source.probe(
            sequence = 1,
            target = ProbeTarget(host = "loopback.invalid", port = UNREACHABLE_PORT),
            resolver = ResolverAddress(host = "127.0.0.1", port = started.port),
            budget = ProbeBudget(),
        )

        val dns = set.sample(Layer.DNS)
        assertTrue(dns?.outcome is LayerOutcome.Ok)
        assertTrue(
            dns!!.durationMillis < 2_000L,
            "a loopback round trip measured ${dns.durationMillis} ms - a fixed duration is " +
                "being recorded instead of a timed one",
        )
    }

    @Test
    fun anUnreachableTargetFailsHonestlyAndSkipsWhatCannotFollow() {
        val source = SocketProbeSetSource()
        // The resolver answers with a loopback address whose port has no listener, so the connect
        // stage is refused immediately and deterministically on every platform.
        val started = responderFor(delayMillis = 0L, address = byteArrayOf(127, 0, 0, 1))

        val set = source.probe(
            sequence = 2,
            target = ProbeTarget(host = "loopback.invalid", port = UNREACHABLE_PORT),
            resolver = ResolverAddress(host = "127.0.0.1", port = started.port),
            budget = ProbeBudget(),
        )

        val tcp = set.sample(Layer.TCP)
        assertTrue(tcp?.outcome is LayerOutcome.Failed, "connect to a closed port did not fail: $tcp")
        assertEquals(Layer.TCP, set.firstFailure)
        assertTrue(set.failed)
        assertFalse(set.complete)

        val tls = set.sample(Layer.TLS)!!
        val ttfb = set.sample(Layer.TTFB)!!
        assertTrue(tls.outcome is LayerOutcome.Skipped)
        assertTrue(ttfb.outcome is LayerOutcome.Skipped)
        assertTrue(
            (tls.outcome as LayerOutcome.Skipped).reason.contains("not attempted"),
            "the skip reason must say why the stage was not attempted",
        )
        // A refusal is fast, but it is not a measurement: a skipped stage carries no duration
        // (the raw sample holds 0) and the accessor reports null, so nothing about the refusal
        // is aggregated into the percentiles.
        assertEquals(0L, tls.durationMillis)
        assertEquals(0L, ttfb.durationMillis)
        assertEquals(null, set.durationMillis(Layer.TLS))
        assertEquals(null, set.durationMillis(Layer.TTFB))
    }

    /** Starts a loopback responder and registers it for cleanup. */
    private fun responderFor(delayMillis: Long, address: ByteArray): LoopbackDnsResponder {
        val started = LoopbackDnsResponder(delayMillis = delayMillis, address = address)
        started.start()
        responder = started
        return started
    }

    private companion object {
        /** A port on 127.0.0.1 nothing listens on, so connect is refused, not timed out. */
        const val UNREACHABLE_PORT: Int = 1
    }
}

/**
 * A one-shot DNS responder on the loopback interface.
 *
 * It answers the first query with a fixed A record after [delayMillis], speaking just enough of
 * the wire format for [DnsWire.parse] to accept the reply: echoed transaction id, the response
 * flag, the question echoed back and one A answer behind a compression pointer.
 */
private class LoopbackDnsResponder(
    private val delayMillis: Long,
    private val address: ByteArray,
) {
    private val socket = java.net.DatagramSocket(
        java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0),
    )
    private var worker: Thread? = null

    /** The ephemeral port to point a resolver address at. */
    val port: Int get() = socket.localPort

    fun start() {
        worker = Thread {
            val buffer = ByteArray(512)
            while (true) {
                val query = java.net.DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(query)
                } catch (_: Exception) {
                    break // socket closed by the test
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis)
                }
                val reply = replyBytes(query.data, query.length)
                val packet = java.net.DatagramPacket(
                    reply,
                    reply.size,
                    java.net.InetAddress.getByName("127.0.0.1"),
                    query.port,
                )
                socket.send(packet)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        socket.close()
    }

    /** A NOERROR reply carrying one A record for the query it answers. */
    private fun replyBytes(query: ByteArray, length: Int): ByteArray {
        // Walk the question section so it can be echoed back byte for byte.
        var index = DnsWire.HEADER_BYTES
        while (index < length && query[index].toInt() != 0) {
            index += query[index].toInt() + 1
        }
        index += 1 + 4 // root label, then QTYPE and QCLASS

        return byteArrayOf(
            query[0], query[1], // transaction id, echoed
            0x81.toByte(), 0x80.toByte(), // QR=1, RD=1, RA=1, RCODE=0
            0, 1, // QDCOUNT
            0, 1, // ANCOUNT
            0, 0, // NSCOUNT
            0, 0, // ARCOUNT
        ) +
            query.copyOfRange(DnsWire.HEADER_BYTES, index) + // the question, echoed
            byteArrayOf(
                0xC0.toByte(), 0x0C.toByte(), // answer name: pointer to the question
                0, DnsWire.TYPE_A.toByte(),
                0, DnsWire.CLASS_IN.toByte(),
                0, 0, 0, 30, // TTL
                0, 4, // RDLENGTH
                address[0], address[1], address[2], address[3],
            )
    }
}

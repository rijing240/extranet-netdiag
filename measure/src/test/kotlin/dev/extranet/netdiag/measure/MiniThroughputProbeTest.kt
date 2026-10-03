package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the arithmetic the throughput verdict rests on.
 *
 * These exist because the probe shipped with two errors that no device test could see: the rate
 * was computed in the wrong unit, so every transfer looked six orders of magnitude slower than
 * it was, and the payload path was pointed at a host that answers it with a 404 page. Both
 * produced the same plausible sentence - "data barely moves" - on a network that was fine, which
 * is exactly the failure mode the app is supposed to rule out.
 */
class MiniThroughputProbeTest {

    /** A stream that hands out one byte per read, so header detection is tested across reads. */
    private class OneByteAtATime(private val delegate: ByteArrayInputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, 1)
    }

    private fun response(body: ByteArray): ByteArray {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test
    fun theRateIsBytesPerSecond() {
        // 1 MB in 2 s is 500 kB/s, which the ledger calls MARGINAL.
        assertEquals(500_000.0, MiniThroughputProbe.rate(1_000_000L, 2_000_000_000L), 1e-6)
        assertEquals("MARGINAL", TimelineBudget.throughputVerdict(MiniThroughputProbe.rate(1_000_000L, 2_000_000_000L)))
        // A megabyte in a second is 1 MB/s, which is still short of the good band.
        assertEquals("MARGINAL", TimelineBudget.throughputVerdict(MiniThroughputProbe.rate(1_000_000L, 1_000_000_000L)))
        // And a megabyte in half a second clears it.
        assertEquals("GOOD", TimelineBudget.throughputVerdict(MiniThroughputProbe.rate(1_000_000L, 500_000_000L)))
    }

    @Test
    fun theRateIsNotOffByAFactorOfAMillion() {
        // The number the screen shows for the case above: 4.0 Mbps at 500 kB/s.
        val megabitsPerSecond = MiniThroughputProbe.rate(1_000_000L, 2_000_000_000L) * 8.0 / 1_000_000.0
        assertEquals(4.0, megabitsPerSecond, 1e-9)
        assertTrue(megabitsPerSecond > 1.0, "a real megabyte in two seconds is not 0.00 Mbps")
    }

    @Test
    fun theRateRefusesImpossibleInputs() {
        assertFailsWith<IllegalArgumentException> { MiniThroughputProbe.rate(1_000L, 0L) }
        assertFailsWith<IllegalArgumentException> { MiniThroughputProbe.rate(-1L, 1_000_000L) }
    }

    @Test
    fun theBodyClockRunsOnlyOverTheBodyAndNotOverTheHeaders() {
        // The clock must start when the body starts. If it ran from the end of the headers the
        // slow-server case would be charged to the transfer, which is the mirror image of the
        // bug this replaced - there, the fast connection was charged for its handshake.
        val body = ByteArray(500) { it.toByte() }

        // A clock frozen on the headers: the whole read costs the same as the body, so the
        // window must not include the time spent waiting for the headers at all. With a
        // frozen clock the span is zero, which the reader floors at one nanosecond so the
        // divisor can never be zero - the point is that it is not the whole elapsed read.
        var frozen = 1_000L
        val still = MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))) { frozen }
        assertEquals(500L, still.bytes)
        assertEquals(1L, still.bodyNanos, "a frozen clock gives a floored window, not the header wait")

        // A clock advancing 10 ms per read: the window is a small multiple of that step, never
        // the sum of the whole response including its headers.
        var ticks = 0
        val stepped = MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))) {
            ticks++
            ticks * 10_000_000L
        }
        assertEquals(500L, stepped.bytes)
        assertTrue(
            stepped.bodyNanos in 1..(ticks * 10_000_000L),
            "the window must come from the body reads, got ${stepped.bodyNanos} ns over $ticks ticks",
        )
    }

    @Test
    fun aResponseWithNoBodyReportsNoWindowSoNoRateCanBeInvented() {
        val head = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val result = MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(head))
        assertEquals(0L, result.bytes)
        assertEquals(0L, result.bodyNanos, "no body means no transfer window to divide by")
    }

    @Test
    fun theBodyIsCountedExactlyEvenWithoutATrailingNewline() {
        val body = ByteArray(1_000) { ((it * 37) and 0xFF).toByte() }
        assertEquals(1_000L, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))).bytes)
    }

    @Test
    fun aBinaryBodyContainingLineEndingsStillCountsEveryByte() {
        val body = byteArrayOf(0x00, 0x0D, 0x0A, 0x0D, 0x0A, 0x7F, 0x0D, 0x0A, 0xFF.toByte())
        assertEquals(
            body.size.toLong(),
            MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))).bytes,
        )
    }

    @Test
    fun theHeaderIsFoundEvenWhenItArrivesOneByteAtATime() {
        val body = ByteArray(64) { it.toByte() }
        val trickle = OneByteAtATime(ByteArrayInputStream(response(body)))
        assertEquals(64L, MiniThroughputProbe.countBodyBytes(trickle).bytes)
    }

    @Test
    fun aResponseWithNoBodyCountsNothing() {
        val head = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(0L, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(head)).bytes)
    }

    @Test
    fun theStatusCodeIsExtractedFromTheResponseHeader() {
        val body = ByteArray(10) { it.toByte() }
        assertEquals(200, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))).statusCode)
        val redirect = "HTTP/1.1 302 Found\r\nLocation: https://example.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(302, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(redirect)).statusCode)
    }

    @Test
    fun thePayloadPathBelongsToTheHostThatActuallyServesIt() {
        // The connectivity endpoint answers 204 No Content, so a payload path pointed at it
        // measures an error page. The two constants have to move together.
        assertEquals("speed.cloudflare.com", MiniThroughputProbe.THROUGHPUT_HOST)
        assertTrue(MiniThroughputProbe.THROUGHPUT_PATH.startsWith("/__down"))
        assertTrue(
            MiniThroughputProbe.THROUGHPUT_PATH.contains(TimelineBudget.THROUGHPUT_PAYLOAD_BYTES.toString()),
            "the payload path must ask for the ledger's payload size",
        )
    }
}

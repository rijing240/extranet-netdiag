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
    fun theBodyIsCountedExactlyEvenWithoutATrailingNewline() {
        val body = ByteArray(1_000) { ((it * 37) and 0xFF).toByte() }
        assertEquals(1_000L, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))).second)
    }

    @Test
    fun aBinaryBodyContainingLineEndingsStillCountsEveryByte() {
        val body = byteArrayOf(0x00, 0x0D, 0x0A, 0x0D, 0x0A, 0x7F, 0x0D, 0x0A, 0xFF.toByte())
        assertEquals(
            body.size.toLong(),
            MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))).second,
        )
    }

    @Test
    fun theHeaderIsFoundEvenWhenItArrivesOneByteAtATime() {
        val body = ByteArray(64) { it.toByte() }
        val trickle = OneByteAtATime(ByteArrayInputStream(response(body)))
        assertEquals(64L, MiniThroughputProbe.countBodyBytes(trickle).second)
    }

    @Test
    fun aResponseWithNoBodyCountsNothing() {
        val head = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(0L, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(head)).second)
    }

    @Test
    fun theStatusCodeIsExtractedFromTheResponseHeader() {
        val body = ByteArray(10) { it.toByte() }
        assertEquals(200, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(response(body))).first)
        val redirect = "HTTP/1.1 302 Found\r\nLocation: https://example.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(302, MiniThroughputProbe.countBodyBytes(ByteArrayInputStream(redirect)).first)
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

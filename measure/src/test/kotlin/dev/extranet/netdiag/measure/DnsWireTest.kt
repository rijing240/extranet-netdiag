package dev.extranet.netdiag.measure

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the DNS codec against hand-built packets.
 *
 * Nothing here touches the network, which is the point: the wire format is the part that must be
 * right, and a resolver is not required to check it. The cases below include the ones that decide
 * whether a timing is trustworthy - a reply to somebody else's query, a reply that answers with a
 * different name, and a reply whose answer section starts with a CNAME rather than an A record.
 */
class DnsWireTest {

    private class Packet {
        private val bytes = ByteArrayOutputStream()

        fun u16(value: Int): Packet = apply {
            bytes.write((value shr 8) and 0xFF)
            bytes.write(value and 0xFF)
        }

        fun u32(value: Long): Packet = apply {
            bytes.write(((value shr 24) and 0xFF).toInt())
            bytes.write(((value shr 16) and 0xFF).toInt())
            bytes.write(((value shr 8) and 0xFF).toInt())
            bytes.write((value and 0xFF).toInt())
        }

        fun raw(values: IntArray): Packet = apply { values.forEach { bytes.write(it and 0xFF) } }

        fun name(value: String): Packet = apply {
            for (label in value.trim('.').split('.')) {
                bytes.write(label.length)
                bytes.write(label.toByteArray(Charsets.US_ASCII))
            }
            bytes.write(0)
        }

        fun pointer(offset: Int): Packet = apply { raw(intArrayOf(0xC0 or (offset shr 8), offset and 0xFF)) }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private fun reply(
        id: Int = 0x1234,
        flags: Int = 0x8180,
        question: String = "example.com",
        answerCount: Int = 1,
        answers: Packet.() -> Unit,
    ): ByteArray = Packet()
        .u16(id)
        .u16(flags)
        .u16(1)
        .u16(answerCount)
        .u16(0)
        .u16(0)
        .name(question)
        .u16(DnsWire.TYPE_A)
        .u16(DnsWire.CLASS_IN)
        .apply { answers(this) }
        .toByteArray()

    private fun Packet.aRecord(address: String, ttl: Long = 60L): Packet = apply {
        pointer(0x0C)
        u16(DnsWire.TYPE_A)
        u16(DnsWire.CLASS_IN)
        u32(ttl)
        u16(4)
        raw(address.split('.').map { it.toInt() }.toIntArray())
    }

    private fun Packet.cname(name: String): Packet = apply {
        pointer(0x0C)
        u16(5)
        u16(DnsWire.CLASS_IN)
        u32(60L)
        val encoded = Packet().name(name).toByteArray()
        u16(encoded.size)
        raw(encoded.map { it.toInt() and 0xFF }.toIntArray())
    }

    // --- the query --------------------------------------------------------------------------

    @Test
    fun `a query is the header, the labels and the question type`() {
        val query = DnsWire.query("example.com", 0x1234)

        assertEquals(DnsWire.HEADER_BYTES + 1 + 7 + 1 + 3 + 1 + 4, query.size)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        assertEquals(0x01, query[2].toInt() and 0xFF, "recursion desired")
        assertEquals(0x00, query[3].toInt() and 0xFF)
        assertEquals(1, query[5].toInt() and 0xFF, "one question")
        assertEquals(0, query[7].toInt() and 0xFF, "no answers in a query")
        assertEquals(7, query[12].toInt() and 0xFF, "first label length")
        assertEquals("example", String(query, 13, 7, Charsets.US_ASCII))
        assertEquals("com", String(query, 21, 3, Charsets.US_ASCII))
        assertEquals(0, query[24].toInt() and 0xFF, "root label")
        assertEquals(0, query[25].toInt() and 0xFF, "type A high byte")
        assertEquals(1, query[26].toInt() and 0xFF, "type A low byte")
        assertEquals(0, query[27].toInt() and 0xFF, "class IN high byte")
        assertEquals(1, query[28].toInt() and 0xFF, "class IN low byte")
    }

    @Test
    fun `a name that cannot go on the wire is rejected here rather than sent`() {
        assertTrue(runCatching { DnsWire.query("", 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DnsWire.query("...", 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { DnsWire.query("a".repeat(64) + ".com", 1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(runCatching { DnsWire.query("exa mple.com", 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DnsWire.query("example.com", 70_000) }.exceptionOrNull() is IllegalArgumentException)
    }

    // --- the reply --------------------------------------------------------------------------

    @Test
    fun `a normal reply yields the answer, the echo and the rcode`() {
        val packet = reply { aRecord("93.184.216.34") }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))

        assertEquals(0x1234, parsed.transactionId)
        assertEquals("NOERROR", parsed.responseCodeName())
        assertEquals(1, parsed.answerCount)
        assertTrue(parsed.answered)
        assertEquals(true, parsed.questionEchoes)
        assertEquals("93.184.216.34", parsed.firstIpv4Answer)
        assertFalse(parsed.truncated)
    }

    @Test
    fun `a reply to a different query is rejected`() {
        val packet = reply(id = 0x9999) { aRecord("1.2.3.4") }
        assertNull(DnsWire.parse(packet, 0x1234, "example.com"))
    }

    @Test
    fun `a query is not mistaken for a reply`() {
        val packet = reply(flags = 0x0100) { aRecord("1.2.3.4") }
        assertNull(DnsWire.parse(packet, 0x1234, "example.com"))
    }

    @Test
    fun `a runt packet is rejected rather than walked off the end`() {
        assertNull(DnsWire.parse(ByteArray(11), 0x1234, "example.com"))
        assertNull(DnsWire.parse(ByteArray(0), 0x1234, "example.com"))
    }

    @Test
    fun `an answer section that starts with a cname is walked to the address behind it`() {
        val packet = reply(answerCount = 2) {
            cname("cdn.example.com")
            aRecord("203.0.113.7")
        }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))
        assertEquals(2, parsed.answerCount)
        assertEquals("203.0.113.7", parsed.firstIpv4Answer)
    }

    @Test
    fun `an answer with only a cname answers without an address`() {
        val packet = reply(answerCount = 1) { cname("cdn.example.com") }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))
        assertTrue(parsed.answered, "the name exists, the address is simply not in this reply")
        assertNull(parsed.firstIpv4Answer)
    }

    @Test
    fun `a name that does not exist is reported as such rather than as an address`() {
        val packet = reply(flags = 0x8183, answerCount = 0) { }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))

        assertEquals(DnsWire.RCODE_NAME_ERROR, parsed.responseCode)
        assertEquals("NXDOMAIN", parsed.responseCodeName())
        assertFalse(parsed.answered)
        assertNull(parsed.firstIpv4Answer)
    }

    @Test
    fun `every documented rcode has a name and the rest fall back to their number`() {
        assertEquals("NOERROR", DnsWire.Reply(0, 0, 0, false, true, null).responseCodeName())
        assertEquals("FORMERR", DnsWire.Reply(0, 1, 0, false, true, null).responseCodeName())
        assertEquals("SERVFAIL", DnsWire.Reply(0, 2, 0, false, true, null).responseCodeName())
        assertEquals("NXDOMAIN", DnsWire.Reply(0, 3, 0, false, true, null).responseCodeName())
        assertEquals("NOTIMP", DnsWire.Reply(0, 4, 0, false, true, null).responseCodeName())
        assertEquals("REFUSED", DnsWire.Reply(0, 5, 0, false, true, null).responseCodeName())
        assertEquals("RCODE9", DnsWire.Reply(0, 9, 0, false, true, null).responseCodeName())
    }

    @Test
    fun `a truncated reply says so`() {
        val packet = reply(flags = 0x8380) { aRecord("1.2.3.4") }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))
        assertTrue(parsed.truncated)
    }

    @Test
    fun `a reply that echoes somebody else's question is noticed`() {
        val packet = reply(question = "other.example") { aRecord("1.2.3.4") }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))
        assertEquals(false, parsed.questionEchoes)
    }

    @Test
    fun `case differences in the echoed question are not reported as a mismatch`() {
        // 0x20-style resolver hardening randomises the case of the echoed question, which is
        // evidence of care rather than a problem.
        val packet = reply(question = "ExAmPlE.CoM") { aRecord("1.2.3.4") }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))
        assertEquals(true, parsed.questionEchoes)
    }

    @Test
    fun `a reply whose answer count exceeds its contents stops instead of guessing`() {
        val packet = reply(answerCount = 3) { aRecord("1.2.3.4") }
        val parsed = assertNotNull(DnsWire.parse(packet, 0x1234, "example.com"))
        assertEquals("1.2.3.4", parsed.firstIpv4Answer, "the first answer is still readable")
    }
}

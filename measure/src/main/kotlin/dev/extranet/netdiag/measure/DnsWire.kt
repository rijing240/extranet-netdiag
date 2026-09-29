package dev.extranet.netdiag.measure

/**
 * A minimal DNS codec: one A query out, one reply read.
 *
 * The platform's own resolver API would have been the obvious choice, but it is asynchronous,
 * its callback signature changed shape in API 30, and - the deciding reason - it reports a
 * completion, not a wire round trip. The waterfall's DNS row is only worth having if it measures
 * the query actually going to the resolver the network handed us and coming back, so the query
 * is built and timed here. It also makes the codec testable on a JVM with no network at all,
 * which is where the last batch learned the value of keeping.
 *
 * Only the header, the echoed question and the first A record are read. Everything else - other
 * record types, CNAME chains, additional sections - is skipped by walking lengths, so a reply the
 * codec does not fully understand still yields a correct timing and a correct rcode.
 */
public object DnsWire {

    /** Fixed DNS header length. */
    public const val HEADER_BYTES: Int = 12

    /** Query type for an IPv4 address record. */
    public const val TYPE_A: Int = 1

    /** Query class for internet records. */
    public const val CLASS_IN: Int = 1

    /** Reply code for a name that resolved. */
    public const val RCODE_NO_ERROR: Int = 0

    /** Reply code for a malformed query. */
    public const val RCODE_FORMAT_ERROR: Int = 1

    /** Reply code for a resolver-side failure. */
    public const val RCODE_SERVER_FAILURE: Int = 2

    /** Reply code for a name that does not exist. */
    public const val RCODE_NAME_ERROR: Int = 3

    /** Reply code for a query the resolver will not answer. */
    public const val RCODE_NOT_IMPLEMENTED: Int = 4

    /** Reply code for a query the resolver refuses to answer. */
    public const val RCODE_REFUSED: Int = 5

    private const val FLAG_STANDARD_QUERY: Int = 0x0100
    private const val FLAG_RESPONSE: Int = 0x8000
    private const val FLAG_TRUNCATED: Int = 0x0200
    private const val RCODE_MASK: Int = 0x000F
    private const val COMPRESSION_MASK: Int = 0xC0
    private const val MAX_LABEL_LENGTH: Int = 63

    /**
     * What a reply said.
     *
     * @property questionEchoes whether the reply echoed our question back. Null when the reply
     *   could not be walked far enough to tell, which is itself worth reporting: a resolver that
     *   does not echo the question is unusual and the reader should see that rather than a
     *   fabricated `true`.
     * @property firstIpv4Answer the first A record, if the reply carried one.
     */
    public data class Reply(
        public val transactionId: Int,
        public val responseCode: Int,
        public val answerCount: Int,
        public val truncated: Boolean,
        public val questionEchoes: Boolean?,
        public val firstIpv4Answer: String?,
    ) {
        /** True when the resolver answered the name with at least one record. */
        public val answered: Boolean get() = responseCode == RCODE_NO_ERROR && answerCount > 0

        /** Readable name for [responseCode], including the codes that are not errors. */
        public fun responseCodeName(): String = when (responseCode) {
            RCODE_NO_ERROR -> "NOERROR"
            RCODE_FORMAT_ERROR -> "FORMERR"
            RCODE_SERVER_FAILURE -> "SERVFAIL"
            RCODE_NAME_ERROR -> "NXDOMAIN"
            RCODE_NOT_IMPLEMENTED -> "NOTIMP"
            RCODE_REFUSED -> "REFUSED"
            else -> "RCODE$responseCode"
        }
    }

    /**
     * Builds a standard recursive A query for [hostname].
     *
     * @throws IllegalArgumentException when the name cannot be put on the wire, so that a
     *   malformed name fails here rather than producing a timing that measures nothing.
     */
    public fun query(hostname: String, transactionId: Int): ByteArray {
        val labels = hostname.trim('.').split('.').filter { it.isNotEmpty() }
        require(labels.isNotEmpty()) { "hostname must have at least one label: '$hostname'" }
        for (label in labels) {
            require(label.length <= MAX_LABEL_LENGTH) { "label too long in '$hostname': '$label'" }
            require(label.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "label '$label' is not a plain DNS label; the codec does not encode escapes"
            }
        }
        require(transactionId in 0..0xFFFF) { "transaction id out of range: $transactionId" }

        val size = HEADER_BYTES + labels.sumOf { it.length + 1 } + 1 + 4
        val out = ByteArray(size)
        var i = 0
        out[i++] = ((transactionId shr 8) and 0xFF).toByte()
        out[i++] = (transactionId and 0xFF).toByte()
        out[i++] = ((FLAG_STANDARD_QUERY shr 8) and 0xFF).toByte()
        out[i++] = (FLAG_STANDARD_QUERY and 0xFF).toByte()
        out[i++] = 0
        out[i++] = 1 // QDCOUNT
        out[i++] = 0
        out[i++] = 0 // ANCOUNT
        out[i++] = 0
        out[i++] = 0 // NSCOUNT
        out[i++] = 0
        out[i++] = 0 // ARCOUNT
        for (label in labels) {
            out[i++] = label.length.toByte()
            for (character in label) {
                out[i++] = character.code.toByte()
            }
        }
        out[i++] = 0 // root label
        out[i++] = 0
        out[i++] = TYPE_A.toByte()
        out[i++] = 0
        out[i++] = CLASS_IN.toByte()
        return out
    }

    /**
     * Parses [packet] as a reply to [expectedTransactionId].
     *
     * Returns null when the packet cannot be a reply to our query at all: too short, a different
     * transaction id, or a query rather than a response. That last check matters because a stray
     * datagram from another process on the same port would otherwise be timed as our answer.
     */
    public fun parse(packet: ByteArray, expectedTransactionId: Int, hostname: String): Reply? {
        if (packet.size < HEADER_BYTES) return null
        val transactionId = u16(packet, 0)
        if (transactionId != expectedTransactionId) return null
        val flags = u16(packet, 2)
        if (flags and FLAG_RESPONSE == 0) return null

        val questionEnd = skipQuestion(packet)
        return Reply(
            transactionId = transactionId,
            responseCode = flags and RCODE_MASK,
            answerCount = u16(packet, 6),
            truncated = flags and FLAG_TRUNCATED != 0,
            questionEchoes = if (questionEnd == null) null else questionEchoes(packet, hostname),
            firstIpv4Answer = questionEnd?.let { firstIpv4Answer(packet, it) },
        )
    }

    /** Unsigned 16-bit read. */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** Offset just past the question section, or null when it cannot be walked. */
    private fun skipQuestion(packet: ByteArray): Int? {
        val afterName = skipName(packet, HEADER_BYTES) ?: return null
        return if (afterName + 4 <= packet.size) afterName + 4 else null
    }

    /** Offset just past the name starting at [start], following a compression pointer as a leaf. */
    private fun skipName(packet: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and COMPRESSION_MASK == COMPRESSION_MASK) return offset + 2
            if (length > MAX_LABEL_LENGTH) return null
            offset += length + 1
        }
        return null
    }

    /** Whether the question at the start of the packet matches [hostname], case-insensitively. */
    private fun questionEchoes(packet: ByteArray, hostname: String): Boolean? {
        val labels = mutableListOf<String>()
        var offset = HEADER_BYTES
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            if (length == 0) break
            if (length and COMPRESSION_MASK == COMPRESSION_MASK) return null
            if (length > MAX_LABEL_LENGTH || offset + 1 + length > packet.size) return null
            labels += String(packet, offset + 1, length, Charsets.US_ASCII)
            offset += length + 1
        }
        val echoed = labels.joinToString(".").lowercase()
        return echoed == hostname.trim('.').lowercase()
    }

    /** The first A record in the answer section, walking lengths rather than assuming layout. */
    private fun firstIpv4Answer(packet: ByteArray, start: Int): String? {
        var offset = start
        var remaining = u16(packet, 6)
        while (remaining > 0) {
            offset = skipName(packet, offset) ?: return null
            // TYPE(2) CLASS(2) TTL(4) RDLENGTH(2)
            if (offset + 10 > packet.size) return null
            val type = u16(packet, offset)
            val dataLength = u16(packet, offset + 8)
            offset += 10
            if (offset + dataLength > packet.size) return null
            if (type == TYPE_A && dataLength == 4) {
                return "${packet[offset].toInt() and 0xFF}." +
                    "${packet[offset + 1].toInt() and 0xFF}." +
                    "${packet[offset + 2].toInt() and 0xFF}." +
                    "${packet[offset + 3].toInt() and 0xFF}"
            }
            offset += dataLength
            remaining--
        }
        return null
    }
}

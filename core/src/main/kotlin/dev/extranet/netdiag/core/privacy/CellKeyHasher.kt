package dev.extranet.netdiag.core.privacy

import dev.extranet.netdiag.core.ledger.Atlas
import java.security.MessageDigest

/**
 * Builds the pseudonymous key under which a Capacity Atlas observation is stored.
 *
 * The rule the architecture depends on is that no raw cell identity and no raw coordinate
 * ever leaves the device. Instead the device sends a truncated salted hash of
 * `geohash | earfcn | pci | tac`, which is stable enough to aggregate observations of the same
 * physical sector and useless for re-identifying a subscriber.
 *
 * This is pseudonymisation, not anonymisation: with the salt, a party holding a candidate cell
 * fingerprint could confirm a match. The salt is therefore treated as a server-side secret and
 * is never shipped in the APK.
 */
public object CellKeyHasher {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Field separator. A control character that cannot appear in any component. */
    public const val FIELD_SEPARATOR: Char = '\u001F'

    /**
     * Hashes a composite cell fingerprint.
     *
     * @param salt server-side secret; never bundle this in the application.
     * @param geohash coarsened location, e.g. geohash-7.
     * @param earfcn or nrarfcn; pass 0 when unknown.
     * @param pci physical cell identity; pass 0 when unknown.
     * @param tac tracking area code; pass 0 when unknown.
     * @return lower-case hex, truncated to [Atlas.CELL_KEY_HEX_LENGTH] characters.
     */
    public fun cellKey(
        salt: String,
        geohash: String,
        earfcn: Int,
        pci: Int,
        tac: Int,
    ): String {
        val composite = buildString {
            append(salt)
            append(FIELD_SEPARATOR)
            append(geohash)
            append(FIELD_SEPARATOR)
            append(earfcn)
            append(FIELD_SEPARATOR)
            append(pci)
            append(FIELD_SEPARATOR)
            append(tac)
        }
        return truncate(sha256Hex(composite), Atlas.CELL_KEY_HEX_LENGTH)
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of [value]. */
    public fun sha256Hex(value: String): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** Keeps the first [length] characters of [hex]. */
    public fun truncate(hex: String, length: Int): String {
        require(length > 0) { "length must be positive, was $length" }
        return if (hex.length <= length) hex else hex.substring(0, length)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }
}

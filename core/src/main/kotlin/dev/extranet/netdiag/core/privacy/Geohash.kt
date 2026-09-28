package dev.extranet.netdiag.core.privacy

/**
 * Standard base-32 geohash encoding.
 *
 * Used only to coarsen a location before it is combined with a radio fingerprint and hashed.
 * A raw latitude/longitude pair must never leave the device; the geohash at precision 7
 * (~153 m) is the finest resolution this project is willing to publish, because anything
 * finer starts describing an individual building rather than a neighbourhood.
 *
 * The alphabet omits `a`, `i`, `l` and `o` to avoid transcription ambiguity.
 */
public object Geohash {

    /** Geohash base-32 alphabet. */
    public const val ALPHABET: String = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Highest precision this encoder supports (12 characters, ~3.7 cm). */
    public const val MAX_PRECISION: Int = 12

    /**
     * Encodes a coordinate pair to [precision] characters.
     *
     * @throws IllegalArgumentException when the coordinate or precision is out of range.
     */
    public fun encode(latitude: Double, longitude: Double, precision: Int): String {
        require(precision in 1..MAX_PRECISION) {
            "precision must be in 1..$MAX_PRECISION, was $precision"
        }
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }

        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        val out = StringBuilder(precision)
        var ch = 0
        var bits = 0
        var longitudeNext = true

        while (out.length < precision) {
            if (longitudeNext) {
                val mid = (lonMin + lonMax) / 2.0
                if (longitude >= mid) {
                    ch = (ch shl 1) or 1
                    lonMin = mid
                } else {
                    ch = ch shl 1
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2.0
                if (latitude >= mid) {
                    ch = (ch shl 1) or 1
                    latMin = mid
                } else {
                    ch = ch shl 1
                    latMax = mid
                }
            }
            longitudeNext = !longitudeNext
            bits++
            if (bits == 5) {
                out.append(ALPHABET[ch])
                bits = 0
                ch = 0
            }
        }
        return out.toString()
    }

    /** Encodes at the project's published precision, [dev.extranet.netdiag.core.ledger.Atlas.GEOHASH_PRECISION]. */
    public fun encodeAtlasPrecision(latitude: Double, longitude: Double): String =
        encode(latitude, longitude, dev.extranet.netdiag.core.ledger.Atlas.GEOHASH_PRECISION)
}

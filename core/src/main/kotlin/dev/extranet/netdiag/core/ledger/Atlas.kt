package dev.extranet.netdiag.core.ledger

/**
 * Capacity Atlas bucketing parameters.
 *
 * Baselines are keyed by a coarsened location plus the cell radio fingerprint, then published
 * only when enough independent observations back the bucket. Coarsening to geohash-7
 * (~153 m x 153 m) is the smallest cell that still averages away individual building
 * geometry; anything finer starts describing a specific household.
 */
public object Atlas {

    /** Geohash character precision used for the spatial key. ~153 m x 153 m at the equator. */
    public const val GEOHASH_PRECISION: Int = 7

    /** Approximate edge length of a geohash-7 cell at the equator, metres. */
    public const val GEOHASH_CELL_METRES: Double = 153.0

    /** Minimum independent observations before a bucket may be published or served. */
    public const val MIN_BUCKET_SIZE: Int = 5

    /** Hour-of-day buckets retained per key. */
    public const val HOUR_BUCKETS: Int = 24

    /** Salted hash length in hex characters used for the cell key. 16 hex chars = 64 bits. */
    public const val CELL_KEY_HEX_LENGTH: Int = 16
}

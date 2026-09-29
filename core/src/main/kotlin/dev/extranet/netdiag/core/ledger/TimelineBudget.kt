package dev.extranet.netdiag.core.ledger

/**
 * The radio timeline's fixed decisions (S1, batch B2).
 *
 * These are the numbers the timeline sampler may not re-choose per feature: the cadence cap,
 * the ring capacity, the compass sector count, and the two-hop attribution thresholds. They
 * sit in the ledger next to the physical constants so a test can fail when a screen silently
 * starts sampling at a different rate than the battery budget assumes.
 */
public object TimelineBudget {

    /** Samples the in-memory ring holds before it starts overwriting: 2 hours at 1 Hz. */
    public const val RING_CAPACITY_SAMPLES: Int = 7_200

    /** Bytes one packed radio sample may occupy, matching [ByteBudget.RADIO_SAMPLE_BYTES]. */
    public const val SAMPLE_BYTES: Int = ByteBudget.RADIO_SAMPLE_BYTES

    /** Bytes the full ring occupies at capacity, for budget reporting: 187.2 kB. */
    public fun ringBytes(): Long = SAMPLE_BYTES.toLong() * RING_CAPACITY_SAMPLES

    /** Compass sectors the signal compass aggregates into: one per 22.5 degrees, 0-based east. */
    public const val COMPASS_SECTORS: Int = 16

    /** Minimum samples a compass sector needs before the app will point at it. */
    public const val COMPASS_MIN_SECTOR_SAMPLES: Int = 5

    /**
     * Improvement, in dB, a compass sector must beat the all-around median by before it is
     * called "better". Below this the honest answer is "no direction is better", because a
     * 0.5 dB wiggle is multipath noise, not a wall.
     */
    public const val COMPASS_IMPROVEMENT_THRESHOLD_DB: Double = 2.0

    /** Timeout for one ICMP-style reachability check to the gateway, milliseconds. */
    public const val GATEWAY_PROBE_TIMEOUT_MILLIS: Int = 2_000

    /** Timeout for one reachability check to an internet host, milliseconds. */
    public const val INTERNET_PROBE_TIMEOUT_MILLIS: Int = 5_000

    /** Rounds the two-hop probe runs per verdict, so a single lost packet is not a diagnosis. */
    public const val TWO_HOP_ROUNDS: Int = 3

    /** Payload the mini-throughput probe fetches, bytes: 1 MB. */
    public const val THROUGHPUT_PAYLOAD_BYTES: Long = 1_000_000L

    /** Timeout for the whole mini-throughput fetch, milliseconds. */
    public const val THROUGHPUT_TIMEOUT_MILLIS: Int = 20_000

    /** Timeout for the ping round that accompanies the throughput fetch, milliseconds. */
    public const val THROUGHPUT_PING_TIMEOUT_MILLIS: Int = 3_000

    /** Ping rounds the mini-throughput probe measures, for a p50 rather than a single shot. */
    public const val THROUGHPUT_PING_ROUNDS: Int = 5

    /**
     * Throughput below which the reality check reports "data barely moves", bytes per second.
     * 62.5 kB/s is 0.5 Mbps: enough for a message, not for a video call.
     */
    public const val THROUGHPUT_POOR_BYTES_PER_SECOND: Double = 62_500.0

    /** Throughput above which the check reports comfortable headroom, bytes per second. */
    public const val THROUGHPUT_GOOD_BYTES_PER_SECOND: Double = 1_250_000.0

    /** Verdict for the mini-throughput probe. */
    public fun throughputVerdict(bytesPerSecond: Double): String = when {
        bytesPerSecond >= THROUGHPUT_GOOD_BYTES_PER_SECOND -> "GOOD"
        bytesPerSecond >= THROUGHPUT_POOR_BYTES_PER_SECOND -> "MARGINAL"
        else -> "POOR"
    }

    /** CSV columns the session log exports, in order. */
    public val CSV_COLUMNS: List<String> = listOf(
        "epochMillis",
        "networkType",
        "rsrpDbm",
        "rsrqDb",
        "rssnrDb",
        "rssiDbm",
        "timingAdvance",
        "taDistanceMeters",
        "level",
        "headingDegrees",
        "networkEvent",
    )
}

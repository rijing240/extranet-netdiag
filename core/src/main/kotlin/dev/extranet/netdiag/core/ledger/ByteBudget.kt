package dev.extranet.netdiag.core.ledger

/**
 * Storage and transfer budget for on-device capture and fleet ingestion.
 *
 * The decisive number here is [fleetRowsIfPerSamplePerMonth] versus
 * [fleetRowsIfSessionSummaryPerMonth]. Storing one row per second per device would be
 * 1.08e10 rows per month at 10k daily actives, which no free-tier relational database will
 * accept. Aggregating to one row per session on the device brings it to 300k rows per month.
 * That single comparison is why the architecture mandates on-device aggregation rather than
 * post-hoc server-side rollups.
 */
public object ByteBudget {

    /** Encoded size of one radio timeline sample: 16 scalar fields, tightly packed. */
    public const val RADIO_SAMPLE_BYTES: Int = 26

    /** Sampling rate of the radio timeline, Hz. Capped by the budget guard (X2). */
    public const val RADIO_SAMPLES_PER_SECOND: Int = 1

    /** Bytes per GNSS measurement epoch: 8 satellites x 18 bytes. */
    public const val GNSS_BYTES_PER_EPOCH: Int = 144

    /** Raw GNSS epochs per second when a burst is running. */
    public const val GNSS_EPOCHS_PER_SECOND: Int = 10

    /** Maximum duration of a raw GNSS burst, seconds. Keeps the cost at ~43 kB. */
    public const val GNSS_BURST_SECONDS: Int = 30

    /** Uncompressed size of a nominal one-hour session payload, bytes. */
    public const val SESSION_RAW_BYTES: Int = 150_000

    /** Compressed size actually uploaded, bytes (~6x reduction on structured time series). */
    public const val SESSION_COMPRESSED_BYTES: Int = 25_000

    /** Fleet size used for the plan's bandwidth figures. */
    public const val PLANNED_DAILY_ACTIVE_USERS: Int = 10_000

    /** Bytes of radio timeline captured per hour: 93,600 (93.6 kB). */
    public fun radioBytesPerHour(): Long =
        RADIO_SAMPLE_BYTES.toLong() * RADIO_SAMPLES_PER_SECOND * 3_600L

    /** Bytes captured by one capped GNSS burst: 43,200 (43.2 kB). */
    public fun gnssBurstBytes(): Long =
        GNSS_BYTES_PER_EPOCH.toLong() * GNSS_EPOCHS_PER_SECOND * GNSS_BURST_SECONDS

    /** Fleet upload per month at [dailyActiveUsers], bytes: 7.5 GB at the planned scale. */
    public fun fleetBytesPerMonth(dailyActiveUsers: Int = PLANNED_DAILY_ACTIVE_USERS): Long =
        SESSION_COMPRESSED_BYTES.toLong() * dailyActiveUsers * 30L

    /** Rows per month if every second of radio timeline became a database row: 1.08e10. */
    public fun fleetRowsIfPerSamplePerMonth(
        dailyActiveUsers: Int = PLANNED_DAILY_ACTIVE_USERS,
    ): Long = dailyActiveUsers.toLong() * 3_600L * 30L

    /** Rows per month with on-device aggregation to one summary per session: 300,000. */
    public fun fleetRowsIfSessionSummaryPerMonth(
        dailyActiveUsers: Int = PLANNED_DAILY_ACTIVE_USERS,
    ): Long = dailyActiveUsers.toLong() * 30L
}

/** Battery envelope for the budget guard (X2), expressed as percent of battery per hour. */
public object BatteryBudget {

    /** Expected drain during an active foreground session, percent per hour. */
    public const val ACTIVE_SESSION_PERCENT_PER_HOUR_MIN: Double = 4.0
    public const val ACTIVE_SESSION_PERCENT_PER_HOUR_MAX: Double = 8.0

    /** Expected drain for opportunistic background sampling, percent per hour. */
    public const val BACKGROUND_PERCENT_PER_HOUR_MAX: Double = 1.0

    /** Background sampling interval that keeps drain under the ceiling, seconds. */
    public const val BACKGROUND_SAMPLE_INTERVAL_SECONDS: Double = 30.0
}

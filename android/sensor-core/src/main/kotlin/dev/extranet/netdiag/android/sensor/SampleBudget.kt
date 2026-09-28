package dev.extranet.netdiag.android.sensor

/**
 * X2 Budget Guard: the sampling policy that keeps capture inside the battery envelope.
 *
 * Android will kill a background app that polls the radio aggressively, and the plan's budget
 * is explicit - 4-8% battery per hour during an active session and under 1% per hour for
 * opportunistic background sampling. This object is the single place that decides how often a
 * sample is allowed, so no later batch invents its own interval.
 *
 * The policy is deliberately a pure function of session state rather than a mutable scheduler:
 * the caller owns the clock, which keeps the decision testable and keeps the actual
 * scheduling in `JobScheduler` / `TelephonyCallback` where Android can enforce it.
 */
public object SampleBudget {

    /** Radio timeline interval during a user-initiated foreground session. The 1 Hz cap. */
    public const val ACTIVE_INTERVAL_MILLIS: Long = 1_000L

    /** Interval while the device is moving but the app is backgrounded. */
    public const val MOVING_INTERVAL_MILLIS: Long = 5_000L

    /** Interval while stationary and backgrounded. Matches the plan's 1-in-30-seconds budget. */
    public const val IDLE_INTERVAL_MILLIS: Long = 30_000L

    /** Interval once the thermal governor has engaged. */
    public const val THROTTLED_INTERVAL_MILLIS: Long = 60_000L

    /**
     * `PowerManager.THERMAL_STATUS_SEVERE`. Beyond this the modem is being clocked down, so
     * measurements are both expensive and unrepresentative.
     */
    public const val THERMAL_STATUS_SEVERE: Int = 3

    /** Longest raw GNSS burst allowed, matching `ByteBudget.GNSS_BURST_SECONDS`. */
    public const val GNSS_BURST_MILLIS: Long = 30_000L

    /**
     * Chooses the sampling interval for the current state.
     *
     * @param foreground true when a user-initiated session is on screen.
     * @param moving true when the accelerometer gate reports movement.
     * @param thermalStatus `PowerManager.getCurrentThermalStatus()`, or 0 when unavailable.
     * @return interval in milliseconds; smaller means more sampling.
     */
    public fun intervalMillis(
        foreground: Boolean,
        moving: Boolean,
        thermalStatus: Int = 0,
    ): Long = when {
        isThermallyThrottled(thermalStatus) -> THROTTLED_INTERVAL_MILLIS
        foreground -> ACTIVE_INTERVAL_MILLIS
        moving -> MOVING_INTERVAL_MILLIS
        else -> IDLE_INTERVAL_MILLIS
    }

    /** True when the thermal governor should engage. */
    public fun isThermallyThrottled(thermalStatus: Int): Boolean =
        thermalStatus >= THERMAL_STATUS_SEVERE

    /** Samples per hour at a given interval, for budget reporting. */
    public fun samplesPerHour(intervalMillis: Long): Double {
        require(intervalMillis > 0) { "intervalMillis must be positive, was $intervalMillis" }
        return 3_600_000.0 / intervalMillis
    }

    /**
     * True when the movement gate should allow capture at all. This is why the accelerometer
     * is in the probe catalog: without it, background sampling burns battery for data that
     * cannot have changed meaningfully.
     */
    public fun shouldSampleInBackground(moving: Boolean, thermalStatus: Int): Boolean =
        moving && !isThermallyThrottled(thermalStatus)
}

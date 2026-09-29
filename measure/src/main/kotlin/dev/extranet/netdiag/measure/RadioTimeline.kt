package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget

/**
 * One second of radio observation, as the timeline records it.
 *
 * Every field is nullable because "the platform refused to say" and "the value is zero" are
 * different facts; a timeline that fills gaps with zeroes would draw a smooth line through
 * moments when the radio said nothing at all. [TIMING_ADVANCE_UNKNOWN] is the platform's own
 * sentinel for "no timing advance in this burst", which the A03 confirmed is a real state:
 * the radio reports it whenever data is not actively moving.
 *
 * @property networkType the telephony data radio type as reported ("LTE", "NR", ...), or null
 *   when the device is not on a cellular network at that moment.
 * @property networkEvent a transition tag ("wifi_lost", "cellular_gained", ...) when the
 *   second in question contained a connectivity change, else null.
 */
public data class RadioSample(
    public val epochMillis: Long,
    public val networkType: String?,
    public val rsrpDbm: Int?,
    public val rsrqDb: Int?,
    public val rssnrDb: Int?,
    public val rssiDbm: Int?,
    public val timingAdvance: Int?,
    public val level: Int?,
    public val headingDegrees: Double?,
    public val networkEvent: String?,
) {
    /** True when the radio reported no timing advance in this second. */
    public val timingAdvanceUnknown: Boolean
        get() = timingAdvance == TIMING_ADVANCE_UNKNOWN

    /** Distance band around the serving tower, meters, from the ledger constant. */
    public val taDistanceMeters: Double?
        get() = timingAdvance
            ?.takeIf { it != TIMING_ADVANCE_UNKNOWN && it >= 0 }
            ?.let { it * TA_STEP_METERS }

    public companion object {
        /** The platform's "not reported this burst" sentinel, seen live on the A03. */
        public const val TIMING_ADVANCE_UNKNOWN: Int = Int.MAX_VALUE

        /** Meters per timing-advance step, from the calculation ledger. */
        public const val TA_STEP_METERS: Double = 78.0709526
    }
}

/**
 * The radio timeline: a fixed-capacity ring of one-second samples.
 *
 * Capacity comes from the ledger ([TimelineBudget.RING_CAPACITY_SAMPLES], two hours at 1 Hz),
 * because a ring that grows without bound is a memory leak wearing a hat. The ring is a plain
 * synchronized structure rather than a lock-free one: writers are a single sampler thread and
 * readers are the UI thread, so contention is two threads at worst and clarity matters more
 * than the last microsecond.
 *
 * The [summary] is what survives a session: per-field counts of what the platform actually
 * said, so a report can distinguish "no signal on this phone" from "signal existed but was
 * not sampled".
 */
public class RadioTimeline {

    private val lock = Any()
    private val samples = ArrayList<RadioSample>(TimelineBudget.RING_CAPACITY_SAMPLES)
    private var dropped = 0

    /** Appends one sample, overwriting the oldest once capacity is reached. */
    public fun append(sample: RadioSample) {
        synchronized(lock) {
            if (samples.size >= TimelineBudget.RING_CAPACITY_SAMPLES) {
                samples.removeAt(0)
                dropped++
            }
            samples.add(sample)
        }
    }

    /** A snapshot in chronological order; safe to hold while the ring keeps filling. */
    public fun snapshot(): List<RadioSample> = synchronized(lock) { samples.toList() }

    /** How many samples were discarded to make room, over the lifetime of the ring. */
    public fun droppedCount(): Int = synchronized(lock) { dropped }

    /** How many samples are currently held. */
    public fun size(): Int = synchronized(lock) { samples.size }

    /** Removes every sample but keeps the drop counter, which is session history. */
    public fun clear() {
        synchronized(lock) {
            samples.clear()
        }
    }

    /**
     * Per-field availability across the ring: for each measured property, how many samples
     * carried a real value. A column that is mostly absent is a capability finding, not a
     * rendering problem, and this is what makes that visible without reading raw rows.
     */
    public fun summary(): FieldSummary {
        val snapshot = snapshot()
        return FieldSummary(
            samples = snapshot.size,
            withNetworkType = snapshot.count { it.networkType != null },
            withRsrp = snapshot.count { it.rsrpDbm != null },
            withRsrq = snapshot.count { it.rsrqDb != null },
            withRssnr = snapshot.count { it.rssnrDb != null },
            withRssi = snapshot.count { it.rssiDbm != null },
            withTimingAdvance = snapshot.count { it.timingAdvance != null && !it.timingAdvanceUnknown },
            withHeading = snapshot.count { it.headingDegrees != null },
            withEvents = snapshot.count { it.networkEvent != null },
        )
    }

    /** Availability counts for one ring's worth of sampling. */
    public data class FieldSummary(
        public val samples: Int,
        public val withNetworkType: Int,
        public val withRsrp: Int,
        public val withRsrq: Int,
        public val withRssnr: Int,
        public val withRssi: Int,
        public val withTimingAdvance: Int,
        public val withHeading: Int,
        public val withEvents: Int,
    ) {
        /** Fraction of samples carrying an RSRP, 0.0 when nothing was sampled. */
        public val rsrpCoverage: Double
            get() = if (samples == 0) 0.0 else withRsrp.toDouble() / samples
    }
}

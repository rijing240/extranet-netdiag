package dev.extranet.netdiag.measure

/**
 * The walking companion for a phone with no compass.
 *
 * Without a magnetometer a phone cannot know which way it faces, so it cannot point. What it can
 * do is remember: the strongest spot reached so far, and how many steps ago that was. That turns
 * "the signal is a bit worse than a minute ago" into something a person can act on - "go back
 * about 12 steps" - using only the radio and the accelerometer's step count.
 *
 * Readings are smoothed with a short median before they are compared, so one noisy report cannot
 * crown a best spot or send the user back for nothing, and advice waits for [minReadings]
 * readings so a walk that has barely begun is not told anything.
 */
public class WalkTracker(
    private val smoothing: Int = 3,
    private val minGainDb: Double = 3.0,
    private val atBestToleranceDb: Double = 1.5,
    private val minReadings: Int = 6,
    private val historyLimit: Int = 160,
) {
    init {
        require(smoothing >= 1) { "smoothing needs at least one reading" }
    }

    /** A smoothed reading and the step count it was taken at. */
    public data class Point(
        public val steps: Int,
        public val dbm: Double,
    )

    /** What the walker should do next. */
    public enum class AdviceKind {
        /** Too few readings so far to say anything. */
        LEARNING,

        /** The current spot is as good as any reached so far. */
        AT_BEST,

        /** A clearly better spot was passed; [Advice.stepsBack] says how far. */
        GO_BACK,

        /** Not at the best spot, but not clearly worse either: keep exploring. */
        KEEP_EXPLORING,
    }

    /** The advice and the numbers behind it. */
    public data class Advice(
        public val kind: AdviceKind,
        /** Steps to walk back to the best spot; null unless [kind] is [AdviceKind.GO_BACK]. */
        public val stepsBack: Int?,
        /** How much stronger the best spot was than here, in dB; null when not [AdviceKind.GO_BACK]. */
        public val gainDb: Double?,
    )

    /** The walk as it stands now. */
    public data class Snapshot(
        public val current: Point?,
        public val best: Point?,
        public val history: List<Point>,
        public val advice: Advice,
    )

    private val recent = ArrayDeque<Int>()
    private val history = ArrayDeque<Point>()
    private var best: Point? = null
    private var current: Point? = null

    /**
     * The last snapshot, rebuilt only when the walk actually changes.
     *
     * The instrument reads this once per drawn frame - sixty times a second - while the walk
     * itself changes once a reading arrives, about once a second. Copying [historyLimit] points
     * into a fresh list sixty times a second put real work on the frame callback and threw away
     * the identity of an unchanged value, so everything downstream redrew for nothing. This is
     * the same caching [dev.extranet.netdiag.measure.RadarTracker] does for the same reason.
     */
    private var cached: Snapshot? = null

    /** Adds one reading; a null is a gap in the evidence and is skipped, never counted as weak. */
    public fun append(dbm: Int?, steps: Int) {
        if (dbm == null) return
        recent.addLast(dbm)
        while (recent.size > smoothing) recent.removeFirst()

        val sorted = recent.sorted()
        val mid = sorted.size / 2
        val smoothed = if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0

        val point = Point(steps, smoothed)
        current = point
        history.addLast(point)
        while (history.size > historyLimit) history.removeFirst()

        // A tie goes to the newer point: if two spots are equally good, the one nearer to here
        // is the one worth walking back to.
        val currentBest = best
        if (currentBest == null || smoothed >= currentBest.dbm) best = point
        cached = null
    }

    /** Clears the walk; a new scan must not inherit the last walk's best spot. */
    public fun reset() {
        recent.clear()
        history.clear()
        best = null
        current = null
        cached = null
    }

    /** The walk as it stands now; the same instance until the next reading arrives. */
    public fun snapshot(): Snapshot = cached
        ?: Snapshot(current, best, history.toList(), advice()).also { cached = it }

    private fun advice(): Advice {
        val here = current
        val top = best
        if (here == null || top == null || history.size < minReadings) {
            return Advice(AdviceKind.LEARNING, null, null)
        }
        val gain = top.dbm - here.dbm
        return when {
            gain <= atBestToleranceDb -> Advice(AdviceKind.AT_BEST, null, null)
            gain >= minGainDb -> Advice(
                kind = AdviceKind.GO_BACK,
                stepsBack = (here.steps - top.steps).coerceAtLeast(0),
                gainDb = gain,
            )
            else -> Advice(AdviceKind.KEEP_EXPLORING, null, null)
        }
    }
}

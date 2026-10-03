package dev.extranet.netdiag.measure

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The radar's memory and its estimator: which way the phone faced, how strong the signal was
 * then, and the one direction the evidence supports.
 *
 * **What an observation is.** One observation is one independent measurement from the radio,
 * filed under the heading the phone had when that measurement was *taken* (the caller looks the
 * heading up in a [HeadingHistory], it does not use the heading of the moment the number
 * arrived). Observations are never created per drawn frame: sixty copies of one reading are not
 * sixty measurements, and counting them as such is how a radar ends up certain of a direction
 * that one noisy number invented.
 *
 * **What the estimator is.** A weighted least-squares fit of
 *
 *     strength(heading) = c + a cos(heading) + b sin(heading) + g * time
 *
 * The cosine pair is the smooth "stronger one way, weaker the other" shape that a body or a wall
 * puts on the signal; its peak is the bearing. The time term absorbs a signal that simply drifted
 * while the user turned, which would otherwise be mistaken for a direction. From the fit's
 * covariance the tracker derives the bearing's own standard error, so the answer is a bearing
 * *and* how far to trust it - and a direction is claimed only when the swing is statistically
 * distinguishable from noise (a Wald test against the exact F tail, with the covariance widened
 * by the residuals' autocorrelation, because fading is not independent from one second to the
 * next).
 *
 * **What it refuses to do.** Claim a direction before the circle is measured (coverage and the
 * largest unmeasured gap), claim one when the swing is under [minContrastDb] peak to trough,
 * claim one whose error bar is wider than [maxBearingErrorDegrees], claim one that disagrees
 * with where the raw bins say the best direction is (a two-lobed room, where a single smooth
 * curve would point between the lobes), or claim one that has not held steady over
 * [confirmations] readings in a row. Each refusal is a [Status], never a guess.
 *
 * The defaults were chosen by replaying that pipeline rather than by feel, and the replay is kept
 * as a test so the numbers are re-measured rather than remembered - see RadarRefusalReplayTest.
 * One reading a second while the phone turns at 25 degrees a second, the radio's 700 ms delay
 * charged the way the engine charges it, fading of 1.5 dB correlated from second to second, and
 * a real 6 dB lobe:
 *
 * - one lap of 14 seconds never claims. About fourteen readings reach the tracker and the floor
 *   is sixteen effective ones, so the honest answer on a single lap is "keep turning";
 * - two laps claims after 22 seconds, and holds the claim for the rest of the scan;
 * - four laps with a lobe claims in eleven scans of twelve, and four laps with nothing in them
 *   claims once in a hundred and fifty.
 *
 * The significance level was loosened from one in a thousand to three in a thousand on the
 * strength of that table: it turned a third of the scans that used to end in "no clear direction"
 * on a signal that had a real direction in it into answers, and cost nothing measurable in false
 * claims, because a claim still has to repeat its direction over four readings before it is shown.
 * The autocorrelation floor was left where it was: lowering it buys claims by charging every scan
 * less for correlation that really is there, which the same replay showed as a four to seven per
 * cent false-claim rate, twenty times the promise.
 *
 * Directions are bearings in degrees clockwise from north, 0 up to 360. Circular quantities are
 * handled as angles on a circle throughout: the mean of 350 and 10 degrees is north.
 */
public class RadarTracker(
    public val binCount: Int = DEFAULT_BIN_COUNT,
    private val decayPerSecond: Double = 0.985,
    private val minBinWeight: Double = 0.25,
    private val minCoverage: Double = 0.6,
    private val maxGapDegrees: Double = 90.0,
    private val minContrastDb: Double = 3.0,
    private val minReadings: Double = 16.0,
    private val falseAlarm: Double = 0.003,
    private val maxBearingErrorDegrees: Double = 35.0,
    private val lagUncertaintySeconds: Double = 0.35,
    private val capacity: Int = 400,
    private val minAutocorrelation: Double = 0.65,
    private val maxDisagreementDegrees: Double = 45.0,
    private val confirmations: Int = 4,
) {
    init {
        require(binCount >= 8) { "a radar needs at least 8 bins, got $binCount" }
        require(decayPerSecond in 0.0..1.0) { "decay is a per-second factor, got $decayPerSecond" }
        require(capacity >= 16) { "a radar must keep at least 16 observations, got $capacity" }
    }

    /** What the radar can currently say. */
    public enum class Status {
        /** Too little of the circle is measured, or the bearing is still too uncertain to claim. */
        NEED_TURNING,

        /** The circle is measured and no direction stands out, or the readings disagree. */
        NO_CLEAR_WINNER,

        /** A direction stands out; [Snapshot.bearingDegrees] is set. */
        FOUND,
    }

    /**
     * Why no bearing was claimed.
     *
     * A refusal is not one thing. There are six different reasons this estimator will decline to
     * name a direction, and they call for six different actions from the person holding the phone:
     * keep turning, keep turning for longer, take a slower pace, stand still, find a spot with a
     * stronger difference, or wait a second while the answer settles. Reporting only [Status]
     * collapses those into one sentence, which is how a user ends up being told "the readings
     * disagree" when in fact they have covered only half the circle - advice that cannot be
     * followed, because it is advice about the wrong problem.
     *
     * The reason is derived from the gate that actually fired, never predicted or guessed.
     */
    public enum class Refusal {
        /** Not enough of the circle has been measured to say anything about a direction. */
        NOT_TURNING,

        /** Most of the circle is measured but one stretch of it is blank; turning is still needed. */
        UNMEASURED_ARC,

        /** The circle is covered, but too few independent readings are behind it to trust it. */
        NOT_ENOUGH_READINGS,

        /** A swing is there but it is too small to steer by, or not distinguishable from noise. */
        NOT_ENOUGH_DIFFERENCE,

        /** A direction is visible but its error bar is wider than the app will claim. */
        TOO_UNCERTAIN,

        /** The smooth fit and the strongest measured direction disagree: more than one lobe. */
        MORE_THAN_ONE_DIRECTION,

        /** The direction is there; it is being held back until it has held steady. */
        CONFIRMING,
    }

    /** One measured bin. Unmeasured bins are null in [Snapshot.bins], never a zero. */
    public data class Bin(
        public val index: Int,
        public val centerDegrees: Double,
        public val meanDbm: Double,
        public val weight: Double,
    )

    /** Everything the radar draws and says, computed from one instant of the tracker. */
    public data class Snapshot(
        public val bins: List<Bin?>,
        /** Fraction of the circle measured, 0.0 to 1.0. */
        public val coverage: Double,
        /** Bearing of the strongest direction, or null unless [status] is [Status.FOUND]. */
        public val bearingDegrees: Double?,
        /**
         * Peak to trough of the fitted swing, in dB: how much stronger the best direction is than
         * the weakest. Zero until a fit exists.
         */
        public val contrastDb: Double,
        public val status: Status,
        public val minDbm: Double?,
        public val maxDbm: Double?,
        /** One standard error of [bearingDegrees], in degrees; null unless it was claimed. */
        public val bearingErrorDegrees: Double? = null,
        /** How many independent readings the answer rests on, after ageing and smear. */
        public val effectiveReadings: Double = 0.0,
        /** The widest stretch of the circle with no measurement in it, in degrees. */
        public val largestGapDegrees: Double = 360.0,
        /**
         * Why the status is not [Status.FOUND], or null when it is.
         *
         * Kept beside the numbers the gate compared, so the screen can show the user the reason
         * and the reading behind it rather than a single sentence that fits every case.
         */
        public val refusal: Refusal? = null,
    )

    private class Observation(
        val timeMillis: Long,
        val theta: Double,
        val arc: Double,
        val rate: Double,
        val dbm: Double,
        val weight: Double,
    )

    private class Fit(
        val a: Double,
        val b: Double,
        val covAA: Double,
        val covBB: Double,
        val covAB: Double,
        val trendPerTau: Double,
        val meanTimeMillis: Double,
        val usedTrend: Boolean,
        /** Degrees of freedom the residual variance was estimated with. */
        val dof: Double,
    ) {
        val amplitude: Double get() = hypot(a, b)
    }

    private val observations = ArrayList<Observation>()

    // The radar re-tests after every reading, and a test that is re-run many times will pass by
    // luck eventually. A direction is therefore only claimed once it has been found on several
    // readings in a row at a steady bearing; noise does not repeat itself that obediently.
    private var cached: Snapshot? = null
    private var streak = 0
    private var streakBearing: Double? = null

    /** Width of one bin in degrees. */
    public val binWidthDegrees: Double
        get() = 360.0 / binCount

    /** How many observations are held. */
    public val observationCount: Int
        get() = observations.size

    /**
     * Adds one radio measurement, filed under the heading it was taken at.
     *
     * @param nowMillis when the measurement happened, on any monotonic clock the caller uses
     *   consistently.
     * @param headingDegrees the phone's heading at that moment.
     * @param weight trust in the reading, 0 up to 1: lower for a reading taken while the compass
     *   was uncertain or the phone was swinging.
     * @param arcDegrees how far the phone swept while the radio was averaging this reading; the
     *   reading describes that whole arc, so the bins under it are painted together.
     * @param turnRateDegPerSec how fast the phone was turning, for the bearing's error bar: a
     *   timing error of a few hundred milliseconds is a larger angular error the faster the turn.
     */
    public fun observe(
        nowMillis: Long,
        headingDegrees: Double,
        dbm: Double,
        weight: Double = 1.0,
        arcDegrees: Double = 0.0,
        turnRateDegPerSec: Double = 0.0,
    ) {
        require(weight >= 0.0) { "a weight cannot be negative, got $weight" }
        if (weight == 0.0 || headingDegrees.isNaN() || dbm.isNaN()) return
        observations.add(
            Observation(
                timeMillis = nowMillis,
                theta = normalize(headingDegrees),
                arc = arcDegrees.coerceIn(0.0, 180.0),
                rate = abs(turnRateDegPerSec),
                dbm = dbm,
                weight = weight,
            ),
        )
        if (observations.size > capacity) observations.removeAt(0)
        cached = confirm(compute())
    }

    /**
     * One pass of the estimator: what it would say, and the bearing it saw while saying it.
     *
     * The two are separate because they are separate questions. "Is there a direction here" is
     * answered by the swing against the noise; "is that direction pinned down well enough to
     * publish" is answered by its error bar. A pass can be certain a direction exists and still
     * decline to name it, and the confirmation counter needs to know that a direction was seen -
     * see [confirm].
     */
    private class Pass(val snapshot: Snapshot, val bearingSeen: Double?)

    /** Forgets everything; a new scan must not inherit the last one's lobes. */
    public fun reset() {
        observations.clear()
        cached = null
        streak = 0
        streakBearing = null
    }

    /**
     * Counts consecutive readings that found the same direction, and holds the claim back until
     * there are [confirmations] of them. Until then the answer is "keep turning", which is true:
     * the radar is still confirming.
     */
    private fun confirm(pass: Pass): Snapshot {
        val raw = pass.snapshot
        // A reading whose error bar was too wide to publish still saw a direction, and that counts
        // towards the run: the counter exists to catch a bearing that does not repeat itself, and
        // this one repeated itself. What must reset it is the direction *changing* or the evidence
        // for a direction disappearing - not the same direction being seen with a wider bar on one
        // of the four readings. Charging both made the rule far stricter than the four readings
        // the spec asks for, and it threw away stable directions in scans that were doing fine.
        val bearing = raw.bearingDegrees ?: pass.bearingSeen
        if (bearing == null) {
            streak = 0
            streakBearing = null
            return raw
        }
        val previous = streakBearing
        streak = if (previous != null && circularDistance(previous, bearing) <= STEADY_DEGREES) streak + 1 else 1
        streakBearing = bearing
        if (raw.status != Status.FOUND) return raw
        return if (streak >= confirmations) {
            raw
        } else {
            raw.copy(
                status = Status.NEED_TURNING,
                bearingDegrees = null,
                bearingErrorDegrees = null,
                refusal = Refusal.CONFIRMING,
            )
        }
    }

    /** The radar as it stands now, aged to the time of the newest observation. */
    public fun snapshot(): Snapshot = cached ?: compute().snapshot

    private fun compute(): Pass {
        val width = binWidthDegrees
        if (observations.isEmpty()) {
            return Pass(
                snapshot = Snapshot(
                    bins = List(binCount) { null },
                    coverage = 0.0,
                    bearingDegrees = null,
                    contrastDb = 0.0,
                    status = Status.NEED_TURNING,
                    minDbm = null,
                    maxDbm = null,
                    refusal = Refusal.NOT_TURNING,
                ),
                bearingSeen = null,
            )
        }

        val count = observations.size
        val newest = observations.maxOf { it.timeMillis }
        val weights = DoubleArray(count) { i ->
            val o = observations[i]
            o.weight * decayPerSecond.pow((newest - o.timeMillis) / 1_000.0)
        }
        val sumWeights = weights.sum()
        val sumSquares = weights.sumOf { it * it }
        val effective = if (sumSquares > 0.0) sumWeights * sumWeights / sumSquares else 0.0

        val fit = if (sumWeights > 0.0) fit(weights, sumWeights, effective) else null

        // Bins, from the readings with any drift removed so a fading signal does not paint the
        // circle as a gradient. Each reading is spread over the arc the phone swept while the
        // radio averaged it; a bin counts as measured once enough reading-weight has touched it.
        val spreadWeight = DoubleArray(binCount)
        val spreadSum = DoubleArray(binCount)
        val touched = DoubleArray(binCount)
        for (i in 0 until count) {
            val o = observations[i]
            val w = weights[i]
            if (w <= 0.0) continue
            val y = o.dbm - driftAt(fit, o.timeMillis)
            val steps = max(1, ceil(o.arc / (width / 4.0)).toInt())
            val seen = HashSet<Int>()
            for (s in 0 until steps) {
                val offset = if (steps == 1) 0.0 else -o.arc / 2.0 + o.arc * s / (steps - 1)
                val bin = binOf(o.theta + offset)
                spreadWeight[bin] += w / steps
                spreadSum[bin] += w / steps * y
                seen.add(bin)
            }
            for (bin in seen) touched[bin] += w
        }

        val bins: List<Bin?> = List(binCount) { i ->
            if (touched[i] >= minBinWeight && spreadWeight[i] > 0.0) {
                Bin(i, (i + 0.5) * width, spreadSum[i] / spreadWeight[i], touched[i])
            } else {
                null
            }
        }
        val measured = bins.filterNotNull()
        val coverage = measured.size.toDouble() / binCount
        val gap = largestGap(bins) * width
        val minDbm = measured.minOfOrNull { it.meanDbm }
        val maxDbm = measured.maxOfOrNull { it.meanDbm }
        val contrast = fit?.let { 2.0 * it.amplitude } ?: 0.0

        fun snapshot(
            status: Status,
            bearing: Double? = null,
            error: Double? = null,
            refusal: Refusal? = null,
        ) = Snapshot(
            bins = bins,
            coverage = coverage,
            bearingDegrees = bearing,
            contrastDb = contrast,
            status = status,
            minDbm = minDbm,
            maxDbm = maxDbm,
            bearingErrorDegrees = error,
            effectiveReadings = effective,
            largestGapDegrees = gap,
            refusal = refusal,
        )

        // Each gate is reported separately rather than folded into one condition, because the
        // screen has to be able to tell the user which one it is: "turn further round" and "turn
        // again and slower" are different instructions and only one of them helps in each case.
        val refusal = when {
            measured.isEmpty() -> Refusal.NOT_TURNING
            coverage < minCoverage -> Refusal.NOT_TURNING
            gap > maxGapDegrees -> Refusal.UNMEASURED_ARC
            effective < minReadings -> Refusal.NOT_ENOUGH_READINGS
            fit == null -> Refusal.NOT_ENOUGH_READINGS
            else -> null
        }
        if (refusal != null) return Pass(snapshot(Status.NEED_TURNING, refusal = refusal), null)
        // Named for the rest of the method: the gate above proved it is not null, and naming it
        // is what keeps that proof alive for the compiler now that the check is a `when`.
        val model = fit ?: return Pass(
            snapshot(Status.NEED_TURNING, refusal = Refusal.NOT_ENOUGH_READINGS),
            null,
        )

        // Is there a direction at all? A Wald test on the cosine pair, plus a floor on the swing
        // itself: a statistically real but tiny swing is not worth sending anyone anywhere for.
        val det = model.covAA * model.covBB - model.covAB * model.covAB
        if (det <= 0.0) {
            return Pass(snapshot(Status.NEED_TURNING, refusal = Refusal.NOT_ENOUGH_READINGS), null)
        }
        val chiSquare = (model.a * model.a * model.covBB - 2.0 * model.a * model.b * model.covAB + model.b * model.b * model.covAA) / det
        if (chiSquare < threshold(model.dof) || contrast < minContrastDb) {
            return Pass(
                snapshot(Status.NO_CLEAR_WINNER, refusal = Refusal.NOT_ENOUGH_DIFFERENCE),
                null,
            )
        }

        val bearing = normalize(Math.toDegrees(atan2(model.b, model.a)))
        val amplitudeSquared = model.amplitude * model.amplitude
        val phaseVariance = (model.b * model.b * model.covAA + model.a * model.a * model.covBB - 2.0 * model.a * model.b * model.covAB) /
            (amplitudeSquared * amplitudeSquared)
        val fitErrorDegrees = Math.toDegrees(sqrt(max(phaseVariance, 0.0)))
        val meanRate = run {
            var numerator = 0.0
            for (i in 0 until count) numerator += weights[i] * observations[i].rate
            numerator / sumWeights
        }
        val error = sqrt(fitErrorDegrees * fitErrorDegrees + (meanRate * lagUncertaintySeconds).pow(2))
        if (error > maxBearingErrorDegrees) {
            // The direction was seen; only its error bar was too wide to publish. Handing it
            // on lets the confirmation counter see that this reading agreed with the last
            // ones rather than forgetting the run.
            return Pass(
                snapshot(Status.NEED_TURNING, error = error, refusal = Refusal.TOO_UNCERTAIN),
                bearing,
            )
        }

        // Cross-check against the raw bins: if the smooth curve and the strongest measured
        // direction disagree by more than a lobe's width, the room has more than one lobe and a
        // single bearing would be a compromise between them.
        val strongest = strongestBinBearing(measured)
        if (strongest != null && circularDistance(strongest, bearing) > maxDisagreementDegrees) {
            return Pass(
                snapshot(Status.NO_CLEAR_WINNER, refusal = Refusal.MORE_THAN_ONE_DIRECTION),
                null,
            )
        }
        return Pass(snapshot(Status.FOUND, bearing, error), bearing)
    }

    /**
     * The Wald statistic a swing must beat to count as a direction.
     *
     * The statistic is two times an F(2, dof) variable, and for two numerator degrees of freedom
     * the tail has a closed form, P(F > f) = (1 + 2f/dof)^(-dof/2), so the critical value for a
     * false-alarm probability p is f = (dof/2)(p^(-2/dof) - 1). Using the exact tail rather than
     * a fixed number matters early in a scan: with ten readings a fixed "15" lets noise through
     * far more often than it looks, because the variance itself is still a rough estimate.
     * [falseAlarm] is small on purpose, because the radar re-tests after every reading and a
     * one-in-twenty chance per look becomes a near certainty over a scan.
     */
    private fun threshold(dof: Double): Double {
        val d = max(dof, 1.0)
        return 2.0 * (d / 2.0) * (falseAlarm.pow(-2.0 / d) - 1.0)
    }

    /** The drift the fit removed at [timeMillis], in dB; zero when no trend was fitted. */
    private fun driftAt(fit: Fit?, timeMillis: Long): Double =
        if (fit == null || !fit.usedTrend) 0.0 else fit.trendPerTau * (timeMillis - fit.meanTimeMillis) / TAU_MILLIS

    private fun fit(weights: DoubleArray, sumWeights: Double, effective: Double): Fit? {
        val count = observations.size
        val significant = observations.indices.filter { weights[it] >= 0.05 * (sumWeights / count) }
        if (significant.size < 6) return null
        val first = significant.minOf { observations[it].timeMillis }
        val last = significant.maxOf { observations[it].timeMillis }
        val useTrend = last - first >= MIN_TREND_SPAN_MILLIS
        val parameters = if (useTrend) 4 else 3
        if (effective - parameters < 1.0) return null

        val meanTime = significant.sumOf { observations[it].timeMillis.toDouble() * weights[it] } /
            significant.sumOf { weights[it] }
        val scale = count / sumWeights

        val normal = Array(parameters) { DoubleArray(parameters) }
        val moment = DoubleArray(parameters)
        val row = DoubleArray(parameters)
        fun regressors(o: Observation) {
            val radians = Math.toRadians(o.theta)
            row[0] = 1.0
            row[1] = cos(radians)
            row[2] = sin(radians)
            if (useTrend) row[3] = (o.timeMillis - meanTime) / TAU_MILLIS
        }
        for (i in 0 until count) {
            val w = weights[i] * scale
            if (w <= 0.0) continue
            val o = observations[i]
            regressors(o)
            for (r in 0 until parameters) {
                moment[r] += w * row[r] * o.dbm
                for (c in 0 until parameters) normal[r][c] += w * row[r] * row[c]
            }
        }
        val inverse = invert(normal) ?: return null
        val beta = DoubleArray(parameters) { r ->
            var s = 0.0
            for (c in 0 until parameters) s += inverse[r][c] * moment[c]
            s
        }

        var sse = 0.0
        val standardized = DoubleArray(count)
        for (i in 0 until count) {
            val w = weights[i] * scale
            if (w <= 0.0) continue
            val o = observations[i]
            regressors(o)
            var predicted = 0.0
            for (k in 0 until parameters) predicted += beta[k] * row[k]
            val residual = o.dbm - predicted
            sse += w * residual * residual
            standardized[i] = residual * sqrt(w)
        }
        // Readings arrive as whole dB, so even a perfect fit leaves 1/12 dB^2 of rounding: the
        // variance may not fall below it, or a noiseless test would claim infinite certainty.
        val variance = max(sse / (effective - parameters), QUANTISATION_VARIANCE)

        // Fading is correlated from one second to the next, so neighbouring residuals are not
        // independent evidence. Widening the covariance by (1+r)/(1-r) is the standard
        // correction for first-order autocorrelation. The correlation is floored because with a
        // few dozen readings its own estimate is too noisy to be allowed to say "none".
        var cross = 0.0
        var before = 0.0
        var after = 0.0
        var pairs = 0
        for (i in 1 until count) {
            val gapMillis = observations[i].timeMillis - observations[i - 1].timeMillis
            if (gapMillis in 50L..4_000L && weights[i] > 0.0 && weights[i - 1] > 0.0) {
                cross += standardized[i] * standardized[i - 1]
                before += standardized[i - 1] * standardized[i - 1]
                after += standardized[i] * standardized[i]
                pairs++
            }
        }
        val rho = if (pairs >= 8 && before > 0.0 && after > 0.0) {
            (cross / sqrt(before * after)).coerceIn(minAutocorrelation, MAX_AUTOCORRELATION)
        } else {
            minAutocorrelation
        }
        val inflation = (1.0 + rho) / (1.0 - rho)
        val factor = variance * inflation

        return Fit(
            a = beta[1],
            b = beta[2],
            covAA = factor * inverse[1][1],
            covBB = factor * inverse[2][2],
            covAB = factor * inverse[1][2],
            trendPerTau = if (useTrend) beta[3] else 0.0,
            meanTimeMillis = meanTime,
            usedTrend = useTrend,
            dof = effective - parameters,
        )
    }

    /** Gauss-Jordan inverse with partial pivoting; null when the matrix is singular. */
    private fun invert(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val work = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) matrix[r][c] else if (c - n == r) 1.0 else 0.0 } }
        for (column in 0 until n) {
            var pivot = column
            for (r in column + 1 until n) if (abs(work[r][column]) > abs(work[pivot][column])) pivot = r
            if (abs(work[pivot][column]) < 1e-9) return null
            val swap = work[column]
            work[column] = work[pivot]
            work[pivot] = swap
            val divisor = work[column][column]
            for (c in 0 until 2 * n) work[column][c] /= divisor
            for (r in 0 until n) {
                if (r == column) continue
                val factor = work[r][column]
                if (factor == 0.0) continue
                for (c in 0 until 2 * n) work[r][c] -= factor * work[column][c]
            }
        }
        return Array(n) { r -> DoubleArray(n) { c -> work[r][n + c] } }
    }

    private fun binOf(headingDegrees: Double): Int =
        (normalize(headingDegrees) / binWidthDegrees).toInt().coerceIn(0, binCount - 1)

    /** The longest run of consecutive unmeasured bins, wrapping round the circle. */
    private fun largestGap(bins: List<Bin?>): Int {
        if (bins.all { it == null }) return binCount
        var longest = 0
        var run = 0
        for (i in 0 until 2 * binCount) {
            if (bins[i % binCount] == null) {
                run++
                longest = max(longest, run)
            } else {
                run = 0
            }
        }
        return longest.coerceAtMost(binCount)
    }

    /** The bearing of the strongest measured bin after light smoothing with its neighbours. */
    private fun strongestBinBearing(measured: List<Bin>): Double? {
        if (measured.isEmpty()) return null
        val byIndex = arrayOfNulls<Bin>(binCount)
        for (bin in measured) byIndex[bin.index] = bin
        var bestIndex = -1
        var bestValue = Double.NEGATIVE_INFINITY
        for (bin in measured) {
            var numerator = 0.0
            var denominator = 0.0
            for (k in -2..2) {
                val neighbour = byIndex[((bin.index + k) % binCount + binCount) % binCount] ?: continue
                numerator += KERNEL[k + 2] * neighbour.meanDbm
                denominator += KERNEL[k + 2]
            }
            val smoothed = numerator / denominator
            if (smoothed > bestValue) {
                bestValue = smoothed
                bestIndex = bin.index
            }
        }
        return if (bestIndex < 0) null else (bestIndex + 0.5) * binWidthDegrees
    }

    public companion object {
        /** 24 bins: 15 degrees each, fine enough to draw and steer by. */
        public const val DEFAULT_BIN_COUNT: Int = 24

        /** The unit the drift term is fitted in: ten seconds. */
        private const val TAU_MILLIS: Double = 10_000.0

        /** A drift can only be told from a direction when the scan has run this long. */
        private const val MIN_TREND_SPAN_MILLIS: Long = 8_000L

        /** Rounding a reading to whole dB leaves a variance of 1/12 dB squared. */
        private const val QUANTISATION_VARIANCE: Double = 1.0 / 12.0

        private const val MAX_AUTOCORRELATION: Double = 0.85

        /** Successive claims within this many degrees of each other count as the same direction. */
        private const val STEADY_DEGREES: Double = 25.0

        private val KERNEL: DoubleArray = doubleArrayOf(0.2, 0.6, 1.0, 0.6, 0.2)

        /** Compass heading folded into 0 (inclusive) to 360 (exclusive). */
        public fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

        /**
         * The shortest turn from [from] to [to], in degrees: positive is clockwise (turn right),
         * negative is anticlockwise (turn left), always within -180 up to 180.
         */
        public fun turnDegrees(from: Double, to: Double): Double =
            ((to - from + 540.0) % 360.0 + 360.0) % 360.0 - 180.0

        /** How far apart two bearings are, ignoring direction, 0 up to 180. */
        public fun circularDistance(a: Double, b: Double): Double = abs(turnDegrees(a, b))
    }
}

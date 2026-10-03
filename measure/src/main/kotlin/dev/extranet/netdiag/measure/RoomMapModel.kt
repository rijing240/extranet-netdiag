package dev.extranet.netdiag.measure

import kotlin.math.hypot
import kotlin.math.max

/**
 * The Room Map's trail of places and the signal measured at each: a walk turned into a picture.
 *
 * What it is, and what it deliberately is not. The dots it holds are where the user stood and how
 * strong the radio was there, laid out in the walker's own frame. It is not a floor plan, it never
 * draws a wall, and it does not know where the room is - only where the person went. A trail that
 * says "stronger over here than over there" is useful and true; a trail that looks like a floor
 * plan would be believed, and would be wrong, because dead reckoning drifts and the drift grows
 * with every step.
 *
 * Three decisions carry most of the honesty:
 *
 * **Colour is relative to this walk, never absolute.** There is no dBm at which a room is
 * "bad" - a Wi-Fi reading of -75 is a weak signal in a flat and a perfectly usable one in a
 * concrete basement, and a mobile RSRP of -100 is unusable on one network band and ordinary on
 * another. So the map sorts its own readings and colours by rank: the strongest third of what was
 * measured here is green, the weakest third red. That is a claim the data can support, because it
 * is a claim about *this* walk.
 *
 * **A flat walk is not coloured at all.** If the spread between the best and worst reading is
 * under [NEUTRAL_SPREAD_DB], ranking them still produces thirds, and those thirds are noise
 * dressed as an answer - "stronger over here" where nothing differs by more than a rounding
 * error. Under that spread every dot is drawn neutral and the screen says the signal is about the
 * same everywhere, which is the true result of the walk.
 *
 * **One reading cannot crown a spot.** The best and weakest spots are found on a median of each
 * dot's two nearest neighbours, so a single reading that landed high because the phone was
 * between two walls does not get a star on it.
 */
public class RoomMapModel(private val capacity: Int = MAX_DOTS) {

    init {
        require(capacity >= MIN_DOTS_FOR_COLOUR) {
            "a map needs room for at least $MIN_DOTS_FOR_COLOUR dots, got $capacity"
        }
    }

    /** One measurement, at the place it was taken. */
    public data class Dot(
        public val timeMillis: Long,
        public val xMeters: Double,
        public val yMeters: Double,
        public val dbm: Double,
        /**
         * True when the position is the last known one rather than an interpolated fix, because
         * the walker paused and the trail could not say where inside the pause they stood.
         */
        public val lowConfidence: Boolean = false,
        /**
         * When this dot was filed, which is later than [timeMillis] by the radio's own delay.
         *
         * Kept separately because the two timestamps answer different questions. [timeMillis] is
         * where the dot belongs on the trail and is measured backwards from the arrival;
         * this is when the user gets to see it, and is what the fade-in is drawn from. Using the
         * measurement time for the fade would have every dot appear already half-faded, because
         * by the time the radio reports a reading it is already most of a second old.
         */
        public val arrivalMillis: Long = timeMillis,
    )

    /** A dot as drawn: its colour rank, its age, and whether it holds a crown. */
    public data class Placed(
        public val dot: Dot,
        /** 0 weakest third, 1 middle third, 2 strongest third; null when the walk is not coloured. */
        public val bucket: Int?,
        /** Fade with age, newest at one. */
        public val alpha: Float,
        public val best: Boolean,
        public val weakest: Boolean,
    )

    /** The box the trail fits in, for the Canvas to scale to. */
    public data class Bounds(
        public val minX: Double,
        public val maxX: Double,
        public val minY: Double,
        public val maxY: Double,
    )

    /** Everything the renderer draws and the caption says, from one instant of the trail. */
    public data class Snapshot(
        public val dots: List<Placed>,
        /** False until enough readings exist to compare; the screen shows grey and says keep walking. */
        public val coloured: Boolean,
        /** True when the readings are so close together that ranking them would be noise. */
        public val neutral: Boolean,
        public val spreadDb: Double,
        public val best: Placed?,
        public val weakest: Placed?,
        public val bounds: Bounds?,
    )

    private val lock = Any()
    private val dots = ArrayDeque<Dot>()

    /** The values seen at the spot the walker is currently standing on, for the running median. */
    private val spotValues = ArrayList<Double>()

    /** How many dots are held. */
    public val size: Int
        get() = synchronized(lock) { dots.size }

    /** Forgets the whole walk. */
    public fun reset() {
        synchronized(lock) {
            dots.clear()
            spotValues.clear()
        }
    }

    /**
     * Files one measurement, and says whether it made a new dot.
     *
     * Standing still is the awkward case. A phone lying on a table reports a new strength every
     * second, and each of those is a genuine measurement, but they were all taken in the same
     * place - filing them separately would stack hundreds of dots on one pixel and make the map
     * look as though the user had walked the length of the room and back. So a measurement at the
     * same position as the last dot, less than [STATIONARY_INTERVAL_MILLIS] after it, updates that
     * dot's value to the median of everything measured there instead of adding another. The dot
     * represents the place, and its value is what that place reads.
     *
     * Past the dot cap the oldest is dropped. The trail is a picture of where the user went, and
     * when a walk is long enough to exceed the cap, the beginning is the part that has drifted
     * furthest and is worth the least.
     */
    public fun append(dot: Dot): Boolean = synchronized(lock) {
        val last = dots.lastOrNull()
        val samePlace = last != null && last.xMeters == dot.xMeters && last.yMeters == dot.yMeters
        if (!samePlace) spotValues.clear()

        if (samePlace && last != null && dot.timeMillis - last.timeMillis < STATIONARY_INTERVAL_MILLIS) {
            spotValues.add(dot.dbm)
            dots[dots.size - 1] = last.copy(
                timeMillis = dot.timeMillis,
                dbm = median(spotValues),
            )
            return false
        }

        spotValues.add(dot.dbm)
        dots.addLast(dot)
        while (dots.size > capacity) dots.removeFirst()
        return true
    }

    /** The map as it should be drawn now. */
    public fun snapshot(nowMillis: Long): Snapshot = synchronized(lock) {
        val values = dots.map { it.dbm }
        val spread = if (values.isEmpty()) 0.0 else ((values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0))
        val coloured = dots.size >= MIN_DOTS_FOR_COLOUR && spread >= NEUTRAL_SPREAD_DB

        val ranks = if (coloured) percentileRanks(values) else DoubleArray(dots.size)
        val smoothed = if (coloured) neighbourhoodMedians() else DoubleArray(dots.size)
        val bestIndex = if (coloured && smoothed.isNotEmpty()) smoothed.indices.maxByOrNull { smoothed[it] } ?: -1 else -1
        val weakestIndex = if (coloured && smoothed.isNotEmpty()) smoothed.indices.minByOrNull { smoothed[it] } ?: -1 else -1

        val placed = dots.mapIndexed { index, dot ->
            Placed(
                dot = dot,
                bucket = if (coloured) bucketFor(ranks[index]) else null,
                alpha = alphaFor(nowMillis - dot.timeMillis),
                // A crown is only worth giving when the crown is not on every dot: on a short
                // trail the best and worst of three readings are not findings, they are the
                // smallest and largest of three numbers.
                best = coloured && dots.size >= MIN_DOTS_FOR_A_SPOT && index == bestIndex,
                weakest = coloured && dots.size >= MIN_DOTS_FOR_A_SPOT && index == weakestIndex,
            )
        }

        val bounds = if (dots.isEmpty()) null else Bounds(
            minX = dots.minOf { it.xMeters },
            maxX = dots.maxOf { it.xMeters },
            minY = dots.minOf { it.yMeters },
            maxY = dots.maxOf { it.yMeters },
        )

        Snapshot(
            dots = placed,
            coloured = coloured,
            neutral = dots.size >= MIN_DOTS_FOR_COLOUR && !coloured,
            spreadDb = spread,
            best = placed.firstOrNull { it.best },
            weakest = placed.firstOrNull { it.weakest },
            bounds = bounds,
        )
    }

    /**
     * Where each dot sits in the ranking, 0 for the lowest and 1 for the highest.
     *
     * Ties share a rank rather than being separated by the order they happened to be stored in,
     * so two dots that read exactly the same cannot end up one green and one red.
     */
    private fun percentileRanks(values: List<Double>): DoubleArray {
        val sorted = values.sorted()
        val denominator = max(1, values.size - 1)
        return DoubleArray(values.size) { index ->
            val value = values[index]
            val first = sorted.indexOfFirst { it >= value }
            val last = sorted.indexOfLast { it <= value }
            ((first + last) / 2.0) / denominator
        }
    }

    /** Each dot's own value replaced by the median of it and its two nearest neighbours. */
    private fun neighbourhoodMedians(): DoubleArray = DoubleArray(dots.size) { index ->
        val here = dots[index]
        val nearest = dots.indices
            .filter { it != index }
            .sortedBy { hypot(dots[it].xMeters - here.xMeters, dots[it].yMeters - here.yMeters) }
            .take(NEIGHBOURHOOD - 1)
        median((listOf(here.dbm) + nearest.map { dots[it].dbm }))
    }

    private fun bucketFor(rank: Double): Int = when {
        rank < 1.0 / 3.0 -> 0
        rank < 2.0 / 3.0 -> 1
        else -> 2
    }

    /**
     * How solidly a dot is drawn, from its age.
     *
     * Older positions have had longer to drift, so they are faded rather than removed: the user
     * can still see the shape of where they walked, and can see that the beginning of the walk is
     * less trustworthy than the end of it. The floor keeps the oldest dots visible at all - a
     * trail that faded to nothing would look like data that had been lost.
     */
    private fun alphaFor(ageMillis: Long): Float {
        val fraction = (ageMillis.toDouble() / FADE_MILLIS).coerceIn(0.0, 1.0)
        return (1.0 - fraction * (1.0 - FADE_FLOOR)).toFloat()
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    public companion object {
        /**
         * How many dots the map keeps.
         *
         * Six hundred dots at a step a half-second is about five minutes of walking, which is
         * longer than the ninety-second scan the screen runs. The cap exists so that a session
         * left open by accident cannot grow without bound.
         */
        public const val MAX_DOTS: Int = 600

        /** Below this many readings, ranking them produces thirds out of nothing. */
        public const val MIN_DOTS_FOR_COLOUR: Int = 8

        /** Fewer than this and the best and worst of the trail are not worth crowning. */
        public const val MIN_DOTS_FOR_A_SPOT: Int = 12

        /**
         * The spread below which the walk is called flat, in dB.
         *
         * Six decibels is about a factor of four in power. Under it, the difference between the
         * best and worst place the user stood is smaller than the run-to-run scatter of a single
         * place measured twice, so the ranking would be reporting the scatter rather than the
         * room.
         */
        public const val NEUTRAL_SPREAD_DB: Double = 6.0

        /** How long a stationary user may re-measure one spot before a new dot is filed. */
        public const val STATIONARY_INTERVAL_MILLIS: Long = 2_000L

        /** How many values, including the dot's own, go into a spot's smoothed reading. */
        public const val NEIGHBOURHOOD: Int = 3

        /** How long it takes a dot to fade from full to the floor. */
        public const val FADE_MILLIS: Long = 120_000L

        /** The faintest a dot is ever drawn. */
        public const val FADE_FLOOR: Double = 0.35
    }
}
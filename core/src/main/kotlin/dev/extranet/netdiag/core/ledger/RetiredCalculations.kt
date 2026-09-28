package dev.extranet.netdiag.core.ledger

/**
 * One calculation that was considered, evaluated, and rejected.
 *
 * @property id stable identifier, safe to reference from docs and review comments.
 * @property name human-readable name.
 * @property formula the formula as it is normally written, for recognition.
 * @property reason why it cannot be implemented against the public Android API.
 * @property replacement what to build instead.
 */
public data class RetiredCalculation(
    public val id: String,
    public val name: String,
    public val formula: String,
    public val reason: String,
    public val replacement: String,
)

/**
 * Calculations that must not be re-derived in later batches.
 *
 * These are kept as reviewable data rather than deleted, because each one is plausible enough
 * that a future contributor will otherwise reinvent it. A unit test asserts that all three are
 * still present so the record cannot silently rot.
 */
public object RetiredCalculations {

    /** RSRP-based trilateration/multilateration to obtain a position without GNSS. */
    public val RSRP_MULTILATERATION: RetiredCalculation = RetiredCalculation(
        id = "rsrp-multilateration",
        name = "RSRP multilateration",
        formula = "min_(x,y) sum_i ( sqrt((x-xi)^2 + (y-yi)^2) - d_i )^2",
        reason = "Two independent blockers. (1) Android exposes no tower coordinates to " +
            "third-party apps, so the anchor positions x_i, y_i are unknown and would have to " +
            "come from a third-party database with 150-1000 m accuracy; Mozilla Location " +
            "Service was sunset in 2024. (2) Converting RSRP to a range needs both the " +
            "transmit power and the path-loss exponent, neither of which is observable, and " +
            "sector antennas are directional so the true constraint is an arc, not a circle. " +
            "Timing advance would be the right ranging primitive, but it is exposed for the " +
            "serving cell only, so three independent ranges cannot be obtained.",
        replacement = "Do not position at all. FusedLocationProvider already does cellular and " +
            "Wi-Fi positioning better and for free. Spend the effort on diagnosis and " +
            "prediction instead.",
    )

    /** Inverting the log-distance path-loss model to infer environmental clutter. */
    public val PATH_LOSS_EXPONENT: RetiredCalculation = RetiredCalculation(
        id = "path-loss-exponent",
        name = "Path-loss exponent inversion for clutter detection",
        formula = "RSSI = Ptx - 10n log10(d) - C, solved for n",
        reason = "Underdetermined: the equation has two unknowns (transmit power Ptx and " +
            "distance d) and one observation. It is also the wrong quantity: RSRP is " +
            "reference-signal received power, not narrowband RSSI, so the narrowband " +
            "log-distance model does not apply without re-derivation.",
        replacement = "Classify environment from signals that are directly observable and " +
            "jointly diagnostic: GNSS C/N0 collapse, barometric delta for floor changes, " +
            "a serving-band shift to low band (typically 700/800 MHz) indoors, a RAT " +
            "downgrade event, and a growing timing-advance trend.",
    )

    /** Little's Law as a congestion proof. */
    public val LITTLES_LAW: RetiredCalculation = RetiredCalculation(
        id = "littles-law",
        name = "Little's Law bufferbloat detection",
        formula = "L = lambda * W",
        reason = "An identity, not a predictor, so it cannot prove causation on its own. Its " +
            "arrival-rate term is also unobservable: NetworkStatsManager returns polled byte " +
            "counters in second-to-minute buckets, and there is no public API for per-app " +
            "packet-arrival rate.",
        replacement = "Loaded-minus-idle RTT delta, already encoded in " +
            "Bufferbloat.deltaMilliseconds. That measures the queueing delay directly and is " +
            "causal.",
    )

    /** Every retired calculation, in a stable order. */
    public val ALL: List<RetiredCalculation> = listOf(
        RSRP_MULTILATERATION,
        PATH_LOSS_EXPONENT,
        LITTLES_LAW,
    )

    /** Lookup by [RetiredCalculation.id]. */
    public fun byId(id: String): RetiredCalculation? = ALL.firstOrNull { it.id == id }
}

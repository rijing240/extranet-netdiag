package dev.extranet.netdiag.measure

/**
 * The four stages of one active probe, in the order they happen.
 *
 * The waterfall exists because a single latency number cannot say which of these is at fault.
 * Good signal plus a slow name lookup is a resolver problem; a fast lookup plus a slow handshake
 * is a path or radio problem; a healthy connection plus a late first byte is the remote server.
 * Keeping the stages separate is what makes that attribution possible at all.
 */
public enum class Layer(public val wireName: String) {

    /** Name resolution: one UDP query to the resolver the active network handed us. */
    DNS("dns"),

    /** Connect: the TCP handshake with the address the lookup returned. */
    TCP("tcp"),

    /** Handshake: TLS negotiation over the connection TCP just established. */
    TLS("tls"),

    /** First byte: the request is written, and this is how long the server took to answer. */
    TTFB("ttfb");

    /** The waterfall order, which is both the order stages run and the order they are reported. */
    public companion object {

        /** Every stage in the order it is attempted and reported. */
        public val ORDER: List<Layer> = listOf(DNS, TCP, TLS, TTFB)

        /** Looks a layer up by [wireName], for reading reports back. */
        public fun fromWireName(name: String): Layer? = ORDER.firstOrNull { it.wireName == name }
    }
}

/**
 * What happened to one stage.
 *
 * `Skipped` is deliberately not a failure. A stage that was never attempted because the network
 * supplied no resolver is a gap in what the environment offers, not a fault in the engine, and
 * the difference matters: the failure ceiling exists to catch engine breakage.
 */
public sealed interface LayerOutcome {

    /** The stage ran and finished inside its budget. */
    public data object Ok : LayerOutcome

    /** The stage ran and did not finish inside its budget. */
    public data class Timeout(public val budgetMillis: Int) : LayerOutcome

    /** The stage ran and failed for a reason that is not a timeout. */
    public data class Failed(public val reason: String) : LayerOutcome

    /** The stage could not be attempted at all. */
    public data class Skipped(public val reason: String) : LayerOutcome

    /** True when the stage was attempted and did not succeed. */
    public val isFailure: Boolean
        get() = this is Timeout || this is Failed

    /** True when the stage produced a duration worth aggregating. */
    public val isMeasured: Boolean
        get() = this is Ok

    /** Report text for this outcome, or null when there is nothing to add. */
    public val detail: String?
        get() = when (this) {
            Ok -> null
            is Timeout -> "exceeded $budgetMillis ms"
            is Failed -> reason
            is Skipped -> reason
        }
}

/**
 * One stage of one probe set.
 *
 * @property durationMillis how long the stage took, 0 when it was skipped.
 * @property note a stage-specific observation that is not an error, such as the resolver used.
 */
public data class LayerSample(
    public val layer: Layer,
    public val outcome: LayerOutcome,
    public val durationMillis: Long,
    public val note: String? = null,
) {
    init {
        require(durationMillis >= 0L) { "duration cannot be negative: $durationMillis" }
    }
}

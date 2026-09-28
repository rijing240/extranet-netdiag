package dev.extranet.netdiag.measure

/** A probe set in which every stage succeeded, with the given per-stage durations. */
fun okSamples(
    dnsMillis: Long = 5L,
    tcpMillis: Long = 10L,
    tlsMillis: Long = 20L,
    ttfbMillis: Long = 30L,
): List<LayerSample> = listOf(
    LayerSample(Layer.DNS, LayerOutcome.Ok, dnsMillis),
    LayerSample(Layer.TCP, LayerOutcome.Ok, tcpMillis),
    LayerSample(Layer.TLS, LayerOutcome.Ok, tlsMillis),
    LayerSample(Layer.TTFB, LayerOutcome.Ok, ttfbMillis),
)

/** A probe set whose [layer] failed, with every later stage skipped as unattempted. */
fun failedAt(layer: Layer, reason: String = "boom"): List<LayerSample> =
    Layer.ORDER.map { stage ->
        when {
            stage.ordinal < layer.ordinal -> LayerSample(stage, LayerOutcome.Ok, 1L)
            stage == layer -> LayerSample(stage, LayerOutcome.Failed(reason), 1L)
            else -> LayerSample(stage, LayerOutcome.Skipped("not attempted"), 0L)
        }
    }

/**
 * A probe source that answers from a script.
 *
 * This is the seam that makes the engine testable: no sockets, no device, no timing flakiness.
 */
class FakeProbeSetSource(
    private val samplesFor: (Int, ProbeTarget) -> List<LayerSample> = { _, _ -> okSamples() },
) : ProbeSetSource {

    /** Every set this source was asked for, as sequence to target. */
    val probed: MutableList<Pair<Int, ProbeTarget>> = mutableListOf()

    /** The resolver each set was given, in order. */
    val resolvers: MutableList<ResolverAddress?> = mutableListOf()

    override fun probe(
        sequence: Int,
        target: ProbeTarget,
        resolver: ResolverAddress?,
        budget: ProbeBudget,
    ): ProbeSet {
        probed += sequence to target
        resolvers += resolver
        return ProbeSet(
            sequence = sequence,
            target = target,
            startedAtEpochMillis = 1_000L + sequence,
            samples = samplesFor(sequence, target),
        )
    }
}

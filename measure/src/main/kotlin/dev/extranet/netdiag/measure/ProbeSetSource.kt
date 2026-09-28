package dev.extranet.netdiag.measure

/**
 * The half of the engine that touches the network.
 *
 * One method, on purpose. Everything the engine adds on top - how many sets to run, in what
 * order, when to stop, how to aggregate and what to conclude - is testable against a fake
 * implementation of this interface, with no sockets and no device. The only part that cannot be
 * tested that way is the socket work itself, which is exactly the part a device run is for.
 */
public interface ProbeSetSource {

    /**
     * Runs one pass through the waterfall against [target].
     *
     * Implementations must not throw: a stage that fails becomes a [LayerOutcome] on the set, so
     * that one broken set cannot abort a hundred-set run.
     *
     * @param resolver the resolver to query, or null when the environment supplied none, in
     *   which case the DNS stage is reported skipped and the rest of the set is not attempted.
     */
    public fun probe(
        sequence: Int,
        target: ProbeTarget,
        resolver: ResolverAddress?,
        budget: ProbeBudget,
    ): ProbeSet
}

package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget

/**
 * Runs B1's probe sets and turns them into a verdict.
 *
 * The sequence is: ask the platform what it knows, then run the sets until they are done or the
 * wall clock cap stops us, then aggregate. The platform question comes first because its answer
 * is context for reading the waterfall - a resolver that the OS reports as unreachable, or a
 * validated network that our own sets cannot use, changes what the numbers mean. The OS wait is
 * deliberately not charged against the wall clock cap: the cap governs the network work.
 *
 * Both clocks are injectable. Durations come from a monotonic source so that a wall clock
 * adjustment mid-run cannot produce a negative latency or a fake percentile.
 */
public class ProbeEngine(
    private val setSource: ProbeSetSource,
    private val osDiagnosticsSource: OsDiagnosticsSource? = null,
    private val budget: ProbeBudget = ProbeBudget(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /**
     * Runs [sets] probe sets over [targets], round robin.
     *
     * @param sets how many sets to attempt; the shortfall is visible in the report rather than
     *   silently top-up'd, so a truncated run cannot masquerade as a complete one.
     * @param collectOsDiagnostics false to skip the platform's report entirely, which is what a
     *   host-side run does because there is no platform to ask.
     */
    public fun run(
        targets: List<ProbeTarget> = ProbeTarget.DEFAULT,
        sets: Int = MeasurementBudget.SETS_PER_RUN,
        collectOsDiagnostics: Boolean = true,
    ): ProbeRun {
        require(targets.isNotEmpty()) { "a run needs at least one target" }
        require(sets > 0) { "a run needs at least one set, asked for $sets" }

        val startedAt = clock()
        val notes = mutableListOf<String>()

        val resolver = osDiagnosticsSource?.resolver()
        val osDiagnostics =
            if (collectOsDiagnostics) osDiagnosticsSource?.collect(MeasurementBudget.OS_DIAGNOSTICS_WAIT_MILLIS)
            else null

        val unavailableReason = when {
            !collectOsDiagnostics -> "platform diagnostics not requested for this run"
            osDiagnosticsSource == null -> "no platform diagnostics source is wired up"
            osDiagnostics == null ->
                "the platform delivered no connectivity report within " +
                    "${MeasurementBudget.OS_DIAGNOSTICS_WAIT_MILLIS} ms"
            else -> null
        }

        if (resolver == null) {
            notes += "no resolver was offered by the environment: every DNS stage is reported skipped"
        }
        notes += "raw coordinates and cell identities are never included in this report"

        val runStartMark = monotonicMillis()
        val collected = mutableListOf<ProbeSet>()
        var truncated = false

        for (index in 0 until sets) {
            if (monotonicMillis() - runStartMark > budget.wallClockCapMillis) {
                // Report the shortfall instead of hiding it: a run that hit the cap has not
                // measured what it set out to measure, and the verdict below says so.
                truncated = true
                break
            }
            collected += setSource.probe(index, targets[index % targets.size], resolver, budget)
        }

        val finishedAt = clock()
        if (truncated) {
            notes += "stopped after ${collected.size} of $sets sets: wall clock cap " +
                "${budget.wallClockCapMillis} ms reached"
        }

        return ProbeRun(
            startedAtEpochMillis = startedAt,
            finishedAtEpochMillis = finishedAt,
            setsRequested = sets,
            sets = collected,
            truncated = truncated,
            resolver = resolver,
            osDiagnostics = osDiagnostics,
            osDiagnosticsUnavailableReason = unavailableReason,
            notes = notes,
        )
    }
}

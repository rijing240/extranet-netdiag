package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.CapabilityFinding
import dev.extranet.netdiag.core.report.CapabilityReport
import dev.extranet.netdiag.core.report.SupportStatus

/**
 * Walks the probe catalog and produces a capability report.
 *
 * ## Classification order
 * Checks run cheapest-and-most-definitive first, so a finding is never reported as
 * "unavailable" when the real answer is "this device does not have the API":
 *
 * 1. `BELOW_API_LEVEL` - the device is older than the member, so the call is not even legal.
 * 2. `PERMISSION_DENIED` - the call would be rejected, so asking would produce noise.
 * 3. `FEATURE_ABSENT` - the hardware is not present.
 * 4. `NOT_PROBED` - the spec is asynchronous and the caller supplied no live-session result.
 * 5. whatever the platform returned.
 *
 * Only after all four guards does the runner touch the platform, which is why a report from a
 * modern handset with no permissions granted is still informative rather than a wall of
 * exceptions.
 *
 * @param source the platform seam.
 * @param clock injectable time source so report timestamps are deterministic in tests.
 */
public class CapabilityProbeRunner(
    private val source: PlatformReportSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Runs [catalog] and returns a report.
     *
     * @param resolvedAsync outcomes for [ProbeKind.ASYNC] specs that a live session managed to
     *   observe. Anything absent from this map is reported as `NOT_PROBED`.
     */
    public fun run(
        catalog: List<ProbeSpec> = ProbeCatalog.ALL,
        resolvedAsync: Map<String, ProbeOutcome> = emptyMap(),
        notes: List<String> = emptyList(),
    ): CapabilityReport {
        val findings = catalog.map { spec ->
            val report = classify(spec, resolvedAsync[spec.id])
            CapabilityFinding(
                id = spec.id,
                api = spec.api,
                system = spec.system,
                requiredApiLevel = spec.requiredApiLevel,
                status = report.status,
                observedValue = report.observed,
                // A real diagnostic explains this device; a catalog note explains the API. The
                // catalog note wins only over canned, content-free platform text, so an
                // UNAVAILABLE finding arrives carrying the reason it is unavailable.
                detail = report.detail ?: spec.note ?: report.fallbackDetail,
            )
        }
        return CapabilityReport(
            device = source.device,
            generatedAtEpochMillis = clock(),
            findings = findings,
            notes = notes,
        )
    }

    private data class Classification(
        val status: SupportStatus,
        val observed: String? = null,
        /** A genuine diagnostic, such as an exception reason or a named missing permission. */
        val detail: String? = null,
        /** Canned text, kept only when the catalog has nothing of its own to say. */
        val fallbackDetail: String? = null,
    )

    private fun classify(spec: ProbeSpec, asyncOutcome: ProbeOutcome?): Classification {
        if (source.device.sdkInt < spec.requiredApiLevel) {
            return Classification(
                status = SupportStatus.BELOW_API_LEVEL,
                detail = "device API ${source.device.sdkInt} < required ${spec.requiredApiLevel}",
            )
        }

        val permission = spec.requiredPermission
        if (permission != null && !source.isPermissionGranted(permission)) {
            return Classification(
                status = SupportStatus.PERMISSION_DENIED,
                detail = "$permission not granted",
            )
        }

        val feature = spec.requiredFeature
        if (feature != null && !source.hasFeature(feature)) {
            return Classification(
                status = SupportStatus.FEATURE_ABSENT,
                detail = "$feature not reported by PackageManager",
            )
        }

        return when (spec.kind) {
            ProbeKind.ASYNC -> if (asyncOutcome == null) {
                Classification(
                    status = SupportStatus.NOT_PROBED,
                    detail = "requires a live session; not observed in this run",
                )
            } else {
                fromOutcome(asyncOutcome)
            }

            // Reaching here means the permission guard above already passed.
            ProbeKind.PERMISSION -> Classification(status = SupportStatus.SUPPORTED, observed = "granted")

            // Likewise: the feature guard above already rejected the absent case.
            ProbeKind.FEATURE -> Classification(status = SupportStatus.SUPPORTED, observed = "present")

            ProbeKind.SYNC -> fromOutcome(source.query(spec.id))
        }
    }

    private fun fromOutcome(outcome: ProbeOutcome): Classification = when (outcome) {
        is ProbeOutcome.Value -> Classification(
            status = SupportStatus.SUPPORTED,
            observed = outcome.rendered,
        )

        ProbeOutcome.Unavailable -> Classification(
            status = SupportStatus.UNAVAILABLE,
            fallbackDetail = "platform returned UNAVAILABLE",
        )

        is ProbeOutcome.Failed -> Classification(
            status = SupportStatus.THROWS,
            detail = outcome.reason,
        )

        ProbeOutcome.Denied -> Classification(
            status = SupportStatus.PERMISSION_DENIED,
            fallbackDetail = "platform refused for permission reasons",
        )

        ProbeOutcome.Absent -> Classification(
            status = SupportStatus.FEATURE_ABSENT,
            fallbackDetail = "platform reported the feature as absent",
        )
    }
}

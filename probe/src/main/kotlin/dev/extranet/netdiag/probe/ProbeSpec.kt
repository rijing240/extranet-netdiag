package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.SystemId

/**
 * How a platform API has to be interrogated.
 *
 * The distinction drives the runner: everything except [ASYNC] can be answered from a
 * synchronous call, whereas [ASYNC] results only exist during a live session and are reported
 * as `NOT_PROBED` until a caller supplies them.
 */
public enum class ProbeKind {
    /** Observable from a single synchronous call. */
    SYNC,

    /** Needs a callback registration and a live session before it produces anything. */
    ASYNC,

    /** A runtime permission grant, answered without calling the platform at all. */
    PERMISSION,

    /** A hardware feature flag from `PackageManager.hasSystemFeature`. */
    FEATURE,
}

/**
 * One thing the project needs to know about the handset.
 *
 * @property id stable identifier; the report and the S7 registry both key on this.
 * @property api the platform member that would be used, or a description for kind-only specs.
 * @property system which of the seven systems consumes it.
 * @property kind how it is interrogated.
 * @property requiredApiLevel API level at which the member first exists.
 * @property requiredPermission runtime permission needed before the call is legal, if any.
 * @property requiredFeature `PackageManager` feature that must be present, if any.
 * @property note why this matters to the project, for the registry UI and review.
 */
public data class ProbeSpec(
    public val id: String,
    public val api: String,
    public val system: SystemId,
    public val kind: ProbeKind,
    public val requiredApiLevel: Int = 1,
    public val requiredPermission: String? = null,
    public val requiredFeature: String? = null,
    public val note: String? = null,
)

/** What the platform said when asked. */
public sealed interface ProbeOutcome {

    /** A usable value. [rendered] is a short human-readable representation for the report. */
    public data class Value(public val rendered: String) : ProbeOutcome

    /** The API was callable but returned the platform UNAVAILABLE sentinel or null. */
    public data object Unavailable : ProbeOutcome

    /** The call threw. */
    public data class Failed(public val reason: String) : ProbeOutcome

    /** The platform refused for permission reasons. */
    public data object Denied : ProbeOutcome

    /** The hardware or feature is not present. */
    public data object Absent : ProbeOutcome
}

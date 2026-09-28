package dev.extranet.netdiag.probe

import dev.extranet.netdiag.core.report.DeviceDescriptor

/**
 * The seam between the probe engine and the platform.
 *
 * Keeping this narrow is what makes B0's deliverable testable without a handset: the runner,
 * the classification rules and the report serialization are all pure JVM logic, and the
 * Android module contributes only an implementation of these four members. It also means the
 * engine can be exercised in a JVM unit test against a scripted device, which is how the
 * classification table is pinned.
 */
public interface PlatformReportSource {

    /** Identity of the device the report is about. */
    public val device: DeviceDescriptor

    /** True when the runtime permission is currently granted. */
    public fun isPermissionGranted(permission: String): Boolean

    /** True when `PackageManager.hasSystemFeature` reports the feature. */
    public fun hasFeature(feature: String): Boolean

    /**
     * Interrogates the platform for [specId].
     *
     * Implementations must not throw: anything that goes wrong inside the platform call has to
     * be translated into [ProbeOutcome.Failed] so one bad API cannot abort the whole report.
     * An unrecognised [specId] should return [ProbeOutcome.Unavailable] rather than throwing,
     * which keeps the catalog and the implementations free to evolve independently.
     */
    public fun query(specId: String): ProbeOutcome
}

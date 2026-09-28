package dev.extranet.netdiag.core.report

/**
 * The seven systems of the blueprint plus the three cross-cutting concerns.
 *
 * Every finding and every later feature is tagged with one of these so the capability report
 * can be read as a per-system support matrix rather than a flat list.
 */
public enum class SystemId(
    public val label: String,
) {
    /** S1 - Sensor Core: radio timeline, GNSS, context, sessions. */
    SENSOR_CORE("S1 Sensor Core"),

    /** S2 - Measurement Engine: layer probes, bufferbloat, capability detection. */
    MEASUREMENT_ENGINE("S2 Measurement Engine"),

    /** S3 - Inference: features, labelling, training, on-device scoring. */
    INFERENCE("S3 Inference"),

    /** S4 - Decision SDK: NetworkConfidence and the offline tripwire. */
    DECISION_SDK("S4 Decision SDK"),

    /** S5 - Collective: ingest, Capacity Atlas, baselines. */
    COLLECTIVE("S5 Collective"),

    /** S6 - Presentation: waterfall, forecast, relative index. */
    PRESENTATION("S6 Presentation"),

    /** S7 - Capability Registry: per-model API availability and calibration. */
    CAPABILITY_REGISTRY("S7 Capability Registry"),

    /** X1 - Privacy and Consent. */
    PRIVACY("X1 Privacy/Consent"),

    /** X2 - Budget Guard: sample-rate caps, duty cycling, thermal governor. */
    BUDGET_GUARD("X2 Budget Guard"),

    /** X3 - Test Harness: golden data, maths tests, device matrix. */
    TEST_HARNESS("X3 Test Harness"),
}

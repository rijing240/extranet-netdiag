package dev.extranet.netdiag.core.report

import dev.extranet.netdiag.core.json.Json

/**
 * Identity of the handset a report was captured on.
 *
 * SoC manufacturer and model are captured because modem behaviour tracks the chipset far more
 * than the brand. The blueprint's whole hardware-fragmentation risk is really a
 * Qualcomm-versus-Exynos-versus-MediaTek risk, so the registry keys on these fields where the
 * platform exposes them (API 31+).
 */
public data class DeviceDescriptor(
    public val manufacturer: String,
    public val model: String,
    public val device: String,
    public val sdkInt: Int,
    public val releaseVersion: String,
    public val socManufacturer: String? = null,
    public val socModel: String? = null,
) {
    /** Stable key for grouping reports by hardware family. */
    public fun hardwareFamily(): String {
        val soc = socManufacturer ?: manufacturer
        return "$soc:$device"
    }

    /** Renders this descriptor as a JSON object at the given indentation depth. */
    public fun toJson(indent: Int = 0): String = Json.objectOf(
        listOf(
            "manufacturer" to Json.quote(manufacturer),
            "model" to Json.quote(model),
            "device" to Json.quote(device),
            "sdkInt" to Json.number(sdkInt),
            "releaseVersion" to Json.quote(releaseVersion),
            "socManufacturer" to Json.nullableString(socManufacturer),
            "socModel" to Json.nullableString(socModel),
            "hardwareFamily" to Json.quote(hardwareFamily()),
        ),
        indent,
    )
}

package dev.extranet.netdiag.core.report

import dev.extranet.netdiag.core.json.Json

/**
 * The result of asking the platform for one thing.
 *
 * @property id stable probe identifier, unique within a report.
 * @property api the fully qualified platform member that was interrogated.
 * @property system which of the seven systems depends on it.
 * @property requiredApiLevel the API level at which the member first exists.
 * @property status what actually happened.
 * @property observedValue a short rendering of what the device returned, when it returned
 *   anything. Kept as a string because findings span integers, doubles, enums and lists.
 * @property detail exception class and message, or an explanatory note.
 */
public data class CapabilityFinding(
    public val id: String,
    public val api: String,
    public val system: SystemId,
    public val requiredApiLevel: Int,
    public val status: SupportStatus,
    public val observedValue: String? = null,
    public val detail: String? = null,
) {
    /** Renders this finding as a JSON object at the given indentation depth. */
    public fun toJson(indent: Int = 0): String = Json.objectOf(
        listOf(
            "id" to Json.quote(id),
            "api" to Json.quote(api),
            "system" to Json.quote(system.name),
            "requiredApiLevel" to Json.number(requiredApiLevel),
            "status" to Json.quote(status.name),
            "observedValue" to Json.nullableString(observedValue),
            "detail" to Json.nullableString(detail),
        ),
        indent,
    )
}

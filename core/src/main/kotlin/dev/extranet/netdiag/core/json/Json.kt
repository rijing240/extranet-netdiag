package dev.extranet.netdiag.core.json

/**
 * A deliberately tiny JSON emitter.
 *
 * The report has a fixed, flat shape and is written by exactly one producer, so a full
 * serialization framework would add a compiler plugin and a dependency to the pure-JVM kernel
 * for no benefit. What matters is that the output is valid JSON and stable across runs, which
 * is what the unit tests pin.
 *
 * Values are passed as pre-rendered JSON strings. Nested objects and arrays must be rendered
 * one indentation level deeper than the container they are placed into.
 */
public object Json {

    /** Indentation unit. Two spaces keeps diffs of generated reports readable. */
    public const val INDENT: String = "  "

    private const val HEX: String = "0123456789abcdef"

    /** Escapes a string for inclusion inside JSON double quotes. */
    public fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u")
                    val code = ch.code
                    sb.append(HEX[(code shr 12) and 0x0F])
                    sb.append(HEX[(code shr 8) and 0x0F])
                    sb.append(HEX[(code shr 4) and 0x0F])
                    sb.append(HEX[code and 0x0F])
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /** Renders a string as a quoted JSON string. */
    public fun quote(value: String): String = "\"" + escape(value) + "\""

    /** Renders a string, or the JSON literal `null` when absent. */
    public fun nullableString(value: String?): String = if (value == null) "null" else quote(value)

    /** Renders an integer. */
    public fun number(value: Int): String = value.toString()

    /** Renders a long. */
    public fun number(value: Long): String = value.toString()

    /**
     * Renders a double. NaN and the infinities have no JSON representation, so they are
     * emitted as `null` rather than producing a document that no parser will accept. Integral
     * values are rendered without a trailing `.0` so ids and counts stay readable.
     */
    public fun number(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "null"
        value == 0.0 -> "0"
        value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1.0e15 -> value.toLong().toString()
        else -> value.toString()
    }

    /** Renders a boolean. */
    public fun bool(value: Boolean): String = if (value) "true" else "false"

    /**
     * Renders an object from pre-rendered `name to value` pairs.
     *
     * @param indent depth of this object within its parent.
     */
    public fun objectOf(entries: List<Pair<String, String>>, indent: Int = 0): String {
        if (entries.isEmpty()) return "{}"
        val pad = INDENT.repeat(indent)
        val childPad = INDENT.repeat(indent + 1)
        return entries.joinToString(
            separator = ",\n$childPad",
            prefix = "{\n$childPad",
            postfix = "\n$pad}",
        ) { (name, value) -> quote(name) + ": " + value }
    }

    /**
     * Renders an array from pre-rendered element strings, which must already be rendered at
     * `indent + 1`.
     */
    public fun arrayOf(items: List<String>, indent: Int = 0): String {
        if (items.isEmpty()) return "[]"
        val pad = INDENT.repeat(indent)
        val childPad = INDENT.repeat(indent + 1)
        return items.joinToString(
            separator = ",\n$childPad",
            prefix = "[\n$childPad",
            postfix = "\n$pad]",
        )
    }

    /** Renders an array of already-quoted strings on a single line. */
    public fun stringArray(values: List<String>): String =
        values.joinToString(separator = ", ", prefix = "[", postfix = "]") { quote(it) }
}

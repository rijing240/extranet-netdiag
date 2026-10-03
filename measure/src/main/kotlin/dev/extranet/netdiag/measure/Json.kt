package dev.extranet.netdiag.measure

/**
 * The smallest JSON reader this app can get away with, written here rather than added as a
 * dependency because of what it is for.
 *
 * The update check reads GitHub's release list, and the one thing in that document that is not
 * under this project's control is the release notes: they are written by hand, they contain
 * quotation marks, backslashes, newlines and the occasional emoji, and a reader that guesses at
 * string end boundaries will chop them in half and hand back nonsense. So the strings are read
 * properly - every escape, including `\uXXXX` - and the whole document has to parse or nothing
 * does. There is no partial answer: a release list that cannot be read is reported as "could not
 * check", never as "no updates".
 *
 * It is also the reason this lives in the pure module. Parsing is logic, and logic here is
 * tested without a phone; the network call that feeds it is thin enough to test with a real
 * socket on the loopback interface.
 *
 * What it deliberately is not: a general-purpose library. It reads a document into a small tree
 * and offers four accessors. It has a nesting limit so a hostile or broken response cannot
 * exhaust the stack, and every failure is a null rather than an exception, because the app has
 * to survive whatever a server sends it.
 */
public sealed interface Json {

    /** `{ ... }`, keys in the order they appeared. */
    public data class Obj(public val fields: Map<String, Json>) : Json

    /** `[ ... ]`. */
    public data class Arr(public val items: List<Json>) : Json

    /** A string, with its escapes already resolved. */
    public data class Text(public val value: String) : Json

    /** A number. Everything arriving from JSON is a double; whole ones are checked by the caller. */
    public data class Num(public val value: Double) : Json

    /** `true` or `false`. */
    public data class Flag(public val value: Boolean) : Json

    /** `null`, which is a value and not the same as a missing key. */
    public data object Null : Json
}

/** The value of [name] on an object, or null when this is not an object or has no such key. */
public fun Json?.field(name: String): Json? = (this as? Json.Obj)?.fields?.get(name)

/** The text of [name], or null when it is missing, null, or not a string. */
public fun Json?.text(name: String): String? = (this.field(name) as? Json.Text)?.value

/** The number of [name], or null when it is missing, null, or not a number. */
public fun Json?.number(name: String): Double? = (this.field(name) as? Json.Num)?.value

/**
 * The boolean of [name], or null when it is missing or not a boolean.
 *
 * Null and false are kept apart on purpose: a release with no `prerelease` key at all is not the
 * same as one that says it is a test build, and the screen says which it is.
 */
public fun Json?.flag(name: String): Boolean? = (this.field(name) as? Json.Flag)?.value

/** The elements of the array at [name], or an empty list when there is none. */
public fun Json?.items(name: String): List<Json> = (this.field(name) as? Json.Arr)?.items.orEmpty()

/** Reads a whole JSON document, or null when the text is not one. */
public object JsonReader {

    /** How deep a document may nest before it is refused. */
    private const val MAX_DEPTH: Int = 32

    public fun read(text: String): Json? {
        val scanner = Scanner(text)
        val value = scanner.value(0) ?: return null
        scanner.skipSpace()
        // Trailing rubbish means this is not the document it claims to be. Accepting the prefix
        // would let a truncated or doubled response be read as a good one.
        return if (scanner.atEnd()) value else null
    }

    private class Scanner(private val text: String) {
        private var at = 0

        fun atEnd(): Boolean = at >= text.length

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(depth: Int): Json? {
            if (depth > MAX_DEPTH) return null
            skipSpace()
            if (atEnd()) return null
            return when (text[at]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()?.let { Json.Text(it) }
                't' -> literal("true", Json.Flag(true))
                'f' -> literal("false", Json.Flag(false))
                'n' -> literal("null", Json.Null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Json? {
            at++ // '{'
            val fields = LinkedHashMap<String, Json>()
            skipSpace()
            if (!atEnd() && text[at] == '}') {
                at++
                return Json.Obj(fields)
            }
            while (true) {
                skipSpace()
                val key = string() ?: return null
                skipSpace()
                if (atEnd() || text[at] != ':') return null
                at++
                val element = value(depth + 1) ?: return null
                fields[key] = element
                skipSpace()
                if (atEnd()) return null
                when (text[at]) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return Json.Obj(fields)
                    }
                    else -> return null
                }
            }
        }

        private fun arr(depth: Int): Json? {
            at++ // '['
            val items = ArrayList<Json>()
            skipSpace()
            if (!atEnd() && text[at] == ']') {
                at++
                return Json.Arr(items)
            }
            while (true) {
                val element = value(depth + 1) ?: return null
                items.add(element)
                skipSpace()
                if (atEnd()) return null
                when (text[at]) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return Json.Arr(items)
                    }
                    else -> return null
                }
            }
        }

        /** A quoted string, escapes resolved; null when it is unterminated or malformed. */
        private fun string(): String? {
            if (atEnd() || text[at] != '"') return null
            at++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) return null
                when (val c = text[at]) {
                    '"' -> {
                        at++
                        return out.toString()
                    }
                    // A raw control character inside a string is not legal JSON, and letting it
                    // through would put stray bytes into something the screen will draw.
                    in '\u0000'..'\u001F' -> return null
                    '\\' -> {
                        at++
                        if (atEnd()) return null
                        when (val escape = text[at]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val code = unicodeEscape() ?: return null
                                out.append(code)
                            }
                            else -> return null
                        }
                        at++
                    }
                    else -> {
                        out.append(c)
                        at++
                    }
                }
            }
        }

        /**
         * The four hex digits of a `\uXXXX` escape, as the character they stand for.
         *
         * A surrogate pair arrives as two escapes and is appended as two halves, which Kotlin's
         * strings carry natively - the pair only has to stay together, and it does.
         */
        private fun unicodeEscape(): Char? {
            if (at + 4 >= text.length) return null
            val digits = text.substring(at + 1, at + 5)
            val value = digits.toIntOrNull(16) ?: return null
            at += 4
            return value.toChar()
        }

        private fun literal(word: String, value: Json): Json? {
            if (!text.startsWith(word, at)) return null
            at += word.length
            return value
        }

        private fun number(): Json? {
            val start = at
            if (!atEnd() && text[at] == '-') at++
            val digitsStart = at
            while (!atEnd() && text[at].isDigit()) at++
            if (at == digitsStart) return null
            if (!atEnd() && text[at] == '.') {
                at++
                val fractionStart = at
                while (!atEnd() && text[at].isDigit()) at++
                if (at == fractionStart) return null
            }
            if (!atEnd() && (text[at] == 'e' || text[at] == 'E')) {
                at++
                if (!atEnd() && (text[at] == '+' || text[at] == '-')) at++
                val exponentStart = at
                while (!atEnd() && text[at].isDigit()) at++
                if (at == exponentStart) return null
            }
            return text.substring(start, at).toDoubleOrNull()?.let { Json.Num(it) }
        }
    }
}

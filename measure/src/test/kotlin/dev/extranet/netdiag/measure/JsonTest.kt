package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the JSON reader, with the cases that actually break naive ones.
 *
 * The reader exists because release notes are free text: they carry quotation marks, backslashes,
 * newlines and emoji, and the whole point of writing a reader instead of splitting on commas is
 * that those cases have to work. So they are tested, along with the failures - a truncated or
 * doubled document must be refused rather than half-read, because "no update" and "could not
 * read the answer" are different things to say to a person.
 */
class JsonTest {

    @Test
    fun readsObjectsArraysAndScalars() {
        val document = JsonReader.read(
            """{"a":1,"b":"two","c":true,"d":false,"e":null,"f":[1,2,3],"g":{"h":"deep"}}""",
        )
        assertEquals(1.0, document.number("a"))
        assertEquals("two", document.text("b"))
        assertEquals(true, document.flag("c"))
        assertEquals(false, document.flag("d"))
        assertEquals(Json.Null, document.field("e"))
        assertEquals(3, document.items("f").size)
        assertEquals("deep", document.field("g").text("h"))
    }

    @Test
    fun resolvesEscapesIncludingQuotesAndNewlines() {
        val document = JsonReader.read(
            """{"body":"He said \"upgrade\" and\\or else.\nSecond line.\tTabbed.\\u0041"}""",
        )
        // The JSON text says: a quoted word, a real backslash, a newline, a tab, and an escaped
        // backslash before "u0041" that must stay literal text rather than becoming an "A".
        assertEquals("He said \"upgrade\" and\\or else.\nSecond line.\tTabbed.\\u0041", document.text("body"))
    }

    @Test
    fun resolvesUnicodeEscapesAndLeavesRealCharactersAlone() {
        val document = JsonReader.read("""{"note":"caf\u00e9 \u2603 ok","emoji":"done"}""")
        assertEquals("caf\u00e9 \u2603 ok", document.text("note"))
        assertEquals("done", document.text("emoji"))
    }

    @Test
    fun readsNumbersWithSignsFractionsAndExponents() {
        val document = JsonReader.read("""{"big":1234567890,"small":-0.5,"tiny":1e-3,"up":2.5E+2}""")
        assertEquals(1234567890.0, document.number("big"))
        assertEquals(-0.5, document.number("small"))
        assertEquals(0.001, document.number("tiny"))
        assertEquals(250.0, document.number("up"))
    }

    @Test
    fun aKeyWithNoValueOrATrailingCommaIsRefused() {
        assertNull(JsonReader.read("""{"a":}"""))
        assertNull(JsonReader.read("""{"a":1,}"""))
        assertNull(JsonReader.read("""[1,2,]"""))
    }

    @Test
    fun unfinishedDocumentsAreRefusedRatherThanHalfRead() {
        assertNull(JsonReader.read("""{"a":"unterminated"""))
        assertNull(JsonReader.read("""{"a":1"""))
        assertNull(JsonReader.read("""{"a":1} {"b":2}"""))
        assertNull(JsonReader.read("""{"a":1} nonsense"""))
        assertNull(JsonReader.read(""))
    }

    @Test
    fun punctuationThatIsNotJsonIsRefused() {
        assertNull(JsonReader.read("not json at all"))
        assertNull(JsonReader.read("""{'a':1}"""))
        assertNull(JsonReader.read("""{"a" 1}"""))
        assertNull(JsonReader.read("""{"a":"\q"}"""))
    }

    @Test
    fun nestingIsBoundedSoAHostileDocumentCannotExhaustTheStack() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertNull(JsonReader.read(deep))

        // Deep-but-legal still reads, so the limit is a guard and not a wall.
        val reasonable = "[".repeat(8) + "1" + "]".repeat(8)
        assertTrue(JsonReader.read(reasonable) is Json.Arr)
    }

    @Test
    fun accessorsAnswerForMissingAndWrongTypesInsteadOfThrowing() {
        val document = JsonReader.read("""{"text":"value","number":3,"flag":true}""")
        assertNull(document.field("absent"))
        assertNull(document.text("number"))
        assertNull(document.number("text"))
        assertNull(document.flag("text"))
        assertTrue(document.items("text").isEmpty())
        assertNull(JsonReader.read("[]").text("anything"))
    }

    @Test
    fun emptyContainersRead() {
        val document = JsonReader.read("""{"objects":{},"arrays":[],"blank":""}""")
        assertEquals(0, document.items("objects").size)
        assertEquals(0, document.items("arrays").size)
        assertEquals("", document.text("blank"))
    }
}

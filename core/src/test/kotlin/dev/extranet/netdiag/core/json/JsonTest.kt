package dev.extranet.netdiag.core.json

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The report is consumed by the S7 registry and by integration tests, so the emitter's exact
 * output shape is pinned here rather than left to chance.
 */
class JsonTest {

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"))
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"))
    }

    @Test
    fun `control characters are escaped`() {
        assertEquals("\"a\\nb\"", Json.quote("a\nb"))
        assertEquals("\"a\\tb\"", Json.quote("a\tb"))
        assertEquals("\"a\\rb\"", Json.quote("a\rb"))
        assertEquals("\"a\\u0000b\"", Json.quote("a\u0000b"))
        assertEquals("\"a\\u001fb\"", Json.quote("a\u001fb"))
    }

    @Test
    fun `non ascii characters pass through unescaped`() {
        // UTF-8 output is expected; escaping them would bloat the report for no gain.
        assertEquals("\"\u00e9\u4e2d\"", Json.quote("\u00e9\u4e2d"))
    }

    @Test
    fun `nullable strings render as json null`() {
        assertEquals("null", Json.nullableString(null))
        assertEquals("\"x\"", Json.nullableString("x"))
    }

    @Test
    fun `integral doubles lose the decimal point`() {
        assertEquals("1", Json.number(1.0))
        assertEquals("0", Json.number(0.0))
        assertEquals("-12", Json.number(-12.0))
    }

    @Test
    fun `fractional doubles keep their precision`() {
        assertEquals("2.5", Json.number(2.5))
        assertEquals("78.0709526", Json.number(78.0709526))
    }

    @Test
    fun `non finite doubles render as null because json has no nan`() {
        assertEquals("null", Json.number(Double.NaN))
        assertEquals("null", Json.number(Double.POSITIVE_INFINITY))
        assertEquals("null", Json.number(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `empty containers are compact`() {
        assertEquals("{}", Json.objectOf(emptyList()))
        assertEquals("[]", Json.arrayOf(emptyList()))
    }

    @Test
    fun `object layout is exactly as pinned`() {
        val rendered = Json.objectOf(listOf("a" to Json.number(1), "b" to Json.number(2)))
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}", rendered)
    }

    @Test
    fun `nested objects indent relative to their depth`() {
        val inner = Json.objectOf(listOf("x" to Json.number(1)), indent = 1)
        val outer = Json.objectOf(listOf("inner" to inner), indent = 0)
        assertEquals("{\n  \"inner\": {\n    \"x\": 1\n  }\n}", outer)
    }

    @Test
    fun `array elements are indented one level deeper than the brackets`() {
        val rendered = Json.arrayOf(listOf(Json.number(1), Json.number(2)), indent = 0)
        assertEquals("[\n  1,\n  2\n]", rendered)
    }

    @Test
    fun `string array renders on one line`() {
        assertEquals("[\"a\", \"b\"]", Json.stringArray(listOf("a", "b")))
        assertEquals("[]", Json.stringArray(emptyList()))
    }

    @Test
    fun `bools render as literals`() {
        assertEquals("true", Json.bool(true))
        assertEquals("false", Json.bool(false))
    }
}

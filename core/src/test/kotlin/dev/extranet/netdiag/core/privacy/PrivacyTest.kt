package dev.extranet.netdiag.core.privacy

import dev.extranet.netdiag.core.ledger.Atlas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Privacy primitives are the one place where a silent change is a compliance incident rather
 * than a bug, so the vectors here are published test vectors rather than values captured from
 * this implementation. They were cross-checked independently in `tools/verify_ledger.py`.
 */
class PrivacyTest {

    // --- Geohash ---------------------------------------------------------------------------

    @Test
    fun `published test vector at precision 5`() {
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, 5))
    }

    @Test
    fun `published test vector at precision 11`() {
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
    }

    @Test
    fun `atlas precision reference value`() {
        val encoded = Geohash.encodeAtlasPrecision(42.6, -5.6)
        assertEquals("ezs42e4", encoded)
        assertEquals(Atlas.GEOHASH_PRECISION, encoded.length)
    }

    @Test
    fun `precision controls the length exactly`() {
        for (p in 1..Geohash.MAX_PRECISION) {
            assertEquals(p, Geohash.encode(51.5074, -0.1278, p).length, "precision $p")
        }
    }

    @Test
    fun `a shorter geohash is a prefix of a longer one`() {
        // This is the property the Atlas relies on to coarsen without re-encoding.
        val long = Geohash.encode(40.7128, -74.0060, 9)
        val short = Geohash.encode(40.7128, -74.0060, 5)
        assertTrue(long.startsWith(short), "$long should start with $short")
    }

    @Test
    fun `nearby points share a prefix and distant points do not`() {
        val a = Geohash.encode(51.5074, -0.1278, 7)
        val nearby = Geohash.encode(51.5075, -0.1279, 7)
        val faraway = Geohash.encode(-33.8688, 151.2093, 7)
        assertEquals(a.substring(0, 5), nearby.substring(0, 5))
        assertNotEquals(a.substring(0, 2), faraway.substring(0, 2))
    }

    @Test
    fun `geohash alphabet excludes ambiguous letters`() {
        for (ch in listOf('a', 'i', 'l', 'o')) {
            assertTrue(ch !in Geohash.ALPHABET, "alphabet must not contain '$ch'")
        }
        assertEquals(32, Geohash.ALPHABET.length)
    }

    @Test
    fun `out of range inputs are rejected rather than encoded`() {
        assertFailsWith<IllegalArgumentException> { Geohash.encode(90.1, 0.0, 7) }
        assertFailsWith<IllegalArgumentException> { Geohash.encode(0.0, 180.1, 7) }
        assertFailsWith<IllegalArgumentException> { Geohash.encode(0.0, 0.0, 0) }
        assertFailsWith<IllegalArgumentException> { Geohash.encode(0.0, 0.0, 13) }
    }

    @Test
    fun `atlas precision is seven characters`() {
        assertEquals(7, Atlas.GEOHASH_PRECISION)
    }

    // --- CellKeyHasher ---------------------------------------------------------------------

    @Test
    fun `sha256 matches the published digests`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CellKeyHasher.sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CellKeyHasher.sha256Hex("abc"),
        )
    }

    @Test
    fun `cell key matches the independently computed vector`() {
        // sha256("salt\u001F" + "ezs42e4\u001F6300\u001F123\u001F4567")[:16]
        assertEquals(
            "0e69fe3fdba91eea",
            CellKeyHasher.cellKey(salt = "salt", geohash = "ezs42e4", earfcn = 6300, pci = 123, tac = 4567),
        )
    }

    @Test
    fun `cell key is truncated to the atlas length`() {
        val key = CellKeyHasher.cellKey("s", "ezs42e4", 6300, 123, 4567)
        assertEquals(Atlas.CELL_KEY_HEX_LENGTH, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, "key must be lower-case hex: $key")
    }

    @Test
    fun `the same input always produces the same key`() {
        // Stability is required: the Atlas aggregates across sessions and devices.
        val a = CellKeyHasher.cellKey("s", "ezs42e4", 6300, 123, 4567)
        val b = CellKeyHasher.cellKey("s", "ezs42e4", 6300, 123, 4567)
        assertEquals(a, b)
    }

    @Test
    fun `changing the salt changes the key`() {
        // This is what makes the salt a revocation tool without re-collecting data.
        assertNotEquals(
            CellKeyHasher.cellKey("salt-a", "ezs42e4", 6300, 123, 4567),
            CellKeyHasher.cellKey("salt-b", "ezs42e4", 6300, 123, 4567),
        )
    }

    @Test
    fun `each fingerprint component affects the key`() {
        val base = CellKeyHasher.cellKey("s", "ezs42e4", 6300, 123, 4567)
        assertNotEquals(base, CellKeyHasher.cellKey("s", "ezs42e5", 6300, 123, 4567))
        assertNotEquals(base, CellKeyHasher.cellKey("s", "ezs42e4", 6301, 123, 4567))
        assertNotEquals(base, CellKeyHasher.cellKey("s", "ezs42e4", 6300, 124, 4567))
        assertNotEquals(base, CellKeyHasher.cellKey("s", "ezs42e4", 6300, 123, 4568))
    }

    @Test
    fun `separator prevents field-boundary collisions`() {
        // Concatenating without a separator would make ("a","bc") and ("ab","c") identical.
        assertNotEquals(
            CellKeyHasher.sha256Hex("ab" + CellKeyHasher.FIELD_SEPARATOR + "c"),
            CellKeyHasher.sha256Hex("a" + CellKeyHasher.FIELD_SEPARATOR + "bc"),
        )
    }

    @Test
    fun `truncate keeps short strings and trims long ones`() {
        assertEquals("abc", CellKeyHasher.truncate("abc", 8))
        assertEquals("abcd", CellKeyHasher.truncate("abcdefgh", 4))
        assertFailsWith<IllegalArgumentException> { CellKeyHasher.truncate("abc", 0) }
    }

    @Test
    fun `atlas bucket size is five observations`() {
        assertEquals(5, Atlas.MIN_BUCKET_SIZE)
        assertEquals(24, Atlas.HOUR_BUCKETS)
        assertEquals(16, Atlas.CELL_KEY_HEX_LENGTH)
    }
}

package dev.extranet.netdiag.core.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The retired-calculation record only has value if it stays complete and reviewable. These
 * tests fail if someone quietly deletes a rejection instead of arguing with it in review.
 */
class RetiredCalculationsTest {

    @Test
    fun `all three rejected calculations are recorded`() {
        assertEquals(3, RetiredCalculations.ALL.size)
        assertNotNull(RetiredCalculations.byId("rsrp-multilateration"))
        assertNotNull(RetiredCalculations.byId("path-loss-exponent"))
        assertNotNull(RetiredCalculations.byId("littles-law"))
    }

    @Test
    fun `ids are unique`() {
        val ids = RetiredCalculations.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate retired-calculation id: $ids")
    }

    @Test
    fun `unknown id returns null rather than throwing`() {
        assertNull(RetiredCalculations.byId("does-not-exist"))
    }

    @Test
    fun `rsrp multilateration points at fused location as the replacement`() {
        val entry = requireNotNull(RetiredCalculations.byId("rsrp-multilateration"))
        assertTrue(entry.reason.contains("serving cell only"))
        assertTrue(entry.replacement.contains("FusedLocationProvider"))
    }

    @Test
    fun `path loss exponent rejection explains the underdetermination`() {
        val entry = requireNotNull(RetiredCalculations.byId("path-loss-exponent"))
        assertTrue(entry.reason.contains("two unknowns"))
        assertTrue(entry.replacement.contains("C/N0"))
    }

    @Test
    fun `littles law is replaced by the loaded minus idle delta`() {
        val entry = requireNotNull(RetiredCalculations.byId("littles-law"))
        assertTrue(entry.reason.contains("identity"))
        assertTrue(entry.replacement.contains("Bufferbloat.deltaMilliseconds"))
    }

    @Test
    fun `every entry is fully populated so it can be reviewed without the plan`() {
        for (entry in RetiredCalculations.ALL) {
            assertTrue(entry.id.isNotBlank(), "blank id")
            assertTrue(entry.name.isNotBlank(), "blank name for ${entry.id}")
            assertTrue(entry.formula.isNotBlank(), "blank formula for ${entry.id}")
            assertTrue(entry.reason.length > 40, "reason too thin for ${entry.id}")
            assertTrue(entry.replacement.length > 20, "replacement too thin for ${entry.id}")
        }
    }
}

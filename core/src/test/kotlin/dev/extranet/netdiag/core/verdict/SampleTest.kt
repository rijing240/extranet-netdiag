package dev.extranet.netdiag.core.verdict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the measurement contract: a reading that did not happen has no number, not a zero. */
class SampleTest {

    @Test
    fun aReadingCarriesTheNumberItMeasured() {
        val sample = Sample.reading("radio", "rsrpDbm", 1_724_000_000_000L, -105.0)
        assertEquals(SampleStatus.OK, sample.status)
        assertEquals(-105.0, sample.value)
    }

    @Test
    fun aFailedAttemptCannotCarryAValue() {
        val error = assertFailsWith<IllegalArgumentException> {
            Sample("gateway-ping", "rttMillis", 1L, SampleStatus.FAILED, 12.0)
        }
        assertTrue(error.message.orEmpty().contains("FAILED"), "the message should name the status")
    }

    @Test
    fun anOkSampleMustCarryItsValue() {
        assertFailsWith<IllegalArgumentException> {
            Sample("radio", "rsrpDbm", 1L, SampleStatus.OK, null)
        }
    }

    @Test
    fun theFactoriesKeepMissingDataExplicit() {
        assertNull(Sample.failed("dns", "lookupMillis", 1L, "no resolver").value)
        assertNull(Sample.unavailable("radio", "rsrpDbm", 1L).value)
    }

    @Test
    fun aSampleMustNameWhatAndWhen() {
        assertFailsWith<IllegalArgumentException> { Sample("", "rsrpDbm", 1L, SampleStatus.OK, -1.0) }
        assertFailsWith<IllegalArgumentException> { Sample("radio", "", 1L, SampleStatus.OK, -1.0) }
        assertFailsWith<IllegalArgumentException> { Sample("radio", "rsrpDbm", -1L, SampleStatus.OK, -1.0) }
    }
}

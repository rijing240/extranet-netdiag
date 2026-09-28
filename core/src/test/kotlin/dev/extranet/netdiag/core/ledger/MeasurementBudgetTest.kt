package dev.extranet.netdiag.core.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins B1's exit criterion and the budget arithmetic that decides whether a run can meet it. */
class MeasurementBudgetTest {

    @Test
    fun `one hundred set is the number the plan asks for`() {
        assertEquals(100, MeasurementBudget.SETS_PER_RUN)
        assertEquals(0.05, MeasurementBudget.FAILURE_RATE_CEILING, 0.0)
    }

    @Test
    fun `a set's worst case is the sum of its four stage budgets`() {
        assertEquals(2_000 + 3_000 + 3_000 + 5_000, MeasurementBudget.worstCaseSetMillis())
    }

    @Test
    fun `the wall clock cap is twice the typical budget for a full run`() {
        assertEquals(200_000L, MeasurementBudget.WALL_CLOCK_CAP_MILLIS)
        assertEquals(100 * 1_000 * 2L, MeasurementBudget.WALL_CLOCK_CAP_MILLIS)
    }

    @Test
    fun `a dead network cannot finish the run and the cap says so`() {
        // Worst case total is 1,300,000 ms against a 200,000 ms cap, so a run in which every
        // stage of every set times out is cut off well short of the 100 sets it promised. The
        // number is pinned because the report's honesty depends on it: a truncated run must be
        // visible as truncated rather than as a low failure rate.
        assertEquals(1_300_000L, MeasurementBudget.worstCaseRunMillis())
        assertEquals(15, MeasurementBudget.setsWithinCapWhenEveryLayerTimesOut())
        assertTrue(MeasurementBudget.setsWithinCapWhenEveryLayerTimesOut() < MeasurementBudget.SETS_PER_RUN)
    }

    @Test
    fun `a full clean run meets the criterion`() {
        assertTrue(MeasurementBudget.meetsExitCriterion(setsAttempted = 100, failedSets = 0))
        assertTrue(MeasurementBudget.meetsExitCriterion(setsAttempted = 100, failedSets = 4))
    }

    @Test
    fun `the ceiling is exclusive so five failures in a hundred is already too many`() {
        assertEquals(0.05, MeasurementBudget.failureRate(100, 5), 0.0)
        assertFalse(MeasurementBudget.meetsExitCriterion(setsAttempted = 100, failedSets = 5))
        assertFalse(MeasurementBudget.meetsExitCriterion(setsAttempted = 100, failedSets = 50))
    }

    @Test
    fun `a short run cannot pass however few sets failed`() {
        assertFalse(MeasurementBudget.meetsExitCriterion(setsAttempted = 99, failedSets = 0))
        assertTrue(MeasurementBudget.meetsExitCriterion(setsAttempted = 101, failedSets = 0))
    }

    @Test
    fun `a run that attempted nothing reports the worst failure rate rather than a perfect one`() {
        // Zero out of zero must not read as 0%, or an engine that never starts would look
        // healthier than one that runs.
        assertEquals(1.0, MeasurementBudget.failureRate(0, 0), 0.0)
        assertFalse(MeasurementBudget.meetsExitCriterion(setsAttempted = 0, failedSets = 0))
    }
}

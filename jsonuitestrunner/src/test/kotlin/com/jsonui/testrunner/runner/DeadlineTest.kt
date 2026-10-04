package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Deadline] on hand-driven clocks (ticket android-driver-misses-a-present-
 * resource-id-for-25s): a wait is measured in the time the device ran, and
 * its last act before giving up is a look.
 */
class DeadlineTest {

    private var mono = 0L
    private var wall = 1_000_000L
    private fun deadline(budgetMs: Long) = Deadline(budgetMs, { mono }, { wall })

    /** The consumer's shape: the wall clock set 27 minutes forward mid-wait. */
    @Test
    fun aWallClockSetForwardDoesNotSpendTheBudget() {
        val d = deadline(25_000)
        mono += 1_000
        wall += 1_000 + 27 * 60_000
        assertFalse("27 min of wall clock, 1 s of device time: still waiting", d.expired())
        assertEquals(1_000, d.elapsedMs)
    }

    @Test
    fun aWallClockSetBackDoesNotStretchTheBudget() {
        val d = deadline(25_000)
        mono += 25_000
        wall -= 27 * 60_000
        assertTrue(d.expired())
    }

    @Test
    fun theBudgetIsTheMonotonicTime() {
        val d = deadline(250)
        mono += 249
        assertFalse(d.expired())
        mono += 1
        assertTrue("expired at exactly the budget", d.expired())
    }

    /**
     * The target appears while the wait sleeps across its deadline. Looking
     * last finds it; the old order (look, sleep, judge the deadline, give up)
     * gave up on the look that never happened.
     */
    @Test
    fun theLastActBeforeGivingUpIsALook() {
        val d = deadline(250)
        val looks = mutableListOf<Long>()
        val found = d.poll(intervalMs = 100, sleep = { mono += it }) {
            looks += mono
            if (mono >= 300) "card" else null
        }
        assertEquals("card", found)
        assertEquals(listOf(0L, 100L, 200L, 300L), looks)
    }

    /** The mutation the arm above exists for, run on the same clocks. */
    @Test
    fun theOldOrderGivesUpWithoutTheLastLook() {
        val d = deadline(250)
        var found: String? = null
        while (!d.expired()) {
            found = if (mono >= 300) "card" else null
            if (found != null) break
            mono += 100
        }
        assertNull("the old order misses what the sleep let appear", found)
    }

    @Test
    fun aZeroBudgetLooksOnce() {
        var calls = 0
        assertNull(deadline(0).poll(sleep = { mono += it }) { calls++; null })
        assertEquals(1, calls)
    }

    @Test
    fun aJumpIsNamedInTheReport() {
        val d = deadline(25_000)
        mono += 1_000
        wall += 1_000 + 27 * 60_000
        val text = d.describe()
        assertTrue(text, text.startsWith("waited 1000ms of a 25000ms budget (wall clock 1621000ms)"))
        assertTrue(text, text.contains("the wall clock was set during the wait"))
    }

    @Test
    fun noJumpNoJumpLine() {
        val d = deadline(25_000)
        mono += 25_000
        wall += 25_010
        val text = d.describe()
        assertEquals("waited 25000ms of a 25000ms budget (wall clock 25010ms)", text)
    }
}

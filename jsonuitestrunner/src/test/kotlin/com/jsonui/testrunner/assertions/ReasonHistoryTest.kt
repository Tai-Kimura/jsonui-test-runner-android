package com.jsonui.testrunner.assertions

import androidx.test.uiautomator.StaleObjectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReasonHistoryTest {

    private val zeroSize = "Element 'stand' exists, but its drawn box is 0x0, so it is not visible " +
        "(iOS: exists, not hittable, empty frame). Waited 5000ms."
    private val notFound = "Element 'stand' not found by resource-id within 5000ms"

    @Test
    fun oneReasonThrowsTheSameFailureUntouched() {
        val h = ReasonHistory()
        var t = 0L
        while (t <= 5000) { h.observe("$notFound\n  probe at $t", t); t += 100 }
        val last = AssertionError("$notFound\n  probe at 5000")
        assertSame(last, h.failure(last, 5000))
    }

    @Test
    fun aChangedReasonKeepsTheLastFirstAndListsBothWithTheirSpans() {
        val h = ReasonHistory()
        var t = 0L
        while (t < 2100) { h.observe(zeroSize, t); t += 100 }
        while (t <= 5000) { h.observe("$notFound\n  probe", t); t += 100 }
        val last = AssertionError("$notFound\n  probe")
        val thrown = h.failure(last, 5000)
        assertSame("the failure that ended the wait stays in the trace", last, thrown.cause)
        assertEquals(
            listOf(
                notFound,
                "  probe",
                "  waited 5.0s; the reason changed while waiting:",
                "    0.0–2.1s  exists, but its drawn box is 0x0, so it is not visible " +
                    "(iOS: exists, not hittable, empty frame). Waited 5000ms.",
                "    2.1–5.0s  not found by resource-id within 5000ms"
            ).joinToString("\n"),
            thrown.message
        )
    }

    @Test
    fun aRoundTripIsNotFoldedAway() {
        val h = ReasonHistory()
        listOf("A", "B", "B", "A").forEachIndexed { i, r -> h.observe(r, i * 100L) }
        assertEquals(listOf("A", "B", "A"), h.all.map { it.reason })
    }

    @Test
    fun aStaleLookIsNamedByItsType() {
        // uiautomator's StaleObjectException has only a no-argument constructor (no message).
        assertEquals("StaleObjectException", ReasonHistory.reasonOf(StaleObjectException()))
        assertEquals("IllegalStateException: gone", ReasonHistory.reasonOf(IllegalStateException("gone")))
        assertEquals("x", ReasonHistory.reasonOf(AssertionError("x")))
    }

    @Test
    fun aboveTheCapTheFirstThreeAndLastFourAreKept() {
        fun at(n: Int) = (0 until n).map { ReasonHistory.Segment("r$it", it * 100L) }
        // control: at the cap nothing is elided
        assertEquals(1 + ReasonHistory.MAX_SEGMENTS, ReasonHistory.renderBlock(at(ReasonHistory.MAX_SEGMENTS), 1000).lines().size)
        assertEquals(
            listOf(
                "  waited 1.0s; the reason changed while waiting:",
                "    0.0–0.1s  r0",
                "    0.1–0.2s  r1",
                "    0.2–0.3s  r2",
                "    … 2 more",
                "    0.5–0.6s  r5",
                "    0.6–0.7s  r6",
                "    0.7–0.8s  r7",
                "    0.8–1.0s  r8"
            ),
            ReasonHistory.renderBlock(at(ReasonHistory.MAX_SEGMENTS + 1), 1000).lines()
        )
    }
}

package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finger path of the clearance rule's motion, pinned on the JVM through
 * [UnstickMotion.segmentYs] (`android.graphics.Point` is a stub here, so the
 * Point form is one map away and not asserted).
 *
 * ⚠️ A path arm cannot see a fling; only a device can (UnstickMotionOnDeviceTest,
 * scratch, measured 2026-09-20 on a 1080x2400 phone: travel 129 → 105–133px,
 * travel 258 → 301–312px forward / 292–304px back, against 499–669px for
 * the old 492px swipe). What it CAN pin is the shape the measurement was
 * taken with: one moving segment, then the finger held on the end point.
 */
class UnstickMotionTest {

    @Test
    fun `the path moves once and then holds the end point`() {
        val ys = UnstickMotion.segmentYs(1300, 1042)
        assertEquals(1 + UnstickMotion.HOLD_SEGMENTS, ys.size)
        assertEquals(1300, ys.first())
        assertEquals("the finger lifts on the end point", 1042, ys.last())
        ys.drop(1).forEach { assertTrue("held within 1px of the end point: $it", it in 1042..1043) }
    }

    /**
     * Moves at an unchanged position never reach the velocity tracker, so a
     * hold of repeated points releases at the travel's velocity — a fling
     * (conf_ci AVD, 2026-10-18: 480px travel → 665–748px). Every hold point
     * differs from the one before it.
     */
    @Test
    fun `no two consecutive points of the hold are the same`() {
        val ys = UnstickMotion.segmentYs(1300, 1042)
        ys.toList().zipWithNext().forEach { (a, b) -> assertTrue("repeated point $a", a != b) }
    }

    @Test
    fun `the hold is long enough to be a hold`() {
        // STEPS moves ~5ms apart per segment: the hold must outlast the
        // ~100ms window a velocity tracker looks back over.
        assertTrue(UnstickMotion.HOLD_SEGMENTS * UnstickMotion.STEPS * 5 >= 200)
    }

    @Test
    fun `the rollback is the same path reversed`() {
        val forward = UnstickMotion.segmentYs(1300, 1042)
        val back = UnstickMotion.segmentYs(1042, 1300)
        assertEquals(forward.first(), back.last())
        assertEquals(forward.last(), back.first())
    }
}

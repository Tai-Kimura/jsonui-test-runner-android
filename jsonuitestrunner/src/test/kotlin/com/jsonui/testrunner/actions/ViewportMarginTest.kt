package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stop-position rule reported 2026-09-08.
 *
 * ⚠️ These arms measure the DECISION, not the scroll. Whether the extra
 * swipe actually moves a real Compose list is a device fact and is stated as
 * unverified in the release notes — what is verified here is that the rule
 * fires on the reported shape, stays silent on the shapes that pass today,
 * and never keeps a scroll that made things worse.
 */
class ViewportMarginTest {

    // The reporting face: landscape tablet, container viewport 1168px tall,
    // target left peeking a few pixels above the bottom edge.
    private val height = 1168
    private val width = 2560
    private val bottom = 1200

    @Test
    fun `a target peeking above the bottom edge is flush`() {
        assertTrue(
            ViewportMargin.isFlushAgainstTrailingEdge(
                targetBottom = bottom - 8, surfaceBottom = bottom,
                surfaceHeight = height, surfaceWidth = width))
    }

    @Test
    fun `a target in the middle of the viewport is not flush`() {
        // The control. Without it, a rule that answered `true` always would
        // pass the arm above -- and would add a scroll to every passing test.
        assertFalse(
            ViewportMargin.isFlushAgainstTrailingEdge(
                targetBottom = bottom - height / 2, surfaceBottom = bottom,
                surfaceHeight = height, surfaceWidth = width))
    }

    @Test
    fun `a target just outside the clearance is not flush`() {
        // The boundary, from the rule's own arithmetic rather than a guess:
        // one pixel beyond the clearance must already be quiet.
        val clearance = ViewportMargin.clearanceFor(height, width)
        assertFalse(
            ViewportMargin.isFlushAgainstTrailingEdge(
                targetBottom = bottom - clearance - 1, surfaceBottom = bottom,
                surfaceHeight = height, surfaceWidth = width))
        assertTrue(
            ViewportMargin.isFlushAgainstTrailingEdge(
                targetBottom = bottom - clearance + 1, surfaceBottom = bottom,
                surfaceHeight = height, surfaceWidth = width))
    }

    @Test
    fun `a degenerate surface never asks for a scroll`() {
        // A container whose bounds could not be read must not turn every
        // target into a flush one: an unreadable rect is not evidence.
        assertFalse(
            ViewportMargin.isFlushAgainstTrailingEdge(
                targetBottom = 10, surfaceBottom = 0,
                surfaceHeight = 0, surfaceWidth = 0))
    }

    @Test
    fun `clearance is capped so a tall surface does not demand a screenful`() {
        assertEquals(
            ViewportMargin.MAX_CLEARANCE_PX,
            ViewportMargin.clearanceFor(40000, 40000))
    }

    @Test
    fun `a scroll that removed the target is reverted`() {
        assertFalse(
            ViewportMargin.keepScrolledPosition(
                before = 1190, after = null, surfaceBottom = bottom))
    }

    @Test
    fun `a scroll that moved the target closer to the edge is reverted`() {
        assertFalse(
            ViewportMargin.keepScrolledPosition(
                before = 1000, after = 1190, surfaceBottom = bottom))
    }

    @Test
    fun `a scroll that moved the target away from the edge is kept`() {
        assertTrue(
            ViewportMargin.keepScrolledPosition(
                before = 1190, after = 900, surfaceBottom = bottom))
    }

    @Test
    fun `the clearance follows the shorter side, so swapping the axes changes nothing`() {
        // The KDoc's "shorter dimension" as behaviour rather than prose. This is
        // also why two clearances can differ within one run on one device: the
        // surface handed in is a container's VISIBLE bounds, so whichever axis
        // is shorter can change when something covers part of it.
        assertEquals(
            ViewportMargin.clearanceFor(surfaceHeight = 2400, surfaceWidth = 1080),
            ViewportMargin.clearanceFor(surfaceHeight = 1080, surfaceWidth = 2400)
        )
        assertEquals(129, ViewportMargin.clearanceFor(2400, 1080))
        // A surface reduced to 1000 on its shorter axis yields a different, and
        // equally correct, clearance — the observation that started this.
        assertEquals(120, ViewportMargin.clearanceFor(1080, 1000))
    }

    @Test
    fun `a scroll that changed nothing is kept rather than undone`() {
        // Equal is kept: reverting a no-op would spend a second swipe to
        // return to where it already is, and every extra swipe is a chance
        // to disturb something.
        assertTrue(
            ViewportMargin.keepScrolledPosition(
                before = 1000, after = 1000, surfaceBottom = bottom))
    }

    /**
     * The boundary, which is the whole reason this predicate is not
     * `Rect.contains`.
     *
     * ⚠️ `bottom == surfaceBottom` is INSIDE. A face measured
     * `flush 1307 of 1307` on a node its layout audit confirmed to be a
     * descendant of the named container, and the unstick moved it 248px. A
     * gate that dropped it would suppress a working scroll AND accuse a
     * correct test of naming the wrong container — two failures, both silent.
     */
    @Test
    fun `a target resting exactly on the edge is inside`() {
        assertFalse(ViewportMargin.isOutsideTrailingEdge(1307, 1307))
        assertFalse(ViewportMargin.isOutsideTrailingEdge(1306, 1307))
        assertTrue(ViewportMargin.isOutsideTrailingEdge(1308, 1307))
    }

    /**
     * The two real captures, kept as data rather than as prose.
     *
     * ⚠️ Both came from the same run on the same face. The one that must be
     * skipped is 168px beyond; the one that must NOT be skipped is 0px
     * beyond. Any predicate that cannot separate 0 from 168 is the wrong
     * predicate, whatever it is spelled.
     */
    @Test
    fun `the measured outside case and the measured edge case separate`() {
        assertTrue("save_button: 168px beyond its container",
            ViewportMargin.isOutsideTrailingEdge(2295, 2127))
        assertFalse("volume_field: on the edge, and the unstick moved it 248px",
            ViewportMargin.isOutsideTrailingEdge(1307, 1307))
    }
}

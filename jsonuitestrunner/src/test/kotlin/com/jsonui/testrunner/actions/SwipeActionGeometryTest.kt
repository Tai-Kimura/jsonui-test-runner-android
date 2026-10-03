package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the `swipe` action's geometry (pure function, no device).
 *
 * The incident: a full-width horizontal pager on a phone (1080px wide, 420dpi,
 * gesture navigation). The 1.15.7 line for `swipe left` started at
 * centerX + (540 - 8) = 1072, 8px from the right screen edge, inside the
 * back-gesture zone; logcat showed `CoreBackPreview: startBackNavigation`
 * and the screen popped. 48dp at density 2.625 is 126px.
 */
class SwipeActionGeometryTest {

    private val phone = 1080
    private val phoneEdge = 126 // 48dp @ 2.625

    // Full-width pager, taller than wide.
    private val pager = intArrayOf(0, 300, 1080, 2100)

    private fun line(r: IntArray, dir: String, display: Int, edge: Int) =
        swipeActionLine(r[0], r[1], r[2], r[3], dir, display, edge)

    private fun assertStartsInsideElementAndSafeBand(
        l: SwipeLine, r: IntArray, display: Int, edge: Int, inset: Int = 8
    ) {
        assertTrue("start x ${l.startX} is in the ${edge}px edge zone", l.startX in edge..(display - edge))
        assertTrue("start x ${l.startX} is not inside the element", l.startX in (r[0] + inset)..(r[2] - inset))
        assertTrue("end x ${l.endX} is not inside the element", l.endX in (r[0] + inset)..(r[2] - inset))
    }

    @Test
    fun unclampedGeometryReproducesTheIncidentLine() {
        // Control: with no edge zone the pager yields 1.15.7's line, so the
        // clamp (not some other change) is what moves the start below.
        assertEquals(SwipeLine(1072, 1200, 8, 1200), line(pager, "left", phone, 0))
        assertEquals(SwipeLine(8, 1200, 1072, 1200), line(pager, "right", phone, 0))
    }

    @Test
    fun fullWidthPhoneSwipeLeftStartsOutsideTheBackGestureZone() {
        val l = line(pager, "left", phone, phoneEdge)
        assertStartsInsideElementAndSafeBand(l, pager, phone, phoneEdge)
        assertEquals(SwipeLine(phone - phoneEdge, 1200, 8, 1200), l)
    }

    @Test
    fun fullWidthPhoneSwipeRightStartsOutsideTheBackGestureZone() {
        val l = line(pager, "right", phone, phoneEdge)
        assertStartsInsideElementAndSafeBand(l, pager, phone, phoneEdge)
        assertEquals(SwipeLine(phoneEdge, 1200, 1072, 1200), l)
    }

    @Test
    fun tabletRightPaneSwipeIsUnchanged() {
        // The incident's tablet run: pager in a right pane x 720..2560 on a
        // 2560px display swiped (2163, 821) -> (1117, 821) and paged.
        val pane = intArrayOf(720, 290, 2560, 1352)
        val before = SwipeLine(2163, 821, 1117, 821)
        assertEquals(before, line(pane, "left", 2560, 0))
        for (edge in listOf(96, 144, 192)) { // 48dp at density 2.0 / 3.0 / 4.0
            assertEquals("edge $edge", before, line(pane, "left", 2560, edge))
        }
    }

    @Test
    fun narrowElementStillSwipesInsideItself() {
        val narrow = intArrayOf(400, 0, 600, 2000)
        val l = line(narrow, "left", phone, phoneEdge)
        assertEquals(SwipeLine(592, 1000, 408, 1000), l)
        assertStartsInsideElementAndSafeBand(l, narrow, phone, phoneEdge)
        assertEquals(SwipeLine(408, 1000, 592, 1000), line(narrow, "right", phone, phoneEdge))
    }

    @Test
    fun elementPartlyInTheEdgeZoneStartsAtTheBandEdgeInsideItself() {
        val rail = intArrayOf(900, 0, 1080, 2000) // cx 990, d 82
        val l = line(rail, "left", phone, phoneEdge)
        assertEquals(SwipeLine(phone - phoneEdge, 1000, 908, 1000), l)
        assertStartsInsideElementAndSafeBand(l, rail, phone, phoneEdge)
    }

    @Test
    fun verticalSwipesClampOnlyXLikeScrollSwipe() {
        // scrollSwipe clamps x for up/down and leaves y alone; so does swipe.
        assertEquals(SwipeLine(540, 1732, 540, 668), line(pager, "up", phone, phoneEdge))
        val leftRail = intArrayOf(0, 0, 200, 2000) // cx 100 -> 126, still inside
        assertEquals(SwipeLine(phoneEdge, 908, phoneEdge, 1092), line(leftRail, "down", phone, phoneEdge))
    }

    @Test
    fun elementWhollyInsideAnEdgeZoneFailsInsteadOfSendingBack() {
        for ((r, dir) in listOf(
            intArrayOf(960, 0, 1080, 2000) to "left",   // right edge: start 1072 -> 954 < 968
            intArrayOf(0, 0, 100, 2000) to "down",      // left edge: x 50 -> 126 > 92
        )) {
            try {
                line(r, dir, phone, phoneEdge)
                fail("expected a failure for ${r.toList()} $dir")
            } catch (e: SwipeOutsideGestureSafeBandException) {
                val msg = e.message.orEmpty(); assertTrue(msg, msg.contains("edge zone"))
            }
        }
    }

    @Test
    fun noEdgeZoneLeavesEveryDirectionAsBefore() {
        // 3-button navigation still has the 48dp floor in gestureEdgeInsetPx,
        // but edge 0 is the pure 1.15.7 geometry for all four directions.
        assertEquals(SwipeLine(540, 1732, 540, 668), line(pager, "up", phone, 0))
        assertEquals(SwipeLine(540, 668, 540, 1732), line(pager, "down", phone, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDirectionThrows() {
        line(pager, "sideways", phone, phoneEdge)
    }
}

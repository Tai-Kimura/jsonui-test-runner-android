package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure text must not claim the app never rendered something when the
 * evidence cannot separate that from "rendered below the viewport".
 */
class OffscreenPossibilityTest {

    @Test
    fun `a scrollable container with room means it may be off-screen`() {
        assertEquals(
            OffscreenPossibility.ROOM_LEFT,
            OffscreenPossibility.of(scrollable = true, atEnd = false))
    }

    @Test
    fun `a scrollable container at its end rules the off-screen case out`() {
        assertEquals(
            OffscreenPossibility.NO_ROOM_LEFT,
            OffscreenPossibility.of(scrollable = true, atEnd = true))
    }

    @Test
    fun `a container that is not scrollable is UNKNOWN not NO_ROOM_LEFT`() {
        // ⚠️ The arm that keeps the overclaim from coming back by a side door.
        // Folding "not scrollable" into "no room" would restore the confident
        // sentence for exactly the containers we understand least.
        assertEquals(
            OffscreenPossibility.UNKNOWN,
            OffscreenPossibility.of(scrollable = false, atEnd = true))
        assertEquals(
            OffscreenPossibility.UNKNOWN,
            OffscreenPossibility.of(scrollable = false, atEnd = false))
    }

    private val before = setOf("a", "b")

    private fun render(offscreen: OffscreenPossibility) = ProjectionReport.render(
        id = "release_event_year_select",
        before = before,
        afterClearCache = before,
        afterServiceResync = before,
        offscreen = offscreen)

    @Test
    fun `with room left the message refuses to blame the app`() {
        val text = render(OffscreenPossibility.ROOM_LEFT)
        assertTrue(text, text.contains("does NOT distinguish"))
        assertTrue(text, !text.contains("the app side never projected it"))
    }

    @Test
    fun `with no room left the original claim is warranted and kept`() {
        // The control for the arm above: the sentence must still be reachable,
        // or the fix would have deleted a true statement rather than bounding
        // a false one.
        val text = render(OffscreenPossibility.NO_ROOM_LEFT)
        assertTrue(text, text.contains("the app side never projected it"))
    }

    @Test
    fun `unknown says it is unknown`() {
        val text = render(OffscreenPossibility.UNKNOWN)
        assertTrue(text, text.contains("UNKNOWN"))
        assertTrue(text, !text.contains("the app side never projected it"))
    }
}

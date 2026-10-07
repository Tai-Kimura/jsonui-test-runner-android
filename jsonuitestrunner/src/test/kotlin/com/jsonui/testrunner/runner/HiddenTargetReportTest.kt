package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A View in the tree but not visible to the user (a Views ScrollView keeps
 * off-screen children) is PRESENT in the census; the report must still say
 * why it was not found and what the scroll container allows. Until 1.15.12
 * it said PRESENT_AT_FAILURE and nothing else.
 */
class HiddenTargetReportTest {

    private val id = "error_label"
    private val census = setOf("form_scroll", "age_field", id)
    private val hidden = TargetPresence(inTree = true, visibleToUser = false, bounds = "[0,2202][1080,2190]")

    private fun container(forward: Boolean, backward: Boolean, scrollable: Boolean = true) =
        ResolvedContainer("form_scroll", ContainerSource.TARGET_ANCESTOR, scrollable, forward, backward)

    private fun render(container: ResolvedContainer?, presence: TargetPresence?) =
        ProjectionReport.render(id, census, census, census, container = container, presence = presence)

    @Test
    fun `a hidden target with room left is named as off-screen`() {
        val text = render(container(forward = true, backward = true), hidden)
        assertTrue(text, text.contains("PRESENT_AT_FAILURE"))
        assertTrue(text, text.contains("isVisibleToUser=false, bounds [0,2202][1080,2190]"))
        assertTrue(text, text.contains("most likely off-screen"))
        assertTrue(text, text.contains("scroll container: 'form_scroll' (from the target's scrollable ancestor)"))
    }

    @Test
    fun `room in either direction keeps the off-screen case open`() {
        // A target can lie above the viewport as well as below it.
        assertEquals(OffscreenPossibility.ROOM_LEFT, container(forward = false, backward = true).possibility)
        assertEquals(OffscreenPossibility.ROOM_LEFT, container(forward = true, backward = false).possibility)
        assertEquals(OffscreenPossibility.NO_ROOM_LEFT, container(forward = false, backward = false).possibility)
        assertEquals(OffscreenPossibility.UNKNOWN,
            container(forward = true, backward = true, scrollable = false).possibility)
    }

    @Test
    fun `a hidden target with no room left is not blamed on the scroll`() {
        val text = render(container(forward = false, backward = false), hidden)
        assertTrue(text, text.contains("scrolling will not bring it in"))
        assertFalse(text, text.contains("most likely off-screen"))
    }

    @Test
    fun `a hidden target with no container says UNKNOWN`() {
        val text = render(null, hidden)
        assertTrue(text, text.contains("UNKNOWN"))
        assertFalse(text, text.contains("most likely off-screen"))
    }

    @Test
    fun `a visible target gets no hidden-target lines`() {
        // The control: PRESENT and visible means the lookup failed for another
        // reason, and the report must not invent an off-screen story for it.
        val text = render(container(forward = true, backward = true),
            TargetPresence(inTree = true, visibleToUser = true, bounds = "[0,0][10,10]"))
        assertFalse(text, text.contains("not visible to the user"))
        assertFalse(text, text.contains("scroll container:"))
    }

    @Test
    fun `a missing id uses the resolved container for its verdict`() {
        // Compose does not project off-screen nodes: there the shape is
        // STILL_MISSING, and the container now decides the sentence.
        val census = setOf("form_scroll")
        val room = ProjectionReport.render(id, census, census, census,
            container = container(forward = true, backward = false),
            presence = TargetPresence(inTree = false, visibleToUser = false, bounds = null))
        assertTrue(room, room.contains("STILL_MISSING"))
        assertTrue(room, room.contains("does NOT distinguish"))
        assertTrue(room, room.contains("scroll container: 'form_scroll'"))
        val end = ProjectionReport.render(id, census, census, census,
            container = container(forward = false, backward = false))
        assertTrue(end, end.contains("the app side never projected it"))
    }
}

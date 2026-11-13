package com.jsonui.testrunner.actions

import com.jsonui.testrunner.actions.MoveItemPlan.Box
import com.jsonui.testrunner.actions.MoveItemPlan.Handle
import com.jsonui.testrunner.actions.MoveItemPlan.Outcome
import com.jsonui.testrunner.actions.MoveItemPlan.Point
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * MoveItemPlan against jsonui-test-runner specs/vectors/move-item.json (src/test/resources/vectors/move-item.json is
 * its byte copy): every case's press and release, or its fail, and every verify case's scrolled, as the iOS and web
 * drivers judge them.
 */
class MoveItemPlanVectorsTest {
    private val doc: JsonObject = Json.parseToJsonElement(File(VECTORS).readText()).jsonObject

    private fun box(a: JsonArray) = a.map { it.jsonPrimitive.double }.let { Box(it[0], it[1], it[2], it[3]) }
    private fun point(a: JsonArray) = a.map { it.jsonPrimitive.double }.let { Point(it[0], it[1]) }

    @Test
    fun `every case plans as the vectors say`() {
        val cases = doc.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(25, cases.size)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val handle = when {
                !c.containsKey("handle") -> Handle.None
                c["handle"] is JsonNull -> Handle.Given(null)
                else -> Handle.Given(box(c.getValue("handle").jsonArray))
            }
            val outcome = MoveItemPlan.plan(
                id = "list",
                from = c.getValue("from").jsonPrimitive.int,
                to = c.getValue("to").jsonPrimitive.int,
                section = c["section"]?.jsonPrimitive?.int ?: 0,
                viewport = box(c.getValue("viewport").jsonArray),
                list = c["list"]?.let { box(it.jsonArray) },
                rows = c.getValue("rows").jsonObject.mapValues { (_, v) -> v.jsonArray.map { box(it.jsonArray) } },
                handle = handle,
                canScrollUp = c["canScrollUp"]?.jsonPrimitive?.boolean ?: true,
                canScrollDown = c["canScrollDown"]?.jsonPrimitive?.boolean ?: true,
                edgeMargin = doc.getValue("edgeMargin").jsonPrimitive.double,
                listMargin = doc.getValue("listMargin").jsonPrimitive.double
            )
            val expected = c["fail"]?.let { Outcome.Fail(it.jsonPrimitive.content) }
                ?: Outcome.Gesture(point(c.getValue("press").jsonArray), point(c.getValue("release").jsonArray))
            assertEquals(name, expected, outcome)
        }
    }

    @Test
    fun `every verify case says scrolled as the vectors say`() {
        val cases = doc.getValue("verify").jsonArray.map { it.jsonObject }
        assertEquals(5, cases.size)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val before = box(c.getValue("before").jsonArray)
            val after = c.getValue("after").let { if (it is JsonNull) null else box(it.jsonArray) }
            assertEquals(name, c.getValue("scrolled").jsonPrimitive.boolean, MoveItemPlan.scrolled(before, after))
            // offScreen: the sentence says the row moved off the screen, not by how much.
            val offScreen = c["offScreen"]?.jsonPrimitive?.boolean ?: false
            assertEquals(name, offScreen, MoveItemPlan.scrolledSentence("list_item_7", before, after).contains("('list_item_7' moved off the screen)"))
        }
    }

    @Test
    fun `the timing and the margins are the vectors'`() {
        val timing = doc.getValue("timing").jsonObject
        assertEquals(timing.getValue("pressSeconds").jsonPrimitive.double * 1000, MoveItemPlan.PRESS_MS.toDouble(), 0.0)
        assertEquals(timing.getValue("holdSeconds").jsonPrimitive.double * 1000, MoveItemPlan.HOLD_MS.toDouble(), 0.0)
        assertEquals(doc.getValue("edgeMargin").jsonPrimitive.double, MoveItemPlan.EDGE_MARGIN, 0.0)
        assertEquals(doc.getValue("listMargin").jsonPrimitive.double, MoveItemPlan.LIST_MARGIN, 0.0)
    }

    @Test
    fun `each fail names its sentence, as the iOS and web drivers say it`() {
        val s = { r: String -> MoveItemPlan.sentence(r, from = 3, handle = "grip") }
        assertEquals("moveItem: 'from' and 'to' are the same row (3): nothing to move", s("same"))
        assertEquals("moveItem moves within section 0 (v1)", s("section"))
        assertEquals(
            "moveItem: 'list_item_0' is more than one element (a Collection of several sections: v1 moves within a single section)",
            s("ambiguous:list_item_0")
        )
        assertEquals("moveItem: 'list_item_9' was not found", s("missing:list_item_9"))
        assertEquals(
            "moveItem: 'list_item_12' is not on screen (scroll it into view first, e.g. scrollUntilVisible; the drag does not scroll)",
            s("offscreen:list_item_12")
        )
        assertEquals(
            "moveItem: 'list_item_9' is too near the top or bottom of the screen (100) or of its list (56): the list scrolls by itself there under a held finger; scroll it towards the middle first",
            s("edge:list_item_9")
        )
        assertEquals("moveItem: the handle 'grip' was not found inside the row (from 3)", s("no-handle"))
        assertEquals(
            "moveItem: the list scrolled during the drag ('list_item_7' moved by 30); the row may have landed elsewhere — scroll the rows away from the list's edges first",
            MoveItemPlan.scrolledSentence("list_item_7", Box(0.0, 400.0, 400.0, 44.0), Box(0.0, 429.6, 400.0, 44.0))
        )
    }

    @Test
    fun `verify compares y only, so a scroll is never said as moved by 0`() {
        val before = Box(0.0, 400.0, 400.0, 44.0)
        assertTrue(!MoveItemPlan.scrolled(before, Box(3.0, 400.2, 397.0, 44.0)))
        assertTrue(MoveItemPlan.scrolled(before, Box(0.0, 401.6, 400.0, 44.0)))
        assertTrue(MoveItemPlan.scrolledSentence("list_item_7", before, Box(0.0, 401.6, 400.0, 44.0)).contains("moved by 2"))
    }

    @Test
    fun `the same row and a section other than 0 fail before anything is looked for`() {
        assertEquals("same", MoveItemPlan.early(2, 2, 0))
        assertEquals("same", MoveItemPlan.early(2, 2, 1))
        assertEquals("section", MoveItemPlan.early(0, 1, 1))
        assertEquals(null, MoveItemPlan.early(0, 1, 0))
    }

    private val visible = Box(0.0, 100.0, 400.0, 500.0)
    private fun rowsAt(vararg tops: Pair<Int, Double>) = tops.associate { (n, top) -> n to Box(0.0, top, 400.0, 50.0) }

    @Test
    fun `the witness is a row outside the moved range wholly on screen, lo-1, hi+1, lo-2, hi+2`() {
        val all = rowsAt(0 to 100.0, 1 to 150.0, 2 to 200.0, 3 to 250.0, 4 to 300.0, 5 to 560.0)
        assertEquals(0, MoveItemPlan.witness(1, 3, all, visible))
        assertEquals(4, MoveItemPlan.witness(0, 3, all, visible))
        // hi+1 (5) is cut at the bottom; lo-2 does not exist; hi+2 is not found: none.
        assertEquals(null, MoveItemPlan.witness(4, 0, all, visible))
        assertEquals(2, MoveItemPlan.witness(5, 3, all, visible))
    }

    @Test
    fun `a row ending at the window's edge is taken as running past it`() {
        val window = Box(0.0, 0.0, 411.0, 890.0)
        val cut = Box(0.0, 850.0, 411.0, 40.0)      // a 64 dp row the window cut to 40
        val past = MoveItemPlan.pastWindowEdge(cut, window)
        assertTrue(!window.contains(past))
        // Read as it was (the clipped rect inside the window), the cut row was a whole row on screen: the hole this
        // closes. Taken past the edge, it is offscreen and no witness.
        assertTrue(window.contains(cut))
        assertEquals(Outcome.Fail("offscreen:list_item_1"), MoveItemPlan.plan("list", 0, 1, 0, window, null,
            mapOf("list_item_0" to listOf(Box(0.0, 400.0, 411.0, 64.0)), "list_item_1" to listOf(past))))
        assertEquals(null, MoveItemPlan.witness(2, 0, mapOf(3 to past), window))
        val top = MoveItemPlan.pastWindowEdge(Box(0.0, 0.0, 411.0, 30.0), window)
        assertTrue(!window.contains(top))
        val inside = Box(0.0, 400.0, 411.0, 64.0)
        assertEquals(inside, MoveItemPlan.pastWindowEdge(inside, window))
    }

    @Test
    fun `the drag path moves straight, no faster than asked, and ends on the release`() {
        val path = MoveItemPlan.path(Point(200.0, 300.0), Point(200.0, 700.0), speedPerS = 500.0)
        assertEquals(Point(200.0, 700.0), path.last())
        assertTrue(path.all { it.x == 200.0 })
        val steps = (listOf(Point(200.0, 300.0)) + path).zipWithNext { a, b -> b.y - a.y }
        assertTrue(steps.toString(), steps.all { it > 0 && it <= 500.0 * 16 / 1000 + 1e-9 })
        assertEquals(1, MoveItemPlan.path(Point(0.0, 0.0), Point(0.0, 1.0), 500.0).size)
    }

    /**
     * The copy is compared with its source when the source is at hand: $JSONUI_TEST_RUNNER_DIR, or this driver as
     * jsonui-test-runner's submodule (drivers/android). This repo's CI checks out the driver alone, so there it is
     * skipped — and says so.
     */
    @Test
    fun `the vector is a byte copy of jsonui-test-runner's`() {
        val source = listOfNotNull(
            System.getenv("JSONUI_TEST_RUNNER_DIR")?.let { File(it, "specs/vectors/move-item.json") },
            File("../../../specs/vectors/move-item.json")
        ).firstOrNull { it.isFile }
        assumeTrue("jsonui-test-runner specs/vectors not found: the copy is not compared here", source != null)
        assertArrayEquals(source!!.readBytes(), File(VECTORS).readBytes())
    }

    private companion object {
        const val VECTORS = "src/test/resources/vectors/move-item.json"
    }
}

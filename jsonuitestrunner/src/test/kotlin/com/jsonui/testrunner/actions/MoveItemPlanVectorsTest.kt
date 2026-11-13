package com.jsonui.testrunner.actions

import com.jsonui.testrunner.actions.MoveItemPlan.Box
import com.jsonui.testrunner.actions.MoveItemPlan.Handle
import com.jsonui.testrunner.actions.MoveItemPlan.Outcome
import com.jsonui.testrunner.actions.MoveItemPlan.Point
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * its byte copy): every case's press and release, or its fail, as the iOS and web drivers plan it.
 */
class MoveItemPlanVectorsTest {
    private val doc: JsonObject = Json.parseToJsonElement(File(VECTORS).readText()).jsonObject

    private fun box(a: JsonArray) = a.map { it.jsonPrimitive.double }.let { Box(it[0], it[1], it[2], it[3]) }
    private fun point(a: JsonArray) = a.map { it.jsonPrimitive.double }.let { Point(it[0], it[1]) }

    @Test
    fun `every case plans as the vectors say`() {
        val cases = doc.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(16, cases.size)
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
                rows = c.getValue("rows").jsonObject.mapValues { (_, v) -> v.jsonArray.map { box(it.jsonArray) } },
                handle = handle,
                edgeMargin = doc.getValue("edgeMargin").jsonPrimitive.double
            )
            val expected = c["fail"]?.let { Outcome.Fail(it.jsonPrimitive.content) }
                ?: Outcome.Gesture(point(c.getValue("press").jsonArray), point(c.getValue("release").jsonArray))
            assertEquals(name, expected, outcome)
        }
    }

    @Test
    fun `the timing and the margin are the vectors'`() {
        val timing = doc.getValue("timing").jsonObject
        assertEquals(timing.getValue("pressSeconds").jsonPrimitive.double * 1000, MoveItemPlan.PRESS_MS.toDouble(), 0.0)
        assertEquals(timing.getValue("holdSeconds").jsonPrimitive.double * 1000, MoveItemPlan.HOLD_MS.toDouble(), 0.0)
        assertEquals(doc.getValue("edgeMargin").jsonPrimitive.double, MoveItemPlan.EDGE_MARGIN, 0.0)
        // On a device the margin is 100 dp: on a 420 dpi phone (2.625 px per dp) 262.5 px, clear of
        // KotlinJsonUI's 48 dp auto-scroll band (126 px), which 100 px is not.
        assertEquals(262.5, MoveItemPlan.edgeMarginPx(2.625f), 1e-9)
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
            "moveItem: 'list_item_9' is too near the screen's top or bottom (within 100: a list scrolls by itself there under a held finger); scroll it towards the middle first",
            s("edge:list_item_9")
        )
        assertEquals("moveItem: the handle 'grip' was not found inside the row (from 3)", s("no-handle"))
    }

    @Test
    fun `the same row and a section other than 0 fail before anything is looked for`() {
        assertEquals("same", MoveItemPlan.early(2, 2, 0))
        assertEquals("same", MoveItemPlan.early(2, 2, 1))
        assertEquals("section", MoveItemPlan.early(0, 1, 1))
        assertEquals(null, MoveItemPlan.early(0, 1, 0))
    }

    @Test
    fun `the drag path moves straight, no faster than asked, and ends on the release`() {
        val path = MoveItemPlan.path(Point(200.0, 300.0), Point(200.0, 700.0), speedPxPerS = 500.0)
        assertEquals(Point(200.0, 700.0), path.last())
        assertTrue(path.all { it.x == 200.0 })
        val steps = (listOf(Point(200.0, 300.0)) + path).zipWithNext { a, b -> b.y - a.y }
        assertTrue(steps.toString(), steps.all { it > 0 && it <= 500.0 * 16 / 1000 + 1e-9 })
        assertEquals(1, MoveItemPlan.path(Point(0.0, 0.0), Point(0.0, 1.0), 500.0).size)
    }

    @Test
    fun `on a device the list that scrolls the rows refuses a row past its edge and a finger inside its band`() {
        val screen = Box(0.0, 0.0, 1080.0, 2337.0)
        val list = Box(0.0, 1004.0, 1080.0, 866.0) // measured: a 330 dp LazyColumn on a 420 dpi emulator
        val rows = mapOf(
            "stops_item_0" to listOf(Box(0.0, 1004.0, 1080.0, 168.0)), "stops_item_1" to listOf(Box(0.0, 1193.0, 1080.0, 168.0)),
            "stops_item_2" to listOf(Box(0.0, 1382.0, 1080.0, 168.0)), "stops_item_4" to listOf(Box(0.0, 1760.0, 1080.0, 168.0))
        )
        fun plan(from: Int, to: Int, list: Box? = null, rowsHere: Map<String, List<Box>> = rows) = MoveItemPlan.planOnDevice(
            "stops", from, to, 0, screen, list, rowsHere, Handle.None,
            screenMargin = MoveItemPlan.edgeMarginPx(2.625f), listMargin = MoveItemPlan.LIST_MARGIN_DP * 2.625
        )
        // The screen alone takes row 4 (inside it) and row 0 (far from its edges) ...
        assertTrue(plan(1, 4) is Outcome.Gesture)
        assertTrue(plan(0, 2) is Outcome.Gesture)
        // ... the list does not: row 4 runs past its bottom, row 0's centre is 84 px (32 dp) below its top.
        assertEquals(Outcome.Fail("offscreen:stops_item_4"), plan(1, 4, list))
        assertEquals(Outcome.Fail("edge:stops_item_0"), plan(0, 2, list))
        assertEquals(Outcome.Gesture(Point(540.0, 1277.0), Point(540.0, 1508.0)), plan(1, 2, list))
        // The vectors' own failures come first, and the screen's offscreen before the list's edge.
        assertEquals(Outcome.Fail("missing:stops_item_9"), plan(1, 9, list))
        assertEquals(Outcome.Fail("same"), plan(1, 1, list))
        val belowScreen = rows + ("stops_item_4" to listOf(Box(0.0, 2300.0, 1080.0, 168.0)))
        assertEquals(Outcome.Fail("offscreen:stops_item_4"), plan(0, 4, list, belowScreen))
        // The screen's edge holds where the list reaches the screen's bottom.
        val tall = Box(0.0, 400.0, 1080.0, 1937.0)
        val nearBottom = rows + ("stops_item_4" to listOf(Box(0.0, 2150.0, 1080.0, 168.0)))
        assertEquals(Outcome.Fail("edge:stops_item_4"), plan(1, 4, tall, nearBottom))
    }

    @Test
    fun `intersect is the part of a inside b`() {
        assertEquals(Box(0.0, 100.0, 50.0, 50.0), MoveItemPlan.intersect(Box(0.0, 0.0, 100.0, 150.0), Box(-10.0, 100.0, 60.0, 500.0)))
        assertEquals(0.0, MoveItemPlan.intersect(Box(0.0, 0.0, 10.0, 10.0), Box(20.0, 20.0, 5.0, 5.0)).height, 0.0)
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

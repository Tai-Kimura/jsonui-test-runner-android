package com.jsonui.testrunner.composeprobe

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jsonui.testrunner.actions.ActionExecutor
import com.jsonui.testrunner.models.TestStep
import com.jsonui.testrunner.runner.MoveItemNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * moveItem on rows KotlinJsonUI's own drag draws (ReorderProbeActivity, the library's CollectionReorder as kjui emits
 * it): the row lands where `to` says, going down and up, pressed on the row or on its handle; a locked row stops it as
 * the shared rule says; and every refusal of the plan moves nothing and names the row.
 */
@RunWith(AndroidJUnit4::class)
class MoveItemOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val actions = ActionExecutor(device, defaultTimeout = 3000L)

    private fun launch(mode: String) {
        ReorderProbeActivity.reset()
        val ctx = instrumentation.targetContext
        ctx.startActivity(
            Intent(ctx, ReorderProbeActivity::class.java).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (!ReorderProbeActivity.resumed && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("probe did not come up", device.wait(Until.hasObject(By.res("stops_item_0")), 10_000))
        device.waitForIdle(1000)
        // What the driver reads, for the record (logcat MoveItemOnDevice).
        val found = MoveItemNodes.boxes(MoveItemNodes.rows((0..19).map { "stops_item_$it" }, null))
        found.filterValues { it.isNotEmpty() }.forEach { (k, v) -> Log.i("MoveItemOnDevice", "$mode $k $v") }
        MoveItemNodes.rows(listOf("stops_item_0"), null)["stops_item_0"]?.singleOrNull()?.let {
            Log.i("MoveItemOnDevice", "$mode grip in row 0: ${MoveItemNodes.handle(it, "grip")}")
            Log.i("MoveItemOnDevice", "$mode the list that scrolls row 0: ${MoveItemNodes.scrollContainer(it)}")
            Log.i("MoveItemOnDevice", "$mode canScroll (up, down) from the list's actions: ${MoveItemNodes.scrollNode(it)?.let { l -> MoveItemNodes.canScroll(l) }}")
        }
    }

    private fun move(from: Int, to: Int, handle: String? = null) =
        actions.execute(TestStep(action = "moveItem", id = "stops", from = from, to = to, handle = handle))

    private fun order(vararg moved: Pair<Int, Int>, start: List<String> = ReorderProbeActivity.INITIAL): List<String> {
        val list = start.toMutableList()
        moved.forEach { (f, t) -> list.add(t, list.removeAt(f)) }
        return list
    }

    private fun refused(from: Int, to: Int, handle: String? = null, says: String, initial: List<String> = ReorderProbeActivity.INITIAL) {
        try {
            move(from, to, handle)
            fail("moveItem $from -> $to was not refused")
        } catch (e: AssertionError) {
            assertEquals(says, e.message)
        }
        device.waitForIdle(500)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), ReorderProbeActivity.moves)
        assertEquals(initial, ReorderProbeActivity.rows.toList())
    }

    @Test
    fun aListRowGoesDownToTo() {
        launch("list")
        move(1, 3)
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 1, 3)), ReorderProbeActivity.moves)
        assertEquals(order(1 to 3), ReorderProbeActivity.rows.toList())
    }

    @Test
    fun aListRowGoesUpToTo() {
        launch("list")
        move(5, 1)
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 5, 1)), ReorderProbeActivity.moves)
        assertEquals(order(5 to 1), ReorderProbeActivity.rows.toList())
    }

    @Test
    fun aRowNextToItsPlaceMovesByOne() {
        launch("list")
        move(2, 3)
        move(3, 2)
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 2, 3), Triple(0, 3, 2)), ReorderProbeActivity.moves)
        assertEquals(ReorderProbeActivity.INITIAL, ReorderProbeActivity.rows.toList())
    }

    @Test
    fun aHandleRowIsPressedOnItsHandle() {
        launch("handle")
        move(0, 2, handle = "grip")
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 0, 2)), ReorderProbeActivity.moves)
        assertEquals(order(0 to 2), ReorderProbeActivity.rows.toList())
    }

    @Test
    fun withoutTheHandleARowOfACollectionWithOneIsNotPickedUp() {
        launch("handle")
        move(0, 2)
        device.waitForIdle(1000)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), ReorderProbeActivity.moves)
        assertEquals(ReorderProbeActivity.INITIAL, ReorderProbeActivity.rows.toList())
    }

    @Test
    fun aLockedRowStopsTheMoveAsTheSharedRuleSays() {
        launch("handle")
        // Row 3 is locked: a row from above stops just before it (shared/core/reorder_vectors.json, locked rows clamp).
        move(1, 5, handle = "grip")
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 1, 2)), ReorderProbeActivity.moves)
    }

    @Test
    fun theRefusalsMoveNothingAndNameTheRow() {
        launch("list")
        refused(1, 1, says = "moveItem: 'from' and 'to' are the same row (1): nothing to move")
        refused(1, 99, says = "moveItem: 'stops_item_99' was not found")
        // A lazy row out of view is not composed, so not in the tree: it is named as not found.
        refused(1, 19, says = "moveItem: 'stops_item_19' was not found")
        refused(1, 2, handle = "grip", says = "moveItem: the handle 'grip' was not found inside the row (from 1)")
    }

    @Test
    fun aRowHalfUnderItsListsEdgeIsNotOnScreenAMoveInsideLands() {
        launch("short")
        // Row 4 is drawn 42 of its 64 dp; its bounds are the whole row (Compose clips them to the window only),
        // past the list's bottom.
        refused(1, 4, says = "moveItem: 'stops_item_4' is not on screen (scroll it into view first, e.g. scrollUntilVisible; the drag does not scroll)")
        move(1, 2)
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 1, 2)), ReorderProbeActivity.moves)
        assertEquals(order(1 to 2), ReorderProbeActivity.rows.toList())
    }

    @Test
    fun theFirstRowAtTheTopOfAListAtItsTopMoves() {
        // Row 0's centre is 32 dp below the list's top, inside KotlinJsonUI's 48 dp band — but the list is at its
        // top, so nothing scrolls there (canScrollUp false).
        launch("short")
        move(0, 2)
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 0, 2)), ReorderProbeActivity.moves)
        assertEquals(order(0 to 2), ReorderProbeActivity.rows.toList())
    }

    @Test
    fun theLastRowAtTheBottomOfAListAtItsEndMovesBothWays() {
        // Six rows in a list exactly their height: it cannot scroll either way, so a release 16 dp above its bottom
        // is clear (canScrollDown false).
        launch("few")
        val six = ReorderProbeActivity.INITIAL.take(6)
        move(3, 5)
        move(5, 0)
        device.waitForIdle(1000)
        assertEquals(listOf(Triple(0, 3, 5), Triple(0, 5, 0)), ReorderProbeActivity.moves)
        assertEquals(order(3 to 5, 5 to 0, start = six), ReorderProbeActivity.rows.toList())
    }

    @Test
    fun aHeaderCutAtTheListsTopIsSeenTheMoveIsRefusedBeforeAnythingMoves() {
        // Row 0 is whole and the header above it is cut: the list can still scroll up. Read from the list's scroll
        // actions, the driver sees it, and a release 26 dp below the list's top (inside the 48 dp band) is refused.
        launch("header")
        refused(2, 0, says = "moveItem: 'stops_item_0' is too near the top or bottom of the screen (100) or of its list (56): the list scrolls by itself there under a held finger; scroll it towards the middle first")
    }

    @Test
    fun aReleaseNearTheBottomIsRefused() {
        launch("list")
        // The last whole row on screen is within 100 dp of the bottom (64 dp rows, 8 dp apart).
        val rows = MoveItemNodes.boxes(MoveItemNodes.rows((0..19).map { "stops_item_$it" }, null))
        val bottom = device.displayHeight.toDouble()
        val last = (0..19).last { n -> rows["stops_item_$n"].orEmpty().singleOrNull()?.let { it.bottom <= bottom && it.height > 100 } == true }
        refused(2, last, says = "moveItem: 'stops_item_$last' is too near the top or bottom of the screen (100) or of its list (56): the list scrolls by itself there under a held finger; scroll it towards the middle first")
    }
}

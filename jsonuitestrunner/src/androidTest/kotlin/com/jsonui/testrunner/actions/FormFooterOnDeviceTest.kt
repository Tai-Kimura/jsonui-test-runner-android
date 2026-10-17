package com.jsonui.testrunner.actions

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.assertions.AssertionExecutor
import com.jsonui.testrunner.models.TestStep
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where scrollUntilVisible leaves a form's last items, by the path it took
 * (FormFooterProbeActivity; ticket android-driver-scroll-until-visible-stops-
 * at-two-different-offsets-for-the-same-target). From each starting offset
 * the step scrolls to `age_field` inside `form_scroll`; then it prints where
 * age_field stopped, the scroll offset, what the accessibility tree holds
 * for `error_label` (present / visible to the user / bounds), and what a
 * following `visible error_label` said.
 *
 * [whereTheFormStopsByStartingOffset] is a MEASUREMENT: it prints
 * `[FormFooter]` rows and asserts nothing. The arms assert.
 *
 * No CI lane runs androidTest here; execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.jsonui.testrunner.actions.FormFooterOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class FormFooterOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    private fun launch() {
        val ctx = instrumentation.context
        FormFooterProbeActivity.scrollView = null
        ctx.startActivity(
            Intent(ctx, FormFooterProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (FormFooterProbeActivity.scrollView == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        device.wait(androidx.test.uiautomator.Until.hasObject(By.res(FormFooterProbeActivity.SCROLL_ID)), 10_000)
        instrumentation.waitForIdleSync()
    }

    private fun scrollTo(y: Int) {
        instrumentation.runOnMainSync { FormFooterProbeActivity.scrollView!!.scrollTo(0, y) }
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
    }

    private fun scrollY(): Int {
        var y = -1
        instrumentation.runOnMainSync { y = FormFooterProbeActivity.scrollView!!.scrollY }
        return y
    }

    private fun maxScroll(): Int {
        var m = 0
        instrumentation.runOnMainSync {
            val sv = FormFooterProbeActivity.scrollView!!
            m = (sv.getChildAt(0).height - (sv.height - sv.paddingTop - sv.paddingBottom)).coerceAtLeast(0)
        }
        return m
    }

    /** The raw tree's node for an id, off-screen ones included. */
    private fun rawNode(id: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.viewIdResourceName == id) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun describe(id: String): String {
        val n = rawNode(id) ?: return "absent from the tree"
        val r = Rect().also { n.getBoundsInScreen(it) }
        val found = device.findObject(By.res(id)) != null
        return "inTree visibleToUser=${n.isVisibleToUser} bounds=${r.toShortString()} findObject=$found"
    }

    @Test
    fun diagnoseIds() {
        launch()
        for (y in listOf(0, maxScroll())) {
            scrollTo(y)
            for (id in listOf("field_0", "field_13", FormFooterProbeActivity.TARGET_ID, "add_row_button",
                    FormFooterProbeActivity.BELOW_ID, FormFooterProbeActivity.SCROLL_ID, FormFooterProbeActivity.FOOTER_ID)) {
                println("[FormFooterDiag] scrollY=${scrollY()} $id: ${describe(id)}")
            }
        }
    }

    /**
     * Ticket android-driver-scroll-until-visible-on-views-counts-an-offscreen-
     * child-as-found. A Views ScrollView keeps its off-screen children in the
     * accessibility tree (`isVisibleToUser=false`), so the accessibility-action
     * scroll took "the id is in the tree" for "found" and stopped before its
     * first action. Measured 2026-10-08 on this specimen: 7 of 7 starts above
     * the target failed (0 settle samples, then 8 re-approach drags the wrong
     * way). Mutation: accepting an invisible node again turns this red.
     */
    /**
     * The largest offset that still leaves age_field wholly below the
     * viewport, from the laid-out column. The second start is half of it, not
     * half the maximum offset: on a 1080x2400 phone AVD (420dpi; 2026-10-18)
     * half the maximum (472) already showed age_field, and the arm's own
     * precondition failed 2 of 2 runs before scrollUntilVisible was called.
     */
    private fun lastOffsetWithTheTargetBelow(): Int {
        var y = -1
        instrumentation.runOnMainSync {
            val sv = FormFooterProbeActivity.scrollView!!
            val column = sv.getChildAt(0) as android.view.ViewGroup
            val viewport = sv.height - sv.paddingTop - sv.paddingBottom
            y = column.getChildAt(FormFooterProbeActivity.ROWS_ABOVE).top - viewport - 1
        }
        return y
    }

    @Test
    fun scrollUntilVisibleReachesATargetBelowTheViewport() {
        launch()
        val last = lastOffsetWithTheTargetBelow()
        assertTrue("the probe cannot start above age_field here: last offset $last", last >= 2)
        for (start in listOf(0, last / 2)) {
            launch()
            scrollTo(start)
            assertTrue("start=$start: age_field must begin off-screen",
                device.findObject(By.res(FormFooterProbeActivity.TARGET_ID)) == null)
            ActionExecutor(device).execute(TestStep(action = "scrollUntilVisible",
                id = FormFooterProbeActivity.TARGET_ID, container = FormFooterProbeActivity.SCROLL_ID))
            assertNotNull("start=$start: age_field visible after scrollUntilVisible",
                device.findObject(By.res(FormFooterProbeActivity.TARGET_ID)))
        }
    }

    /**
     * The red side of the ticket's two stop positions: scrollUntilVisible
     * stops on arrival with age_field visible and error_label behind the
     * footer — in the tree, `isVisibleToUser=false`. The following
     * `visible error_label` must say it is off-screen and name the container
     * it resolved. Measured 2026-10-08 before 1.15.12: the message said
     * PRESENT_AT_FAILURE and nothing else (the probe never resolved a
     * container). Mutation: render without the container and presence turns
     * this red.
     */
    /**
     * Stages the arm on this device's geometry, from the driver's own rules:
     * the row between age_field and error_label is made taller than
     * scrollUntilVisible's clearance (ViewportMargin.clearanceFor of the
     * scroll surface) by half a row, then the offset is the one that puts
     * error_label's top exactly on the viewport's bottom edge. So error_label
     * is just hidden and age_field sits more than the clearance above the
     * edge — the step stops on arrival without its corrective drag.
     *
     * Not fixed: 860 was measured where rows (64dp) were taller than the
     * clearance and the maximum offset was 1040. On the conf_ci AVD
     * (2560x1600 landscape, 320dpi; 2026-10-18) the clearance is 160px and a
     * row 128px, so no offset hid error_label while keeping age_field clear
     * of it: 860 left error_label's top 36px on screen (`visible` rightly
     * passed, 5 of 5 runs red), and the offset that hid it put age_field
     * inside the clearance, so the step dragged on and revealed it.
     */
    private fun stageHiddenErrorLabel(): Int {
        var y = -1
        instrumentation.runOnMainSync {
            val sv = FormFooterProbeActivity.scrollView!!
            val surface = Rect().also { sv.getGlobalVisibleRect(it) }
            val clearance = ViewportMargin.clearanceFor(surface.height(), surface.width())
            val column = sv.getChildAt(0) as android.view.ViewGroup
            val gap = column.getChildAt(FormFooterProbeActivity.ROWS_ABOVE + 1)
            gap.layoutParams = gap.layoutParams.apply { height = clearance + column.getChildAt(0).height / 2 }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val sv = FormFooterProbeActivity.scrollView!!
            val column = sv.getChildAt(0) as android.view.ViewGroup
            val viewport = sv.height - sv.paddingTop - sv.paddingBottom
            y = column.getChildAt(FormFooterProbeActivity.ROWS_ABOVE + 2).top - viewport
        }
        return y
    }

    @Test
    fun aHiddenFollowingItemIsNamedAsOffscreen() {
        launch()
        val start = stageHiddenErrorLabel()
        assertTrue("the probe cannot hide error_label behind the viewport here: offset $start of max ${maxScroll()}",
            start in 1..maxScroll())
        scrollTo(start)
        ActionExecutor(device).execute(TestStep(action = "scrollUntilVisible",
            id = FormFooterProbeActivity.TARGET_ID, container = FormFooterProbeActivity.SCROLL_ID))
        val failure = runCatching {
            AssertionExecutor(device, 3000).execute(
                TestStep(assert = "visible", id = FormFooterProbeActivity.BELOW_ID, timeout = 1500))
        }.exceptionOrNull()
        assertNotNull("error_label must be hidden at scrollY=${scrollY()}", failure)
        val text = failure!!.message.orEmpty()
        text.lines().forEach { println("[FormFooterArm] $it") }
        assertTrue(text, text.contains("isVisibleToUser=false"))
        assertTrue(text, text.contains("most likely off-screen"))
        assertTrue(text, text.contains(
            "scroll container: '${FormFooterProbeActivity.SCROLL_ID}' (from the target's scrollable ancestor)"))
    }

    @Test
    fun whereTheFormStopsByStartingOffset() {
        launch()
        val max = maxScroll()
        val scroll = device.findObject(By.res(FormFooterProbeActivity.SCROLL_ID)).visibleBounds
        val footer = device.findObject(By.res(FormFooterProbeActivity.FOOTER_ID)).visibleBounds
        println("[FormFooter] display=${device.displayWidth}x${device.displayHeight} scroll=${scroll.toShortString()} " +
            "footer=${footer.toShortString()} maxScroll=$max")
        val executor = ActionExecutor(device)
        val assertions = AssertionExecutor(device, 3000)
        for (start in (0..max step (max / 12).coerceAtLeast(1))) {
            launch()
            scrollTo(start)
            val before = device.findObject(By.res(FormFooterProbeActivity.TARGET_ID))?.visibleBounds
            val scrolled = runCatching {
                executor.execute(TestStep(action = "scrollUntilVisible", id = FormFooterProbeActivity.TARGET_ID,
                    container = FormFooterProbeActivity.SCROLL_ID))
            }
            if (scrolled.isFailure) {
                println("[FormFooter] start=$start before=${before?.toShortString()} scrollUntilVisible THREW: " +
                    scrolled.exceptionOrNull()?.message?.lines()?.first())
                continue
            }
            val target = device.findObject(By.res(FormFooterProbeActivity.TARGET_ID))?.visibleBounds
            val y = scrollY()
            val visible = runCatching {
                assertions.execute(TestStep(assert = "visible", id = FormFooterProbeActivity.BELOW_ID, timeout = 1500))
            }
            val verdict = visible.fold({ "passed" }, { e ->
                val msg = e.message.orEmpty()
                msg.lines().forEach { println("[FormFooterMsg] start=$start $it") }
                "FAILED " + (msg.lines().firstOrNull { it.contains("=>") }?.trim() ?: msg.lines().first())
            })
            println("[FormFooter] start=$start before=${before?.toShortString()} " +
                "after=${target?.toShortString()} scrollY=$y error_label: ${describe(FormFooterProbeActivity.BELOW_ID)} " +
                "| visible error_label: $verdict")
        }
    }
}

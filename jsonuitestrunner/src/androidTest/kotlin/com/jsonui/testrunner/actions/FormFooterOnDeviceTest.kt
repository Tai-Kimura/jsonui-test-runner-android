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
    @Test
    fun scrollUntilVisibleReachesATargetBelowTheViewport() {
        for (start in listOf(0, maxScrollAfterLaunch() / 2)) {
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

    private fun maxScrollAfterLaunch(): Int {
        launch()
        return maxScroll()
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

package com.jsonui.testrunner.actions

import android.content.Intent
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jsonui.testrunner.models.TestStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A target the edge-clearance drag carried out through the TOP is found
 * again (ticket test-android-reapproach-after-loss-always-searches-up): the
 * re-approach searches the way it left, not always "up". On
 * OvershootProbeActivity the target is placed flush inside the clearance and
 * the ScrollView is armed to carry the first upward drag on by enough that
 * the target is still above the viewport after the rollback. The step must
 * end with the target on screen and one tap delivered.
 */
@RunWith(AndroidJUnit4::class)
class ReapproachAfterOvershootOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test
    fun aTargetTheDragCarriedOutThroughTheTopIsFoundAgain() {
        val ctx = instrumentation.context
        OvershootProbeActivity.scrollView = null
        ctx.startActivity(Intent(ctx, OvershootProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.hasObject(By.res(OvershootProbeActivity.SCROLL_ID)), 10_000)
        instrumentation.waitForIdleSync()
        // The target's bottom half a clearance above the viewport's bottom.
        val surface = Rect()
        var clearance = 0
        instrumentation.runOnMainSync {
            val sv = OvershootProbeActivity.scrollView!!
            sv.getGlobalVisibleRect(surface)
            clearance = ViewportMargin.clearanceFor(surface.height(), surface.width())
            val t = OvershootProbeActivity.target!!
            sv.scrollTo(0, t.bottom - sv.height + clearance / 2)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        val before = device.findObject(By.res(OvershootProbeActivity.TARGET_ID)).visibleBounds
        // Carry the drag on until, after the rollback undoes the drag, the
        // target's bottom is a clearance above the viewport: overshoot =
        // (bottom − surface top) + clearance.
        OvershootProbeActivity.overshootPx = before.bottom - surface.top + clearance
        ActionExecutor(device).execute(TestStep(action = "scrollUntilVisible",
            id = OvershootProbeActivity.TARGET_ID, container = OvershootProbeActivity.SCROLL_ID, timeout = 10000))
        val after = device.findObject(By.res(OvershootProbeActivity.TARGET_ID))?.visibleBounds
        val tapped = runCatching {
            ActionExecutor(device).execute(TestStep(action = "tap", id = OvershootProbeActivity.TARGET_ID, timeout = 3000))
        }.isSuccess
        Thread.sleep(300)
        println("[ReapproachOvershoot] surface=${surface.toShortString()} clearance=$clearance " +
            "before=${before.toShortString()} overshoots=${OvershootProbeActivity.overshoots} " +
            "after=${after?.toShortString()} tap=${if (tapped) "ok" else "failed"} taps=${OvershootProbeActivity.taps}")
        assertEquals("the probe did not overshoot: it measured nothing", 1, OvershootProbeActivity.overshoots)
        assertTrue("the target is not on screen after the step (it left through the top)", after != null)
        assertEquals("taps delivered to the target", 1, OvershootProbeActivity.taps)
    }
}

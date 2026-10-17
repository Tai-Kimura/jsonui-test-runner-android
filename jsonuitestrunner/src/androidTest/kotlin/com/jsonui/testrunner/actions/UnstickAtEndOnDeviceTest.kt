package com.jsonui.testrunner.actions

import android.content.Intent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jsonui.testrunner.models.TestStep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * scrollUntilVisible leaves a target at the end of its container where the
 * next step can tap it, when the edge-clearance drag cannot scroll and the
 * content settles back a few px after the finger lifts
 * (SlipAtEndProbeActivity). Reported 2026-10-18 under driver 1.17.2: the
 * rule read the 4px slip as "worse", reversed its whole 293px drag, pushed
 * the target off the bottom, and returned success; the tap found 0 nodes.
 *
 * The slip is run at 4px (the consumer's), one under the touch slop (kept:
 * a drag cannot undo less than a slop) and one over (rolled back by the
 * slip, upward). Each must end with the target on screen and one tap
 * delivered to it.
 */
@RunWith(AndroidJUnit4::class)
class UnstickAtEndOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    private fun launchAtEnd(slip: Int) {
        val ctx = instrumentation.context
        SlipAtEndProbeActivity.scrollView = null
        ctx.startActivity(
            Intent(ctx, SlipAtEndProbeActivity::class.java)
                .putExtra(SlipAtEndProbeActivity.EXTRA_SLIP, slip)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        device.wait(Until.hasObject(By.res(SlipAtEndProbeActivity.SCROLL_ID)), 10_000)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val sv = SlipAtEndProbeActivity.scrollView!!
            sv.scrollTo(0, sv.getChildAt(0).height)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
    }

    @Test
    fun aTargetAtTheEndStaysTappableWhenTheContentSlipsBackAfterTheDrag() {
        val slop = ViewConfiguration.get(instrumentation.context).scaledTouchSlop
        val wrong = mutableListOf<String>()
        for (slip in listOf(4, slop - 1, slop + 1)) {
            launchAtEnd(slip)
            val before = device.findObject(By.res(SlipAtEndProbeActivity.TARGET_ID))?.visibleBounds
            ActionExecutor(device).execute(TestStep(action = "scrollUntilVisible",
                id = SlipAtEndProbeActivity.TARGET_ID, container = SlipAtEndProbeActivity.SCROLL_ID, timeout = 10000))
            val after = device.findObject(By.res(SlipAtEndProbeActivity.TARGET_ID))?.visibleBounds
            val tapped = runCatching {
                ActionExecutor(device).execute(TestStep(action = "tap", id = SlipAtEndProbeActivity.TARGET_ID, timeout = 3000))
            }.isSuccess
            Thread.sleep(300)
            println("[UnstickAtEnd] slip=$slip slop=$slop before=${before?.toShortString()} " +
                "after=${after?.toShortString()} slips=${SlipAtEndProbeActivity.slips} " +
                "tap=${if (tapped) "ok" else "failed"} taps=${SlipAtEndProbeActivity.taps}")
            if (after == null || SlipAtEndProbeActivity.taps != 1) {
                wrong += "slip=$slip: after=${after?.toShortString()}, taps=${SlipAtEndProbeActivity.taps}"
            }
        }
        assertEquals("slips after which the target was not on screen or not tapped once", emptyList<String>(), wrong)
    }
}

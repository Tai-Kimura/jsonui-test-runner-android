package com.jsonui.testrunner.actions

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.models.TestStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `swipe` action on a full-width element does not start in the system
 * back-gesture edge zone (1.15.7: a full-width horizontal pager on a phone
 * swiped left from 8px inside the screen edge, and the system took it as
 * Back — the screen was popped and every later step failed). Through the
 * public entry, ActionExecutor.execute, on SwipeEdgeProbeActivity's
 * full-screen surface: after each swipe the activity is still resumed and not
 * finished by Back, the surface saw its stream end in an UP (not a CANCEL),
 * the DOWN landed outside the edge zone, and the finger travelled.
 *
 * Meaningful only under gesture navigation (navigation_mode 2), and only
 * where the surface is taller than wide, so the unclamped start lies in the
 * edge zone: the test turns the device to portrait for each swipe (and gives
 * the rotation back), and says so rather than passing for nothing when the
 * start would not reach the zone. Measured 2026-10-03 on the conf_ci AVD
 * (2560x1600, 320dpi, API 35, gesture navigation) in portrait: 1.15.7 put
 * the DOWN at x=8 / 1592 and the system took both as Back (stream
 * cancelled, activity finished); with the clamp the DOWN is at 96 / 1504
 * and the surface sees the whole 1496px swipe. In landscape the surface is
 * wider than tall and the start is far from the edge on both: the guard
 * fails both, by name.
 */
@RunWith(AndroidJUnit4::class)
class SwipeEdgeOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    private fun gestureNavigation(): Boolean =
        device.executeShellCommand("settings get secure navigation_mode").trim() == "2"

    private fun launch() {
        SwipeEdgeProbeActivity.reset()
        val ctx = instrumentation.context
        ctx.startActivity(
            Intent(ctx, SwipeEdgeProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (!SwipeEdgeProbeActivity.resumed && System.currentTimeMillis() < deadline) Thread.sleep(100)
        device.waitForIdle(2000)
    }

    private fun swipe(direction: String) {
        device.setOrientationPortrait()
        try {
            swipeInPortrait(direction)
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    private fun swipeInPortrait(direction: String) {
        launch()
        // The 1.15.7 line, before the edge clamp: a sample that does not put
        // it inside the edge zone measures nothing (a landscape tablet's
        // full-screen surface is wider than tall, so min(w, h) / 2 is the
        // height's and the start is far from the edge). Said, not passed.
        val bounds = device.findObject(androidx.test.uiautomator.By.res(SwipeEdgeProbeActivity.PROBE_ID)).visibleBounds
        val d = minOf(bounds.width(), bounds.height()) / 2 - 8
        val rawStart = if (direction == "left") bounds.centerX() + d else bounds.centerX() - d
        val edgeFloor = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        assertTrue(
            "the probe does not discriminate: the unclamped start x=$rawStart is outside the ${edgeFloor}px edge zone " +
                "(surface ${bounds.toShortString()}; the surface must be taller than wide)",
            rawStart < edgeFloor || rawStart > device.displayWidth - edgeFloor
        )
        ActionExecutor(device).execute(TestStep(action = "swipe", id = SwipeEdgeProbeActivity.PROBE_ID, direction = direction))
        device.waitForIdle(2000)
        Thread.sleep(500)
        val width = device.displayWidth
        val edge = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        println("[SwipeEdge] $direction: width=$width edgeFloor=$edge downX=${SwipeEdgeProbeActivity.downX} " +
            "upDx=${SwipeEdgeProbeActivity.upDx} cancelled=${SwipeEdgeProbeActivity.cancelled} " +
            "back=${SwipeEdgeProbeActivity.finishedByBack} resumed=${SwipeEdgeProbeActivity.resumed}")
        assertFalse("$direction: the system took the swipe as Back", SwipeEdgeProbeActivity.finishedByBack)
        assertTrue("$direction: the probe screen is no longer on top", SwipeEdgeProbeActivity.resumed)
        assertFalse("$direction: the stream was cancelled", SwipeEdgeProbeActivity.cancelled)
        val down = SwipeEdgeProbeActivity.downX
        assertTrue("$direction: DOWN x=$down is inside the ${edge}px edge zone", down >= edge && down <= width - edge)
        val dx = SwipeEdgeProbeActivity.upDx
        assertTrue("$direction: travelled dx=$dx", if (direction == "left") dx < -edge else dx > edge)
    }

    @Test
    fun aFullWidthSwipeLeftIsNotTakenAsBack() {
        assertTrue("navigation_mode is not gesture (2): nothing to measure here", gestureNavigation())
        swipe("left")
    }

    @Test
    fun aFullWidthSwipeRightIsNotTakenAsBack() {
        assertTrue("navigation_mode is not gesture (2): nothing to measure here", gestureNavigation())
        swipe("right")
    }
}

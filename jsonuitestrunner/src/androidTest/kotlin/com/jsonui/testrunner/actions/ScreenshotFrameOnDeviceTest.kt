package com.jsonui.testrunner.actions

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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
import java.io.File
import kotlin.math.abs

/**
 * The `screenshot` action saves the frame the display shows once it has
 * caught up with the tree the steps waited on (StableScreenshot; ticket
 * test-android-screenshot-after-tab-switch-saves-the-previous-frame: a
 * consumer flow saved a PNG byte-identical to the screen it had left, its
 * waits all passed).
 *
 * On ScreenshotFrameProbeActivity: tap, wait until the accessibility text
 * names the new colour — the tree is current — then screenshot through the
 * public entry, ActionExecutor.execute, and read the colour at the centre of
 * the PNG. [ROUNDS] taps; every PNG must show the colour its text named.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotFrameOnDeviceTest {
    companion object { const val ROUNDS = 10; const val CIRCLES = 20000 }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    private fun launch() {
        val ctx = instrumentation.context
        ctx.startActivity(
            Intent(ctx, ScreenshotFrameProbeActivity::class.java)
                .putExtra(ScreenshotFrameProbeActivity.EXTRA_CIRCLES,
                    InstrumentationRegistry.getArguments().getString("circles")?.toIntOrNull() ?: CIRCLES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (!ScreenshotFrameProbeActivity.resumed && System.currentTimeMillis() < deadline) Thread.sleep(100)
        device.waitForIdle(2000)
        Thread.sleep(500)
    }

    /** The two colours differ by which of red and blue dominates (the probe's veil lightens both). */
    private fun near(a: Int, b: Int) = (Color.red(a) > Color.blue(a)) == (Color.red(b) > Color.blue(b)) &&
        abs(Color.red(a) - Color.blue(a)) > 40

    @Test
    fun aScreenshotAfterTheTreeChangedShowsTheNewPixels() {
        launch()
        val executor = ActionExecutor(device)
        val probe = device.findObject(By.res(ScreenshotFrameProbeActivity.PROBE_ID))
        val center = probe.visibleCenter
        val dir = instrumentation.targetContext.filesDir
        val stale = mutableListOf<String>()
        for (round in 1..ROUNDS) {
            val want = 1 - ScreenshotFrameProbeActivity.index
            device.click(center.x, center.y)
            assertTrue("round $round: the text never named color_$want",
                device.wait(Until.hasObject(By.res(ScreenshotFrameProbeActivity.PROBE_ID).desc("color_$want")), 5000))
            val name = "screenshot_frame_$round"
            executor.execute(TestStep(action = "screenshot", name = name))
            val png = BitmapFactory.decodeFile(File(dir, "$name.png").absolutePath)
            val got = png.getPixel(center.x, center.y)
            val expected = ScreenshotFrameProbeActivity.COLORS[want]
            println("[ScreenshotFrame] round $round: want color_$want, pixel #${Integer.toHexString(got)} ${if (near(got, expected)) "ok" else "STALE"}")
            if (!near(got, expected)) stale += "$round"
        }
        assertEquals("rounds whose PNG shows the colour before the tap", emptyList<String>(), stale)
    }
}

package com.jsonui.testrunner.actions

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.models.TestStep
import com.jsonui.testrunner.runner.Deadline
import com.jsonui.testrunner.runner.FindTimeoutReport
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A timed-out `waitFor` names how long it waited on each clock and what the
 * same find saw when it gave up (ticket android-driver-misses-a-present-
 * resource-id-for-25s: the consumer's failure could say neither). Through
 * the public entry, ActionExecutor.execute, on the device.
 *
 * The wall-clock jump itself is not staged here: setting a shared emulator's
 * clock is what broke the consumer's run. DeadlineTest drives both clocks by
 * hand instead.
 */
@RunWith(AndroidJUnit4::class)
class FindTimeoutOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test
    fun aForcedMissNamesWhatItWaitedAndWhatItSaw() {
        val id = "find_timeout_probe_never_present"
        val started = SystemClock.uptimeMillis()
        val error = assertThrows(AssertionError::class.java) {
            ActionExecutor(device).execute(TestStep(action = "waitFor", id = id, timeout = 1500))
        }
        val took = SystemClock.uptimeMillis() - started
        val message = error.message.orEmpty()
        println("[FindTimeoutOnDeviceTest] took ${took}ms:\n$message")
        assertTrue(message, message.contains("of a 1500ms budget (wall clock "))
        assertTrue(message, message.contains("the same find right after giving up: 0 node(s) with resource-id '$id'"))
        assertTrue(message, message.contains("projection probe for '$id': STILL_MISSING"))
        assertTrue("the wait spent its budget before giving up: ${took}ms", took >= 1500)
    }

    /** The report's census on an id that IS there: the count, bounds and enabled. */
    @Test
    fun aPresentIdIsListedWithItsBounds() {
        SwipeEdgeProbeActivity.reset()
        val ctx = instrumentation.context
        ctx.startActivity(
            Intent(ctx, SwipeEdgeProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val id = SwipeEdgeProbeActivity.PROBE_ID
        ActionExecutor(device).execute(TestStep(action = "waitFor", id = id, timeout = 10_000))
        val report = FindTimeoutReport.render(device, id, Deadline.of(0))
        println("[FindTimeoutOnDeviceTest] present:\n$report")
        assertTrue(report, report.contains("1 node(s) with resource-id '$id' — "))
        assertTrue(report, Regex("""bounds=\[\d+,\d+]\[\d+,\d+] enabled=true""").containsMatchIn(report))
    }
}

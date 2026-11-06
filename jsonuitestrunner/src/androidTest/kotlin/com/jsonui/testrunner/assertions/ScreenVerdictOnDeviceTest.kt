package com.jsonui.testrunner.assertions

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jsonui.testrunner.models.TestStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `assert: "screen"` says one thing from one read. Reported from a consumer's
 * run at load 70: "previous-screen-only: 'item_detail' is not displayed;
 * displayed screens are [item_detail]" — the verdict came from the last look,
 * the sentence from a second read after it, and the screen came between them.
 */
@RunWith(AndroidJUnit4::class)
class ScreenVerdictOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val probe get() = ScreenMarkerProbeActivity.current!!

    @Before
    fun openProbe() {
        ScreenMarkerProbeActivity.current = null
        val ctx = instrumentation.context
        ctx.startActivity(
            Intent(ctx, ScreenMarkerProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val until = SystemClock.uptimeMillis() + 20_000
        while (ScreenMarkerProbeActivity.current == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        probe.show("before")
        assertTrue(device.wait(Until.hasObject(By.res(ScreenMarker.tagFor("before"))), 20_000))
    }

    private fun screenStep(name: String, timeout: Int) = TestStep(assert = "screen", name = name, timeout = timeout)

    /** The race, every time: the screen comes after the last look missed it. */
    @Test
    fun aScreenThatComesAfterTheLastLookIsDisplayedNotAContradiction() {
        val warnings = mutableListOf<String>()
        val executor = AssertionExecutor(device)
        executor.warningHandler = { warnings += it }
        executor.beforeLastRead = {
            probe.show("arriving")
            assertTrue(device.wait(Until.hasObject(By.res(ScreenMarker.tagFor("arriving"))), 20_000))
        }
        executor.execute(screenStep("arriving", timeout = 0))
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("'arriving' came on the last read"))
    }

    /** What the arm above must not cost: a screen that is not there stays red, and says what is. */
    @Test
    fun aScreenThatIsNotThereStaysRedAndNamesWhatIs() {
        val error = assertThrows(AssertionError::class.java) {
            AssertionExecutor(device).execute(screenStep("somewhere_else", timeout = 500))
        }
        val message = error.message.orEmpty()
        assertTrue(message, message.startsWith("previous-screen-only: 'somewhere_else' is not displayed; displayed screens are [before]"))
    }

    /** The same, when another screen comes in that last moment: it is named, the wanted one is still not displayed. */
    @Test
    fun anotherScreenComingAtTheLastReadIsNotTakenForTheWantedOne() {
        val executor = AssertionExecutor(device)
        executor.beforeLastRead = {
            probe.show("other")
            assertTrue(device.wait(Until.hasObject(By.res(ScreenMarker.tagFor("other"))), 20_000))
        }
        val error = assertThrows(AssertionError::class.java) { executor.execute(screenStep("wanted", timeout = 0)) }
        val message = error.message.orEmpty()
        assertTrue(message, message.startsWith("previous-screen-only: 'wanted' is not displayed; displayed screens are [other]"))
    }

    /**
     * With nothing staged: the screen comes about when the budget runs out, over
     * and over. Whatever each run's verdict, it never names the wanted screen as
     * displayed while saying it is not.
     */
    @Test
    fun aScreenComingAsTheBudgetRunsOutNeverReadsAsAContradiction() {
        var passed = 0
        var red = 0
        val contradictions = mutableListOf<String>()
        repeat(TRIALS) { i ->
            val name = "arriving_$i"
            val budget = 200
            val arrival = budget + (i % 12) * 25L   // a look takes its time: the last one ends well past the budget
            probe.show("before")
            assertTrue(device.wait(Until.hasObject(By.res(ScreenMarker.tagFor("before"))), 20_000))
            val shower = Thread {
                SystemClock.sleep(arrival)
                probe.show(name)
            }
            shower.start()
            try {
                AssertionExecutor(device).execute(screenStep(name, timeout = budget))
                passed++
            } catch (e: AssertionError) {
                red++
                val first = e.message.orEmpty().lineSequence().first()
                if (first.startsWith("previous-screen-only") && first.substringAfter("displayed screens are").contains(name)) {
                    contradictions += first
                }
            }
            shower.join()
        }
        println("[ScreenVerdictOnDeviceTest] sweep: $passed passed, $red red, ${contradictions.size} contradictions in $TRIALS")
        assertFalse(contradictions.joinToString("\n"), contradictions.isNotEmpty())
    }

    private companion object {
        const val TRIALS = 240
    }
}

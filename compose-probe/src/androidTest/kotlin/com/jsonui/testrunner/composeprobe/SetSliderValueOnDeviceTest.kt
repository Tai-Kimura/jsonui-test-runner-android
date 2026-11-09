package com.jsonui.testrunner.composeprobe

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jsonui.testrunner.actions.ActionExecutor
import com.jsonui.testrunner.actions.SliderPlan
import com.jsonui.testrunner.assertions.AssertionExecutor
import com.jsonui.testrunner.models.TestStep
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * setSliderValue, the value assertion and readText on Material3 Sliders
 * (SliderProbeActivity), through the public entries — one test per row of
 * the family table measured 2026-11-09 (docs/bugs 2026-11-09
 * test-runner-no-slider-action): ACTION_SET_PROGRESS lands exactly and
 * reports one change and one finish; out of range it clamps in silence and
 * with steps it snaps, so the driver refuses those before acting; the node
 * read straight after the action is stale until refreshed.
 */
@RunWith(AndroidJUnit4::class)
class SetSliderValueOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val actions = ActionExecutor(device, defaultTimeout = 3000L)
    private val assertions = AssertionExecutor(device, defaultTimeout = 1500L)

    @Before
    fun launch() {
        SliderProbeActivity.reset()
        val ctx = instrumentation.targetContext
        ctx.startActivity(
            Intent(ctx, SliderProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (!SliderProbeActivity.resumed && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("probe did not come up", device.wait(Until.hasObject(By.res("s1to10")), 10_000))
    }

    private fun value(id: String) = SliderProbeActivity.values.getValue(id).floatValue.toDouble()
    private fun set(id: String, value: String, range: List<Double>? = null, step: Double? = null) =
        actions.execute(TestStep(action = "setSliderValue", id = id, value = value, range = range, step = step))

    private fun refused(id: String, value: String, range: List<Double>? = null, step: Double? = null): String {
        val before = value(id); val changes = SliderProbeActivity.changes.getValue(id)
        try {
            set(id, value, range, step)
        } catch (e: Throwable) {
            device.waitForIdle(500)
            assertEquals("$id moved although the step was refused", before, value(id), 0.0)
            assertEquals("$id saw a change although the step was refused", changes, SliderProbeActivity.changes.getValue(id))
            return e.message ?: ""
        }
        fail("setSliderValue $id $value passed; expected a refusal"); return ""
    }

    @Test
    fun inRangeValuesLandExactlyWithOneChangeAndOneFinish() {
        for ((id, v) in listOf("s1to10" to 9.0, "s1to10" to 9.4, "s1to10" to 1.0, "s1to10" to 10.0,
                "s0to1" to 0.123456, "sm5to5" to -2.5, "s0to1000" to 999.0, "s1to10steps" to 9.0)) {
            val changes = SliderProbeActivity.changes.getValue(id); val finishes = SliderProbeActivity.finishes.getValue(id)
            set(id, SliderPlan.fmt(v))
            device.waitForIdle(500)
            assertEquals("$id after setSliderValue $v", v, value(id), 1e-6)
            assertEquals("$id onValueChange count", changes + 1, SliderProbeActivity.changes.getValue(id))
            assertEquals("$id onValueChangeFinished count", finishes + 1, SliderProbeActivity.finishes.getValue(id))
        }
    }

    @Test
    fun theValueItAlreadyHasPasses() {
        set("s1to10", "5")
        assertEquals(5.0, value("s1to10"), 0.0)
    }

    @Test
    fun outOfRangeIsRefusedBeforeAnythingMovesNamingTheBound() {
        val below = refused("s1to10", "0")
        assertTrue(below, below.contains("below the slider's range [1, 10]") && below.contains("nearest value it can take is 1"))
        val above = refused("s1to10", "11")
        assertTrue(above, above.contains("above the slider's range [1, 10]") && above.contains("is 10"))
    }

    @Test
    fun offTheDeclaredStepIsRefusedBeforeAnythingMovesNamingTheNeighbours() {
        val msg = refused("s1to10steps", "9.4", step = 1.0)
        assertTrue(msg, msg.contains("not on the slider's steps") && msg.contains("9 and 10"))
    }

    @Test
    fun offTheSlidersOwnStepsFailsNamingWhereItLanded() {
        // No step declared: Android cannot read the steps, the slider snaps,
        // and the read-back names the value it is at.
        try {
            set("s1to10steps", "9.4")
            fail("an off-step value on a slider with steps passed")
        } catch (e: AssertionError) {
            assertTrue(e.message, e.message!!.contains("the slider is at 9, not 9.4"))
        }
    }

    @Test
    fun aDeclaredRangeThatIsNotTheSlidersFailsNamingTheSlidersOwn() {
        val msg = refused("s1to10", "5.5", range = listOf(0.0, 10.0))
        assertTrue(msg, msg.contains("declares range [0, 10] but the slider's own is [1, 10]"))
        set("s1to10", "6", range = listOf(1.0, 10.0))
        assertEquals(6.0, value("s1to10"), 0.0)
    }

    @Test
    fun theValueAssertionReadsTheSliderJustSet() {
        // The slider as it starts (5), before any setSliderValue.
        assertions.execute(TestStep(assert = "value", id = "s1to10", equals = JsonPrimitive(5)))
        set("s1to10", "9")
        assertions.execute(TestStep(assert = "value", id = "s1to10", equals = JsonPrimitive(9)))
        try {
            assertions.execute(TestStep(assert = "value", id = "s1to10", equals = JsonPrimitive(8)))
            fail("value 8 passed on a slider at 9")
        } catch (e: AssertionError) {
            assertTrue(e.message, e.message!!.contains("Expected value 8 (tolerance 0.000001) but the slider 's1to10' is at 9"))
        }
        assertions.execute(TestStep(assert = "value", id = "s1to10", equals = JsonPrimitive(8.6), tolerance = 0.5))
    }

    @Test
    fun readTextReadsASlidersValue() {
        val store = mutableMapOf<String, String>()
        actions.variableStore = store
        // As it starts (5): before 1.22.0 a slider read "" (no text, no description).
        actions.execute(TestStep(action = "readText", id = "s1to10", variable = "v"))
        assertEquals("5", store["v"])
        set("sm5to5", "-2.5")
        actions.execute(TestStep(action = "readText", id = "sm5to5", variable = "v"))
        assertEquals("-2.5", store["v"])
    }
}

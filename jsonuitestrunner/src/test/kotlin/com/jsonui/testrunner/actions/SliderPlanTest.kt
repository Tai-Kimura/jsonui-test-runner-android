package com.jsonui.testrunner.actions

import com.jsonui.testrunner.models.TestStep
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What setSliderValue refuses before it acts, and how it names numbers (SliderPlan). */
class SliderPlanTest {
    private fun refusal(v: Double, min: Double = 1.0, max: Double = 10.0, range: List<Double>? = null, step: Double? = null) =
        SliderPlan.refusal("r", v, min, max, range, step)

    @Test fun inRangeOnTheGridIsAccepted() {
        assertNull(refusal(9.0)); assertNull(refusal(1.0)); assertNull(refusal(10.0)); assertNull(refusal(9.4))
        assertNull(refusal(9.0, step = 1.0)); assertNull(refusal(0.3, 0.0, 1.0, step = 0.1))
        assertNull(refusal(-2.5, -5.0, 5.0, step = 0.5))
    }

    @Test fun belowAndAboveNameTheBound() {
        val below = refusal(0.0)!!
        assertTrue(below, below.contains("0 is below the slider's range [1, 10]") && below.endsWith("is 1"))
        val above = refusal(11.0)!!
        assertTrue(above, above.contains("11 is above the slider's range [1, 10]") && above.endsWith("is 10"))
    }

    @Test fun offTheGridNamesBothNeighbours() {
        assertTrue(refusal(9.4, step = 1.0)!!.contains("the nearest values are 9 and 10"))
        assertTrue(refusal(0.25, 0.0, 1.0, step = 0.1)!!.contains("the nearest values are 0.2 and 0.3"))
        // The grid starts at the minimum, not at 0.
        assertTrue(refusal(2.0, 1.0, 10.0, step = 2.0)!!.contains("the nearest values are 1 and 3"))
        // The upper neighbour never passes the maximum.
        assertTrue(refusal(9.5, 1.0, 10.0, step = 2.0)!!.contains("the nearest values are 9 and 10"))
    }

    @Test fun aRangeThatIsNotTheSlidersIsRefusedFirst() {
        val msg = refusal(50.0, range = listOf(0.0, 100.0))!!
        assertTrue(msg, msg.contains("declares range [0, 100] but the slider's own is [1, 10]"))
        assertNull(refusal(5.0, range = listOf(1.0, 10.0)))
    }

    @Test fun floatsOfTheSameNumberAreTheSameValue() {
        // ACTION_ARGUMENT_PROGRESS_VALUE and RangeInfo are floats.
        assertNull(SliderPlan.landedRefusal("r", 0.73, 0.73f.toDouble(), 0.0, 1.0))
        assertNull(SliderPlan.landedRefusal("r", 0.123456, 0.123456f.toDouble(), 0.0, 1.0))
        assertTrue(SliderPlan.landedRefusal("r", 9.4, 9.0, 1.0, 10.0)!!.contains("the slider is at 9, not 9.4"))
    }

    @Test fun numbersAreWrittenAsATestAuthorWritesThem() {
        assertEquals("9", SliderPlan.fmt(9.0)); assertEquals("-2.5", SliderPlan.fmt(-2.5))
        assertEquals("0.123456", SliderPlan.fmt(0.123456)); assertEquals("1000", SliderPlan.fmt(1000.0))
        assertEquals("0.3", SliderPlan.fmt(0.1 * 3))
    }

    @Test fun aNumericValueDecodesAsTheStepsValue() {
        // TestLoader's Json: a number written for `value` reaches the step as its text.
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val step = json.decodeFromString(TestStep.serializer(),
            """{"action":"setSliderValue","id":"r","value":9.5,"range":[1,10],"step":0.5}""")
        assertEquals("9.5", step.value); assertEquals(listOf(1.0, 10.0), step.range); assertEquals(0.5, step.step!!, 0.0)
        val assert = json.decodeFromString(TestStep.serializer(), """{"assert":"value","id":"r","equals":9,"tolerance":0.25}""")
        assertEquals(0.25, assert.tolerance!!, 0.0)
    }
}

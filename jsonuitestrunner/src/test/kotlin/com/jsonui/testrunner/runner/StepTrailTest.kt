package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: test-android-flow-failure-does-not-name-the-step.
 *
 * A long flow failed with `androidx.test.uiautomator.StaleObjectException`
 * and nothing else. The reporter had to open `failure.png` and grep
 * `hierarchy.xml` to work out which step it was, and that only worked because
 * the screen happened to change around the failure -- inside a run of steps
 * on one screen it would not have.
 */
class StepTrailTest {

    private class Silent : RuntimeException(null as String?)

    @Test
    fun anEmptyTrailReturnsWhatTheOldExpressionReturned() {
        // The population this must not disturb: failures before any step runs
        // (mock setup, launch config) had no step to name and still do not.
        assertEquals("boom", StepTrail().describe(RuntimeException("boom")))
    }

    @Test
    fun aMessagelessExceptionStillDegradesToItsClassName() {
        val text = StepTrail().describe(Silent())
        assertTrue(text, text.contains("Silent"))
    }

    @Test
    fun aFailureInsideAStepNamesTheStep() {
        val trail = StepTrail()
        trail.push(StepTrail.frame("step", 31, 48, "pick an option", "action=tap, id=choice_label"))
        val text = trail.describe(Silent())
        assertTrue(text, text.contains("step 32/48"))
        assertTrue(text, text.contains("pick an option"))
        assertTrue(text, text.contains("id=choice_label"))
        assertTrue(text, text.contains("Silent"))
    }

    @Test
    fun twoStepsThatThrowTheSAMEMessageStillReadDifferently() {
        // ⚠️ The arm this file exists for. Printing `e.message` alone looks
        // like it names the step whenever each step throws a different text.
        // It stops looking like that only when the two texts are identical --
        // which is exactly the reported case, where every step's exception
        // was a bare `StaleObjectException`.
        val same = Silent()
        val a = StepTrail().apply { push(StepTrail.frame("step", 2, 9, null, "action=tap, id=submit")) }
        val b = StepTrail().apply { push(StepTrail.frame("step", 6, 9, null, "action=tap, id=submit")) }
        assertNotEquals(a.describe(same), b.describe(same))
        assertTrue(a.describe(same).contains("step 3/9"))
        assertTrue(b.describe(same).contains("step 7/9"))
    }

    @Test
    fun nestedFramesNameBothTheOuterAndTheInnerPosition() {
        val trail = StepTrail()
        trail.push(StepTrail.frame("flow step", 4, 12, null, "block=login"))
        trail.push(StepTrail.frame("in block", 1, 3, null, "action=tap, id=submit"))
        val text = trail.describe(Silent())
        assertTrue(text, text.contains("flow step 5/12"))
        assertTrue(text, text.contains("in block 2/3"))
        assertTrue(text, text.indexOf("flow step 5/12") < text.indexOf("in block 2/3"))
    }

    @Test
    fun aStepThatThrowsDoesNotLeaveItsFrameBehind() {
        // Without the `finally`, the next step inherits the dead step's frame
        // and every later failure is reported at the wrong index -- a wrong
        // answer, which is worse than the missing one this change fixes.
        val trail = StepTrail()
        runCatching {
            trail.inFrame(StepTrail.frame("step", 0, 2, null, "action=tap, id=a")) {
                throw Silent()
            }
        }
        assertEquals(0, trail.depth())
        assertEquals("boom", trail.describe(RuntimeException("boom")))
    }

    @Test
    fun aLabelIsOptionalAndTheIndexStillCarriesItsTotal() {
        val unlabelled = StepTrail.frame("step", 0, 5, null, "action=tap, id=a")
        assertEquals("step 1/5 (action=tap, id=a)", unlabelled)
        val blank = StepTrail.frame("step", 0, 5, "   ", "action=tap, id=a")
        assertEquals("step 1/5 (action=tap, id=a)", blank)
    }
}

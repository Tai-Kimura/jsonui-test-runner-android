package com.jsonui.testrunner.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The floor must survive the runner, because the runner does not survive a test.
 *
 * 🚨 1.13.0-alpha01 held this as a field on `JsonUITestRunner`, and
 * `JsonUITest.createRunner()` returns a NEW runner per test. So the capture
 * and the use landed in one call and the floor was always a no-op — and on
 * the next file it captured what the previous file's `setOrientation` left,
 * adopting the state it exists to undo. Found by the reporting lane before
 * release, by reading the runner's LIFETIME rather than its logic.
 */
class RunOrientationFloorTest {

    @Before fun reset() = RunOrientationFloor.resetForTest()

    @Test
    fun `it keeps the first observation across later captures`() {
        // 🚨 The arm alpha01 fails. Each "capture" stands for a separate
        // runner instance: file 1 starts landscape, a case rotates to
        // portrait, file 2 begins and observes portrait. The floor must still
        // say landscape.
        assertEquals("landscape", RunOrientationFloor.captureOnce("landscape"))
        assertEquals("landscape", RunOrientationFloor.captureOnce("portrait"))
        assertEquals("landscape", RunOrientationFloor.captureOnce("portrait"))
        assertEquals("landscape", RunOrientationFloor.current())
    }

    @Test
    fun `a failed first observation is not retried`() {
        // ⚠️ If the first reading fails, re-capturing later would take a
        // reading AFTER a rotation — the exact state the floor undoes. One
        // attempt, and "unknown" stands.
        assertNull(RunOrientationFloor.captureOnce(null))
        assertNull(RunOrientationFloor.captureOnce("portrait"))
        assertNull(RunOrientationFloor.current())
    }

    @Test
    fun `the control - nothing is remembered before the first capture`() {
        assertNull(RunOrientationFloor.current())
    }

    @Test
    fun `the floor is process-scoped, not per-runner`() {
        // The structural claim, asserted where it can be read: an object
        // declaration outlives every runner instance. If this moves back onto
        // the runner, the arms above still pass in isolation — which is how
        // alpha01 shipped — so the scope itself is pinned.
        val src = File(
            "src/main/kotlin/com/jsonui/testrunner/models/RunOrientationFloor.kt"
        ).readText()
        assertTrue("the floor is no longer an object declaration",
            src.contains("object RunOrientationFloor"))

        val runner = File(
            "src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt"
        ).readText()
        assertTrue("the runner no longer delegates to the process-scoped floor",
            runner.contains("RunOrientationFloor.captureOnce(observeOrientation())"))
        assertTrue("the runner reintroduced a per-instance floor",
            !runner.contains("private var runStartOrientation"))
    }

    @Test
    fun `the runner still consults the floor last`() {
        // Order matters: a file's own orientation beats the tier default,
        // which beats the floor. If the floor moved earlier it would override
        // declarations that are working today.
        val runner = File(
            "src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt"
        ).readText()
        val block = runner.substring(runner.indexOf("val wanted = declared"))
            .substringBefore("declaredOrientation")
        assertTrue("the floor is no longer the last resort",
            block.indexOf("RunDefaultsLoader.forTier") < block.indexOf("runStartOrientation"))
    }
}

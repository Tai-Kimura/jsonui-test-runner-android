package com.jsonui.testrunner.runner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestMetadata
import com.jsonui.testrunner.models.TestSource
import com.jsonui.testrunner.models.TestStep
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the case-retry loop: runs the real runner (UiDevice,
 * real waits) against whatever is on screen and checks the attempts
 * accounting end to end, through ResultsWriter.
 *
 * The deterministically-failing case asserts a resource id that no screen
 * has, so with caseRetries = 1 it must run exactly twice and record
 * attempts = 2 without flaky; the empty case settles first try with
 * attempts = 1. The flaky-pass transition (pass on attempt 2 -> flaky) is
 * pinned by the JVM CaseRetryAttemptsTest — there is no deterministic
 * organic flake to stage against the launcher.
 *
 * No CI lane runs androidTest here; execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     --tests com.jsonui.testrunner.runner.AttemptsOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class AttemptsOnDeviceTest {

    @Test
    fun attemptsAccountingSurvivesARealRun() {
        val runner = JsonUITestRunner(
            TestRunnerConfig(
                caseRetries = 1,
                defaultTimeout = 500L,
                screenshotOnFailure = false,
                verifyScreenTransitions = false
            )
        )

        val suite = runner.runScreenTest(
            ScreenTest(
                type = "screen",
                source = TestSource(layout = "attempts_probe"),
                metadata = TestMetadata(name = "AttemptsProbe"),
                cases = listOf(
                    TestCase(
                        name = "hopeless",
                        steps = listOf(
                            TestStep(assert = "visible", id = "jsonui_no_such_id", timeout = 300)
                        )
                    ),
                    TestCase(name = "settles first try", steps = emptyList())
                )
            )
        )

        val rows = ResultsWriter.toJson(
            listOf(suite),
            platform = "android",
            generatedAt = "probe"
        )["suites"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray

        val hopeless = rows[0].jsonObject
        assertEquals("failed", hopeless["status"]!!.jsonPrimitive.content)
        assertEquals(2, hopeless["attempts"]!!.jsonPrimitive.int)
        assertNull(hopeless["flaky"])

        val settled = rows[1].jsonObject
        assertEquals("passed", settled["status"]!!.jsonPrimitive.content)
        assertEquals(1, settled["attempts"]!!.jsonPrimitive.int)
        assertNull(settled["flaky"])
    }
}

package com.jsonui.testrunner.runner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jsonui.testrunner.models.LaunchConfig
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestMetadata
import com.jsonui.testrunner.models.TestSource
import com.jsonui.testrunner.models.TestStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The launch contract, measured end to end on a device: arguments must ride
 * the relaunch intent as JSONUI_TEST_ARGS and be readable from the launched
 * activity (the consumer side of the contract), the relaunch must happen
 * exactly once (the mocks/launch fold), and clearState must wipe persisted
 * state while the run SURVIVES — completion is itself an assertion, because
 * the `pm clear` this wipe replaced killed the instrumentation process
 * (measured 2026-09-02: "Instrumentation run failed due to Process crashed").
 *
 * No CI lane runs androidTest here; execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     --tests com.jsonui.testrunner.runner.LaunchDeliveryOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class LaunchDeliveryOnDeviceTest {

    private fun runner() = JsonUITestRunner(
        TestRunnerConfig(
            defaultTimeout = 500L,
            screenshotOnFailure = false,
            verifyScreenTransitions = false
        )
    )

    private fun screenTest(launch: LaunchConfig?) = ScreenTest(
        type = "screen",
        source = TestSource(layout = "launch_probe"),
        metadata = TestMetadata(name = "LaunchProbe"),
        launch = launch,
        cases = listOf(
            TestCase(
                name = "noop",
                steps = listOf(
                    TestStep(assert = "exists", id = "jsonui_probe_absent", timeout = 300)
                )
            )
        )
    )

    @Test
    fun argumentsRideTheRelaunchIntentAndTheAppCanReadThem() {
        LaunchProbeActivity.reset()
        runner().runScreenTest(
            screenTest(
                LaunchConfig(
                    arguments = mapOf(
                        "probe" to JsonPrimitive("hello"),
                        "n" to JsonPrimitive(7)
                    )
                )
            )
        )
        // Exactly one relaunch: the fold gives mocks and launch a single
        // shared restart, so a launch-only file must not start the app twice.
        assertEquals(
            "launch-only config must relaunch exactly once",
            1, LaunchProbeActivity.launches.get()
        )
        val json = LaunchProbeActivity.lastArgs
        assertNotNull(
            "JSONUI_TEST_ARGS extra missing from the launched activity's intent",
            json
        )
        val parsed = Json.parseToJsonElement(json!!).jsonObject
        assertEquals("hello", parsed["probe"]!!.jsonPrimitive.content)
        assertEquals(7, parsed["n"]!!.jsonPrimitive.int)
    }

    @Test
    fun clearStateWipesPersistedStateAndTheRunSurvives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seeded = File(context.filesDir, "launch_probe_seed.txt").apply { writeText("seed") }
        context.getSharedPreferences("launch_probe_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("k", "v").commit()
        val prefsFile = File(context.dataDir, "shared_prefs/launch_probe_prefs.xml")
        assertTrue("seed file was not written", seeded.exists())
        assertTrue("prefs file was not written", prefsFile.exists())

        val suite = runner().runScreenTest(screenTest(LaunchConfig(clearState = true)))

        // The assertions read the FILES, not getSharedPreferences: a cached
        // SharedPreferences instance is process memory, which the declared
        // boundary says survives.
        assertFalse("files/ was not wiped", seeded.exists())
        assertFalse("shared_prefs/ was not wiped", prefsFile.exists())
        // Completion is the negative measurement: with `pm clear` this line
        // was never reached — the process died before any result existed.
        assertEquals("run did not complete", 1, suite.results.size)
    }

    @Test
    fun noLaunchConfigMeansNoRelaunch() {
        LaunchProbeActivity.reset()
        runner().runScreenTest(screenTest(null))
        assertEquals(
            "a file with neither mocks nor launch must not restart the app",
            0, LaunchProbeActivity.launches.get()
        )
    }
}

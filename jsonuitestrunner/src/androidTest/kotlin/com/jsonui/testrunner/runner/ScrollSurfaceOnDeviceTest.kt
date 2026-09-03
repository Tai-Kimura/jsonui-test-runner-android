package com.jsonui.testrunner.runner

import android.app.Instrumentation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.models.LaunchConfig
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestMetadata
import com.jsonui.testrunner.models.TestSource
import com.jsonui.testrunner.models.TestStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device measurement of the scrollUntilVisible fallback surface (no
 * `container`): the swipes must run in the app WINDOW, resolved through
 * AppWindow, and the failure text must name that rect and its source.
 *
 * Why this exists (2026-09-03, consumer page): the old surface was the first
 * `By.pkg` match, which right after a bottom sheet closed was a 148x63px
 * header node at x=0; the swipes at x=74 were back gestures and popped two
 * screens. That tree state cannot be staged against the launch probe, so
 * this test measures the invariant the fix rests on — the resolved surface
 * IS the window (width >= half the display), the probe activity is still
 * up after a full both-ends search, and the failure names the rect — while
 * the JVM ScrollGestureTest pins the edge-zone geometry with the incident's
 * own coordinates.
 *
 * No CI lane runs androidTest here; execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     --tests com.jsonui.testrunner.runner.ScrollSurfaceOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class ScrollSurfaceOnDeviceTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test
    fun fallbackSurfaceIsTheAppWindowAndTheFailureNamesIt() {
        LaunchProbeActivity.reset()
        val runner = JsonUITestRunner(
            TestRunnerConfig(
                defaultTimeout = 500L,
                screenshotOnFailure = false,
                verifyScreenTransitions = false
            )
        )
        // launch = LaunchConfig(): the probe activity is brought up so an app
        // window of our package is the active a11y window.
        val suite = runner.runScreenTest(
            ScreenTest(
                type = "screen",
                source = TestSource(layout = "scroll_probe"),
                metadata = TestMetadata(name = "ScrollProbe"),
                launch = LaunchConfig(),
                cases = listOf(
                    TestCase(
                        name = "no container, absent target",
                        steps = listOf(
                            TestStep(action = "scrollUntilVisible", id = "jsonui_no_such_id", timeout = 3000)
                        )
                    )
                )
            )
        )
        assertEquals(1, LaunchProbeActivity.launches.get())

        val result = suite.results.single()
        assertFalse("an absent target must fail", result.passed)
        val error = result.error
        assertNotNull(error)
        assertTrue("failure must name the surface source: $error",
            error!!.contains("from app window root (rootInActiveWindow)"))
        val rect = Regex("""within \[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""").find(error)
        assertNotNull("failure must carry the rect it swiped in: $error", rect)
        val (l, _, r, _) = rect!!.destructured
        val width = r.toInt() - l.toInt()
        assertTrue("surface width $width is not a window (display ${device.displayWidth}): $error",
            width >= device.displayWidth / 2)

        // The resolver agrees with what the failure text reported, and the
        // probe window is still the active one after both search legs (no
        // swipe was taken for a back gesture).
        val now = AppWindow.rootBounds(instrumentation)
        assertNotNull("app window root must resolve while the probe is up", now)
        assertEquals(width, now!!.width())
    }
}

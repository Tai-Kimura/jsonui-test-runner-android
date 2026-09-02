package com.jsonui.testrunner.runner

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deny-assert contract, measured on a device. deny never executes a
 * revoke — measured 2026-09-02, `pm revoke` of a granted permission kills
 * the instrumented process (ActivityManager: "permissions revoked") and
 * `appops set` on permission-backed ops is a silent no-op — so the two
 * honest outcomes are the ones asserted here:
 *  - already denied: satisfied, the file runs
 *  - granted: a loud per-file failure naming the cure, and the run SURVIVES
 *    (reaching the assertions after the failure is itself the not-killed
 *    measurement, the same shape as the clearState wipe test).
 *
 * READ_CONTACTS is never granted by any test here, CAMERA is granted in-run
 * by the loud-fail path only; see the manifest comment.
 */
@RunWith(AndroidJUnit4::class)
class PermissionDenyOnDeviceTest {

    private fun runWith(permissions: Map<String, String>) = JsonUITestRunner(
        TestRunnerConfig(
            defaultTimeout = 500L,
            screenshotOnFailure = false,
            verifyScreenTransitions = false
        )
    ).runScreenTest(
        ScreenTest(
            type = "screen",
            source = TestSource(layout = "deny_probe"),
            metadata = TestMetadata(name = "DenyProbe"),
            launch = LaunchConfig(permissions = permissions),
            cases = listOf(
                TestCase(
                    name = "probe_case",
                    steps = listOf(
                        TestStep(assert = "exists", id = "jsonui_probe_absent", timeout = 300)
                    )
                )
            )
        )
    )

    @Test
    fun denyOfAnUngrantedPermissionIsSatisfiedAndTheFileRuns() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "precondition: READ_CONTACTS must start denied — something granted " +
                "it, which no test here should do",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
        )
        val suite = runWith(mapOf("contacts" to "deny"))
        assertEquals(1, suite.results.size)
        val error = suite.results[0].error ?: ""
        assertTrue(
            "satisfied deny must not produce the launch failure (got: $error)",
            !error.contains("launch.permissions deny")
        )
    }

    @Test
    fun denyOfAGrantedPermissionFailsTheFileLoudlyAndTheRunSurvives() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val pkg = instr.targetContext.packageName
        UiDevice.getInstance(instr)
            .executeShellCommand("pm grant $pkg android.permission.CAMERA")

        val suite = runWith(mapOf("camera" to "deny"))

        // Reaching this line IS the not-killed assertion.
        assertEquals(1, suite.results.size)
        val result = suite.results[0]
        assertTrue("the file must fail", !result.passed)
        val error = result.error ?: ""
        assertTrue(
            "the failure must name the cause (got: $error)",
            error.contains("launch.permissions deny") && error.contains("GRANTED")
        )
        assertTrue(
            "the failure must name the cure (got: $error)",
            error.contains("pregrant")
        )
    }
}

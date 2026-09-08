package com.jsonui.testrunner.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The run-orientation default must name the DEVICE, not the moment.
 *
 * `resolveSizeTier` answers two different questions in this driver:
 *
 *   "what window is this drawn in?" — responsive gating. Current width is
 *      correct: the answer must move when the device rotates.
 *   "which device is this lane?"    — run orientation. Current width is
 *      wrong: the answer must NOT move when the device rotates.
 *
 * Measured 2026-09-08 by the reporting lane: a 1280x800dp tablet resolved
 * `regular` in landscape and `medium` in portrait, so a face that declared
 * `{"regular": "landscape"}` failed to match at exactly the moment it wanted
 * to. And a 411x914dp phone resolved `regular` once rotated, matching the
 * tablet's row and pinning itself landscape.
 */
class RunOrientationTierIsDeviceNotWindowTest {

    private val defaults = ResponsiveThresholds(medium = 600, regular = 840)

    private fun deviceTier(w: Int, h: Int) = resolveSizeTier(minOf(w, h), defaults)

    @Test
    fun `a tablet resolves the same tier in both orientations`() {
        // conf_ci: 2560x1600 at dpi 320 = 1280x800dp
        assertEquals(deviceTier(1280, 800), deviceTier(800, 1280))
    }

    @Test
    fun `a phone resolves the same tier in both orientations`() {
        // phone_ci: 411x914dp
        assertEquals(deviceTier(411, 914), deviceTier(914, 411))
    }

    @Test
    fun `the control - current-width resolution DOES change with rotation`() {
        // ⚠️ Without this, the test above passes for a thresholds table that
        // never distinguishes anything. This pins that the old behaviour was
        // genuinely orientation-dependent, which is what made it wrong here.
        assertNotEquals(
            resolveSizeTier(800, defaults),   // tablet portrait, current width
            resolveSizeTier(1280, defaults)   // tablet landscape, current width
        )
    }

    @Test
    fun `a phone and a tablet do not collapse onto the same tier`() {
        // 🚨 The reported hazard: a rotated phone resolved `regular` and
        // matched the tablet's row. Under smallestWidth they stay distinct.
        assertNotEquals(deviceTier(411, 914), deviceTier(1280, 800))
    }

    @Test
    fun `the tablet lands in medium under smallestWidth`() {
        // ⚠️ Stated because it MOVES a boundary: 800dp is below regular's 840,
        // so this device is `medium` where landscape once made it `regular`.
        // A face that declared only `regular` stops matching — for a new
        // reason. The run-start floor exists so that face is still covered.
        assertEquals("medium", deviceTier(1280, 800))
    }

    @Test
    fun `the runner resolves the orientation tier from smallestWidth`() {
        // The pairing arm: the behaviour above is only reached if the runner
        // actually calls it that way. Asserted against the runner's source so
        // that changing the call site reddens this.
        val src = File(
            "src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt"
        ).readText()
        assertTrue(
            "applyRunOrientation no longer resolves the tier from smallestWidth",
            src.contains("resolveSizeTier(minOf(size.width, size.height)")
        )
    }

    @Test
    fun `the responsive path still resolves from the current width`() {
        // The other half of the pair. Fixing one question must not change the
        // answer to the other: responsive gating MUST follow rotation.
        val src = File(
            "src/main/kotlin/com/jsonui/testrunner/models/ResponsiveCondition.kt"
        ).readText()
        assertTrue(
            "responsive gating no longer uses the live window width",
            src.contains("resolveSizeTier(size.width, thresholds)")
        )
    }

    @Test
    fun `an undeclared run falls back to the orientation the run started in`() {
        // 🚨 The floor. `setOrientation` is absolute since 1.12.0, so without
        // this a single rotating case leaks into every later case.
        //
        // ⚠️ This arm originally pinned `runStartOrientation = observeOrientation()`
        // — an implementation that COULD NOT FIRE, because the runner is new
        // for every test, so capture and use happened in one call. The arm was
        // green against a dead floor: it read the spelling, not the lifetime.
        // Behaviour now lives in RunOrientationFloorTest; this only pins that
        // the runner delegates there and consults it last.
        val src = File(
            "src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt"
        ).readText()
        assertTrue(
            "the runner no longer captures the run-start orientation",
            src.contains("RunOrientationFloor.captureOnce(observeOrientation())")
        )
        assertTrue(
            "the run-start orientation is no longer used as the last resort",
            src.contains("?: runStartOrientation")
        )
    }
}

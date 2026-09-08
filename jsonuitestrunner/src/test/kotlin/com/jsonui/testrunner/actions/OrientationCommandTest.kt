package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `setOrientation` names a RESULT, so the call it maps to must too.
 *
 * The defect this pins is not a crash and not a red test: until 1.12.0 both
 * arms were natural-relative (`setOrientationLeft` / `setOrientationNatural`),
 * so on a landscape-natural tablet `"landscape"` produced portrait and
 * `"portrait"` produced landscape — with every assertion in every case still
 * passing, in the orientation nobody asked for. What it actually cost was a
 * gate that could never be met: `responsive: { orientation: "portrait" }` was
 * unreachable on such a device.
 *
 * A JVM test cannot rotate a device, so what is pinned here is the MAPPING —
 * that the driver asks for an orientation rather than for a rotation. The
 * device-side half is stated in [OrientationCommand]'s docstring from a
 * screen recording, and read out of the uiautomator 2.3.0 bytecode; this file
 * is the half that a build can check.
 */
class OrientationCommandTest {

    @Test
    fun portraitAsksForPortrait() {
        assertEquals(OrientationCommand.PORTRAIT, OrientationCommand.forOrientation("portrait"))
    }

    @Test
    fun landscapeAsksForLandscape() {
        assertEquals(OrientationCommand.LANDSCAPE, OrientationCommand.forOrientation("landscape"))
    }

    @Test
    fun bothArmsAreDistinct() {
        // The failure mode was not "one arm wrong" but "both arms relative to
        // the same origin", which is what let the earlier fix proposal treat
        // it as a one-sided problem.
        assertEquals(
            2,
            setOf(
                OrientationCommand.forOrientation("portrait"),
                OrientationCommand.forOrientation("landscape")
            ).size
        )
    }

    @Test
    fun anUnknownValueIsNullNotADefault() {
        // A value that quietly became one of the two would leave the device in
        // an orientation nobody asked for and pass every assertion.
        assertNull(OrientationCommand.forOrientation("sideways"))
        assertNull(OrientationCommand.forOrientation(""))
    }

    /**
     * The call site, not the mapping.
     *
     * MEASURED GAP, stated rather than papered over: reverting the two
     * `device.setOrientation*` calls in `ActionExecutor` to the old
     * natural-relative pair leaves every other arm in this class GREEN — the
     * mapping is pinned, the call that consumes it is not, because a JVM unit
     * test cannot construct a `UiDevice` and so cannot observe which method
     * was asked for. (Run as a mutation: 0 of 108 turned red.)
     *
     * So this arm reads the source. That is a weaker instrument than
     * executing the code and it is deliberately narrow — it can only say that
     * a specific spelling is absent, not that the right one is present in the
     * right branch. What it does close is the exact regression: the two
     * natural-relative calls coming back, which is how this defect shipped
     * and stayed for as long as it did.
     *
     * The read itself is guarded: an unreadable file FAILS instead of
     * skipping, because a source-scanning arm whose file has moved is
     * otherwise green forever while checking nothing.
     */
    @Test
    fun theCallSiteDoesNotUseTheNaturalRelativeApi() {
        val source = java.io.File(
            "src/main/kotlin/com/jsonui/testrunner/actions/ActionExecutor.kt"
        )
        assertTrue(
            "ActionExecutor.kt not found at ${source.absolutePath} — this arm " +
                "scans source, so a moved file must fail it, never skip it",
            source.isFile
        )
        val text = source.readText()
        // Only CALLS. The docstring names both methods on purpose, to say what
        // the old behaviour was, and a scan that matched prose would force the
        // explanation to be deleted to keep the test green.
        for (call in listOf("device.setOrientationLeft(", "device.setOrientationRight(",
                            "device.setOrientationNatural(")) {
            assertFalse(
                "ActionExecutor still calls $call — that is natural-relative, " +
                    "and inverts on a landscape-natural device (see " +
                    "OrientationCommand). Use setOrientationPortrait/Landscape.",
                text.contains(call)
            )
        }
        // Positive control: the scan is looking at the right file. Without
        // this, a file that lost the rotation code entirely would pass.
        assertTrue(
            "the scanned file does not contain the replacement calls either — " +
                "this arm is reading the wrong file",
            text.contains("device.setOrientationPortrait(") &&
                text.contains("device.setOrientationLandscape(")
        )
    }

    @Test
    fun matchingIsCaseSensitive() {
        // The schema enum is lower-case; accepting 'Portrait' here would make
        // the driver more permissive than the file that validates the tests.
        assertNull(OrientationCommand.forOrientation("Portrait"))
        assertNull(OrientationCommand.forOrientation("LANDSCAPE"))
    }
}

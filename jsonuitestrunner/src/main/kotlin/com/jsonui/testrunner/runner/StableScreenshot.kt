package com.jsonui.testrunner.runner

import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * A screenshot of what the display shows once it has caught up, not of the
 * frame that happened to be presented when the step ran.
 *
 * The steps before a `screenshot` wait on the accessibility tree, and the
 * tree is current as soon as the app has laid the change out; the pixels
 * reach the display one or more frames later (RenderThread, then
 * SurfaceFlinger), and how many varies with the emulator's load. A capture
 * in that gap is the previous frame: a consumer flow tapped the bar tab,
 * waited for its first row and for the purchase tab's notice to go, and
 * still saved a PNG byte-identical to the purchase tab it had just left —
 * twice in two runs (ticket
 * test-android-screenshot-after-tab-switch-saves-the-previous-frame).
 * KotlinJsonUI's conformance host met the same thing on CI and settled it
 * the same way (ConformanceSuiteTest.captureStableScreenshot): retake every
 * [INTERVAL_MS] until two captures agree, at most [MAX_RETAKES] retakes.
 * A screen that has already caught up costs one retake.
 */
object StableScreenshot {
    const val INTERVAL_MS = 100L
    const val MAX_RETAKES = 12

    /** The last capture, how many captures were taken, and whether two agreed. */
    class Outcome(val bytes: ByteArray, val captures: Int, val stable: Boolean)

    /**
     * Captures until two consecutive captures are byte-identical, or
     * [maxRetakes] retakes have been taken; null when the first capture
     * fails. A failed retake keeps the last good capture.
     */
    fun settle(
        capture: () -> ByteArray?,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        intervalMs: Long = INTERVAL_MS,
        maxRetakes: Int = MAX_RETAKES,
    ): Outcome? {
        var previous = capture() ?: return null
        var captures = 1
        repeat(maxRetakes) {
            sleep(intervalMs)
            val current = capture() ?: return Outcome(previous, captures, false)
            captures++
            if (current.contentEquals(previous)) return Outcome(current, captures, true)
            previous = current
        }
        return Outcome(previous, captures, false)
    }

    /**
     * Writes the settled capture to [file]. Returns the outcome, or null when
     * the device could not take a screenshot at all.
     */
    fun capture(device: UiDevice, file: File): Outcome? {
        val scratch = File(file.parentFile, "${file.nameWithoutExtension}.settling.png")
        try {
            val outcome = settle(capture = {
                if (device.takeScreenshot(scratch)) scratch.readBytes() else null
            }) ?: return null
            file.writeBytes(outcome.bytes)
            return outcome
        } finally {
            scratch.delete()
        }
    }
}

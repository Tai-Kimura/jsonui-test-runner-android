package com.jsonui.testrunner.runner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the settle rule behind every driver screenshot. A capture is
 * stood in by a scripted list of frames; `null` is a failed capture.
 */
class StableScreenshotTest {

    private val old = byteArrayOf(1)
    private val new = byteArrayOf(2)
    private val sleeps = mutableListOf<Long>()

    private fun settle(vararg frames: ByteArray?): StableScreenshot.Outcome? {
        val queue = ArrayDeque(frames.toList())
        return StableScreenshot.settle(
            capture = { if (queue.isEmpty()) frames.last() else queue.removeFirst() },
            sleep = { sleeps += it },
        )
    }

    /** The ticket's case: the first capture is the frame before the change. */
    @Test
    fun aCaptureTakenBeforeTheDisplayCaughtUpIsReplacedByTheFrameAfterIt() {
        val outcome = settle(old, new, new)!!
        assertArrayEquals(new, outcome.bytes)
        assertEquals(3, outcome.captures)
        assertTrue(outcome.stable)
    }

    @Test
    fun aScreenAlreadyCaughtUpCostsOneRetake() {
        val outcome = settle(new, new)!!
        assertArrayEquals(new, outcome.bytes)
        assertEquals(2, outcome.captures)
        assertEquals(listOf(StableScreenshot.INTERVAL_MS), sleeps)
    }

    @Test
    fun aScreenThatNeverSettlesStopsAtTheRetakeLimitWithTheLastCapture() {
        var n = 0
        val outcome = StableScreenshot.settle(capture = { byteArrayOf((n++).toByte()) }, sleep = {})!!
        assertFalse(outcome.stable)
        assertEquals(1 + StableScreenshot.MAX_RETAKES, outcome.captures)
        assertArrayEquals(byteArrayOf(StableScreenshot.MAX_RETAKES.toByte()), outcome.bytes)
    }

    @Test
    fun aFailedFirstCaptureIsNoScreenshot() {
        assertNull(settle(null))
    }

    @Test
    fun aFailedRetakeKeepsTheLastGoodCapture() {
        val outcome = settle(old, null)!!
        assertArrayEquals(old, outcome.bytes)
        assertEquals(1, outcome.captures)
        assertFalse(outcome.stable)
    }
}

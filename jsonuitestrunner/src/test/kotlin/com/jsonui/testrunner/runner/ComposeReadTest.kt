package com.jsonui.testrunner.runner

import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words that tell a build R8 cut from a screen that has no marker. On the device: the probe app under R8. */
class ComposeReadTest {
    private val gone = StateBridge.Reading.Unreadable("gone")

    @After fun clear() = ComposeRead.forget()

    @Test
    fun aMissingMemberNamesItselfAndTheWayOut() {
        val text = ComposeRead.message(NoSuchMethodError("No interface method getSemanticsOwner()"))
        assertTrue(text, text.contains("NoSuchMethodError: No interface method getSemanticsOwner()"))
        assertTrue(text, text.contains(ComposeRead.KEEP_ARTIFACT))
    }

    @Test
    fun aLongMessageIsCut() {
        assertFalse(ComposeRead.message(NoSuchMethodError("x".repeat(500))).contains("x".repeat(121)))
    }

    @Test
    fun theReceiptDoesNotWaitOutTheTimeout() {
        var reads = 0
        var clock = 0L
        val e = assertThrows(InitialStateNotConfirmedException::class.java) {
            StateBridge.awaitReceipt("s", "L1", 15000, read = { reads++; gone }, sleep = {}, now = { clock += 1000; clock })
        }
        assertEquals(1, reads)
        assertTrue(e.message!!, e.message!!.contains("gone"))
        assertFalse(e.message!!, e.message!!.contains("no screen marker"))
    }

    @Test
    fun anAbsentMarkerKeepsItsOwnWords() {
        val text = StateBridge.noReceiptMessage("s", 15000, StateBridge.Reading.Absent)
        assertTrue(text, text.contains("no screen marker"))
        assertFalse(text, text.contains(ComposeRead.KEEP_ARTIFACT))
    }

    @Test
    fun theStateOutcomeFails() {
        assertEquals(StateBridge.Outcome.Unreadable("gone"), StateBridge.outcome(gone, "a"))
    }

    @Test
    fun aBridgeStaysABridge() {
        val b = StateBridge.Reading.Bridge("L1", StateBridge.Receipt(emptyList(), emptyList()), buildJsonObject { })
        assertEquals(StateBridge.ReceiptState.Confirmed, StateBridge.receipt(b, "L1"))
    }

    @Test
    fun noteIsEmptyUntilAMissSawIt() {
        assertEquals("", ComposeRead.note())
        ComposeRead.saw("gone")
        assertEquals(" (gone)", ComposeRead.note())
    }

    @Test
    fun anAppWithoutComposeIsNotABuildR8Cut() {
        // Compose is compileOnly: this classpath is an app without it.
        assertFalse(ComposeRead.classPresent)
        assertEquals(null, HiddenNodes.isHidden("any"))
        assertEquals(emptyList<ZeroSizeNodes.Hit>(), ZeroSizeNodes.find("any"))
        assertEquals("", ComposeRead.note())
    }
}

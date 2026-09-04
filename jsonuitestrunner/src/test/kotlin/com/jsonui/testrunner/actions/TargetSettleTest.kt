package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the settle / visibility geometry behind scrollUntilVisible
 * and the tap-then-expect failure text.
 *
 * The numbers are the 2026-09-04 consumer capture: target
 * [42,237][1038,319] (82px tall), scrollable viewport top at y=300, so
 * 19px / 82px of the target was on screen.
 */
class TargetSettleTest {

    private val full = Box(42, 237, 1038, 319)
    private val visible = Box(42, 300, 1038, 319)

    @Test
    fun capturedGeometryIsTwentyThreePercentVisible() {
        assertEquals(23, TargetSettle.visiblePercent(visible, full))
    }

    @Test
    fun fullyVisibleIsHundredAndMissingIsZero() {
        assertEquals(100, TargetSettle.visiblePercent(full, full))
        assertEquals(0, TargetSettle.visiblePercent(null, full))
        assertEquals(0, TargetSettle.visiblePercent(visible, null))
        assertEquals(0, TargetSettle.visiblePercent(visible, Box(0, 0, 0, 0)))
        // a clipped rect reported larger than the unclipped one never exceeds 100
        assertEquals(100, TargetSettle.visiblePercent(Box(0, 0, 2000, 2000), full))
    }

    @Test
    fun settledMeansTheLastTwoSamplesAgree() {
        assertFalse(TargetSettle.settled(emptyList()))
        assertFalse(TargetSettle.settled(listOf(full)))
        assertFalse(TargetSettle.settled(listOf(full, full.copy(top = 230, bottom = 312))))
        assertTrue(TargetSettle.settled(listOf(full, full.copy(top = 230, bottom = 312), full.copy(top = 230, bottom = 312))))
    }

    @Test
    fun movedPxIsTheDisplacementBetweenFirstAndLastSample() {
        // the target slid up 20px after it was first seen, then stopped
        val samples = listOf(full, full.copy(top = 227, bottom = 309), full.copy(top = 217, bottom = 299), full.copy(top = 217, bottom = 299))
        assertEquals(20, TargetSettle.movedPx(samples))
        assertEquals(0, TargetSettle.movedPx(listOf(full)))
        assertEquals(0, TargetSettle.movedPx(listOf(full, full)))
    }

    @Test
    fun describeNamesTheFractionTheRectsAndTheClipper() {
        val line = TargetSettle.describe("release_event_select", visible, full, "form_scroll" to Box(0, 300, 1080, 2127))
        assertEquals(
            "target 'release_event_select' visible 23% (visible [42,300][1038,319] of [42,237][1038,319]), " +
                "clipped by scrollable 'form_scroll' [0,300][1080,2127]",
            line
        )
    }

    @Test
    fun describeDegradesWhenHalfTheGeometryIsMissing() {
        assertEquals("target 'x' was not in the tree afterwards", TargetSettle.describe("x", null, null, null))
        assertEquals("target 'x' visible 0% (visible [42,300][1038,319])", TargetSettle.describe("x", visible, null, null))
        assertEquals("target 'x' visible 0% (bounds [42,237][1038,319])", TargetSettle.describe("x", null, full, null))
    }

    @Test
    fun settleLineCarriesWhatAConsumerNeedsToReadMovement() {
        val line = TargetSettle.settleLine("t", listOf(full, full.copy(top = 227, bottom = 309), full.copy(top = 227, bottom = 309)), 210)
        assertEquals(
            "scrollUntilVisible 't': settled=true after 210ms, moved 10px over 3 sample(s), resting [42,227][1038,309]",
            line
        )
        assertEquals(
            "scrollUntilVisible 't': settled=false after 2000ms, moved 0px over 0 sample(s)",
            TargetSettle.settleLine("t", emptyList(), 2000)
        )
    }
}

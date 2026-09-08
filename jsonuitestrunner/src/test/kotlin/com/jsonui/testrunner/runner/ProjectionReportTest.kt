package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict a failed lookup records about the accessibility projection.
 * These are the three answers a consumer investigation (2026-09-04) needed
 * and could not get, because the shape is intermittent — 2 of 5 runs, then
 * 1 of 4, then 0 of 5 — so nothing could be measured on demand.
 */
class ProjectionReportTest {

    private val missing = "sort_control"

    @Test
    fun uiAutomatorCacheWasStale() {
        val verdict = ProjectionReport.verdict(
            missing,
            before = setOf("detailPane", "pane_content"),
            afterClearCache = setOf("detailPane", "pane_content", missing),
            afterServiceResync = setOf("detailPane", "pane_content", missing)
        )
        assertEquals(ProjectionVerdict.RECOVERED_BY_CLEAR_CACHE, verdict)
    }

    @Test
    fun onlyTheServiceResyncHelped() {
        val verdict = ProjectionReport.verdict(
            missing,
            before = setOf("pane_content"),
            afterClearCache = setOf("pane_content"),
            afterServiceResync = setOf("pane_content", missing)
        )
        assertEquals(ProjectionVerdict.RECOVERED_BY_SERVICE_RESYNC, verdict)
    }

    @Test
    fun theAppSideNeverProjectedIt() {
        // The 2026-07-17 shape: the projection holds a subtree the current
        // composition no longer has, and neither recovery produces the id.
        //
        // ⚠️ REPOINTED 2026-09-08. This used to assert the sentence "the app
        // side never projected it" on the DEFAULT render, and that assertion
        // is what made the overclaim look verified: the same census is
        // produced by an id that exists BELOW the viewport, and the default
        // render knows nothing about a scroll container. The claim now lives
        // in the NO_ROOM_LEFT branch alone — see the case below and
        // OffscreenPossibilityTest. What this arm still owns is the 2026-07-17
        // shape itself: verdict, and that the stale ids are shown.
        val stale = setOf("detailPane", "pane_content", "legacy_list")
        val verdict = ProjectionReport.verdict(missing, stale, stale, stale)
        assertEquals(ProjectionVerdict.STILL_MISSING, verdict)
        val text = ProjectionReport.render(missing, stale, stale, stale)
        assertTrue(text, text.contains("STILL_MISSING"))
        assertTrue(text, text.contains("legacy_list"))
        assertTrue(text, text.contains("UNKNOWN"))
        assertTrue(text, !text.contains("the app side never projected it"))
    }

    @Test
    fun theOriginalClaimSurvivesWhereItIsWarranted() {
        // The control for the repointing above. Weakening a message is only
        // correct if the strong form is still REACHABLE — otherwise the fix
        // deleted a true statement instead of bounding a false one.
        val stale = setOf("detailPane", "pane_content", "legacy_list")
        val text = ProjectionReport.render(
            missing, stale, stale, stale,
            offscreen = OffscreenPossibility.NO_ROOM_LEFT)
        assertTrue(text, text.contains("the app side never projected it"))
    }

    @Test
    fun aLookupThatFailedForAnotherReasonSaysSo() {
        // The id was there the whole time: whatever failed, it was not the
        // projection — and the report must not blame it.
        val present = setOf("pane_content", missing)
        assertEquals(
            ProjectionVerdict.PRESENT_ALL_ALONG,
            ProjectionReport.verdict(missing, present, present, present)
        )
    }

    @Test
    fun aViewIdIsFoundUnderItsQualifiedSpelling() {
        // Compose test tags are bare; View ids come back as `pkg:id/name`,
        // and tests are written against the bare spelling for both. Measured
        // against a positive control on device: comparing with `==` called a
        // visible TextView missing.
        val projection = setOf("com.example.app:id/tmp_probe_present", "android:id/content", "bare_tag")
        assertTrue(ProjectionReport.holds(projection, "tmp_probe_present"))
        assertTrue(ProjectionReport.holds(projection, "bare_tag"))
        assertEquals(
            ProjectionVerdict.PRESENT_ALL_ALONG,
            ProjectionReport.verdict("tmp_probe_present", projection, projection, projection)
        )
        // A partial segment must NOT match: "probe_present" is only a tail
        // of the name "tmp_probe_present", not the name.
        assertFalse(ProjectionReport.holds(projection, "probe_present"))
        // "content" IS the whole name in "android:id/content", so it matches
        // — that is the same bare spelling a test would be written against.
        assertTrue(ProjectionReport.holds(projection, "content"))
    }

    @Test
    fun theReportNamesWhatMovedInEachDirection() {
        val before = setOf("a", "b", "gone_after")
        val after = setOf("a", "b", missing, "new_one")
        val text = ProjectionReport.render(missing, before, before, after)
        assertTrue(text, text.contains("RECOVERED_BY_SERVICE_RESYNC"))
        assertTrue(text, text.contains("appeared after recovery (2)"))
        assertTrue(text, text.contains("vanished after recovery (1)"))
        assertTrue(text, text.contains("gone_after"))
        // Counts come from the sets, not from a scan of the text.
        assertTrue(text, text.contains("before=3"))
        assertTrue(text, text.contains("afterServiceResync=4"))
    }

    @Test
    fun samplesAreBoundedSoAWedgedTreeCannotFloodTheFailure() {
        val many = (1..500).map { "id_$it" }.toSet()
        val text = ProjectionReport.render(missing, many, many, many, sampleLimit = 5)
        assertEquals(ProjectionVerdict.STILL_MISSING, ProjectionReport.verdict(missing, many, many, many))
        assertTrue(text, text.contains("(500, first 5)"))
        assertFalse(text, text.contains("id_400"))
    }
}

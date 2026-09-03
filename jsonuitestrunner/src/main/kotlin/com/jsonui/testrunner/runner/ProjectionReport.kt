package com.jsonui.testrunner.runner

/** What the failure-path probe concluded about a missing element. */
enum class ProjectionVerdict {
    /** The id was in the projection all along — the miss was not the projection. */
    PRESENT_ALL_ALONG,

    /** UiAutomator's node cache was stale: dropping it made the id appear. */
    RECOVERED_BY_CLEAR_CACHE,

    /** The cache was not the problem, but resyncing the a11y service state was. */
    RECOVERED_BY_SERVICE_RESYNC,

    /** Neither helped: the app side never projected it (the 2026-07-17 shape). */
    STILL_MISSING,
}

/**
 * The verdict and the report text for one failed lookup, from three
 * snapshots of the app's own `viewIdResourceName` set.
 *
 * Pure so it can be tested without a device, and because the interesting
 * part is the reasoning, not the collection: which of "the tester's cache
 * was stale" and "the app never projected it" is true decides whether the
 * repair belongs in this driver or in the renderer. A consumer report
 * (2026-09-04) could not answer it, because the shape is intermittent —
 * 2 in 5 runs, then 1 in 4, then 0 in 5 — so a plan to reproduce it and
 * then measure never got its measurement. This runs INSIDE the failure, so
 * the next occurrence is the experiment.
 *
 * Sets are attribute-based (`AccessibilityNodeInfo.viewIdResourceName`),
 * never a regex over a dump: the dump is one line, so `resource-id="` +
 * `.*?` + `<tag>"` also matches a later node's text or content-desc, and
 * any id merely ENDING in the tag (measured 2026-09-04: three
 * false-positive shapes).
 */
object ProjectionReport {

    /**
     * Whether the projection holds this id, in either spelling it can have.
     *
     * `viewIdResourceName` is fully qualified for a View (`pkg:id/name`),
     * while a Compose `testTag` surfaced through `testTagsAsResourceId` is
     * the bare tag — and tests are written against the bare spelling for
     * both. Comparing bare-to-qualified with `==` reports a View-backed id
     * as missing while it is on screen: measured 2026-09-04 against a
     * positive control (a TextView with a real id, visible, reported
     * STILL_MISSING).
     */
    fun holds(ids: Set<String>, id: String): Boolean =
        id in ids || ids.any { it.endsWith(":id/$id") }

    fun verdict(
        id: String,
        before: Set<String>,
        afterClearCache: Set<String>,
        afterServiceResync: Set<String>
    ): ProjectionVerdict = when {
        holds(before, id) -> ProjectionVerdict.PRESENT_ALL_ALONG
        holds(afterClearCache, id) -> ProjectionVerdict.RECOVERED_BY_CLEAR_CACHE
        holds(afterServiceResync, id) -> ProjectionVerdict.RECOVERED_BY_SERVICE_RESYNC
        else -> ProjectionVerdict.STILL_MISSING
    }

    /**
     * Human-readable block for the failure message. Reports the sizes, the
     * verdict, and — for a still-missing id — a bounded sample of what the
     * projection DOES hold, which is what tells a reader whether the
     * projection is merely incomplete or is a snapshot of an older
     * composition (ids present that the current screen no longer has).
     */
    fun render(
        id: String,
        before: Set<String>,
        afterClearCache: Set<String>,
        afterServiceResync: Set<String>,
        sampleLimit: Int = 24
    ): String {
        val verdict = verdict(id, before, afterClearCache, afterServiceResync)
        val lines = mutableListOf(
            "projection probe for '$id': $verdict",
            "  own-package ids: before=${before.size} afterClearCache=${afterClearCache.size} " +
                "afterServiceResync=${afterServiceResync.size}"
        )
        val appeared = (afterServiceResync - before).sorted()
        val vanished = (before - afterServiceResync).sorted()
        if (appeared.isNotEmpty()) {
            lines += "  appeared after recovery (${appeared.size}): ${appeared.take(sampleLimit)}"
        }
        if (vanished.isNotEmpty()) {
            lines += "  vanished after recovery (${vanished.size}): ${vanished.take(sampleLimit)}"
        }
        if (verdict == ProjectionVerdict.STILL_MISSING) {
            lines += "  the projection holds these instead (${before.size}, first $sampleLimit): " +
                before.sorted().take(sampleLimit)
            lines += "  => neither UiAutomator's cache nor a service resync produced it; " +
                "the app side never projected it"
        }
        return lines.joinToString("\n")
    }
}

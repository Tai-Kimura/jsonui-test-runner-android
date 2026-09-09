package com.jsonui.testrunner.actions

/**
 * Whether `scrollUntilVisible` stopped with the target FLUSH against the
 * viewport edge, and how far to move it.
 *
 * Reported 2026-09-08 by a consumer face on a landscape tablet. The early
 * return asks "does the id exist in the projection?", never "how much of it
 * can be seen", so a target peeking a few pixels above the bottom edge counts
 * as found and NO scroll happens. Harmless on its own — until operating that
 * target reveals something directly BELOW it, which then lands off-screen.
 * Off-screen Compose nodes are not projected, so the following
 * `assert visible` sees a census identical to the one it would see if the
 * operation had done nothing at all.
 *
 * ⚠️ Phone lanes never see this: in portrait the target does not end up
 * pinned to the edge. The face measured 6 consecutive green phone runs
 * against a reproducible tablet failure, which is why a single-form-factor
 * suite cannot be the evidence that this is fixed.
 *
 * 📌 The rule is deliberately one-directional: it says when a target is TOO
 * CLOSE to an edge, never where it ought to sit. Centring the target would
 * be a bigger behaviour change than the defect warrants, and any test that
 * currently passes with the target mid-viewport must keep passing unchanged.
 */
object ViewportMargin {

    /**
     * Fraction of the surface's shorter dimension a target must be clear of
     * the trailing edge by. 12% ≈ one list row on the shapes measured, which
     * is what "the thing revealed below it" needs to land inside.
     *
     * ⚠️ "SHORTER DIMENSION" IS RESOLVED PER CALL, NOT PER DEVICE. The surface
     * is whatever [clearanceFor] is handed — for a container step it is that
     * container's *visible* bounds, which by definition shrink when anything
     * covers part of it. So the axis that is shorter can SWAP WITHIN ONE RUN on
     * one device: a 1080-wide surface yields 129 while the same surface reduced
     * to 1000 tall yields 120, and nothing about the device changed. Two
     * clearances that differ are not evidence of a bug.
     *
     * 🚨 THIS CONSTANT HAS A SECOND USE WITH A DIFFERENT BASE. The corrective
     * swipe in `ActionExecutor.unstickFromTrailingEdge` sizes its step as
     * `surface.height() * CLEARANCE_FRACTION` — height, not the shorter side.
     * The two agree in landscape and diverge in portrait (1080x2400: clearance
     * 129, step 288, so the swipe travels 576px to repair a 129px shortfall),
     * and [MAX_CLEARANCE_PX] caps the clearance while nothing caps the step.
     * Filed rather than changed: making them agree is a behaviour change and
     * this repo has no device to measure the shorter swipe against.
     *
     * ⚠️ Not tuned against the reporting face's flow — that flow is on a
     * consumer tree this repo cannot run. It is a floor chosen to be smaller
     * than a row and larger than the few pixels that produced the report.
     */
    const val CLEARANCE_FRACTION = 0.12

    /** No clearance is demanded beyond this many pixels, whatever the size. */
    const val MAX_CLEARANCE_PX = 240

    @JvmStatic
    fun clearanceFor(surfaceHeight: Int, surfaceWidth: Int): Int {
        val shorter = minOf(surfaceHeight, surfaceWidth).coerceAtLeast(0)
        return minOf((shorter * CLEARANCE_FRACTION).toInt(), MAX_CLEARANCE_PX)
    }

    /**
     * True when *targetBottom* sits within the clearance of *surfaceBottom*.
     *
     * ⚠️ Only the trailing (bottom) edge, and only for a downward search.
     * The leading edge has no equivalent failure: something revealed BELOW a
     * target pinned to the TOP lands inside the viewport, not outside it.
     * Making this symmetric would add scrolls to passing tests for a shape
     * nobody has reported.
     */
    @JvmStatic
    fun isFlushAgainstTrailingEdge(
        targetBottom: Int,
        surfaceBottom: Int,
        surfaceHeight: Int,
        surfaceWidth: Int
    ): Boolean {
        if (surfaceHeight <= 0 || surfaceWidth <= 0) return false
        val clearance = clearanceFor(surfaceHeight, surfaceWidth)
        if (clearance <= 0) return false
        return targetBottom > surfaceBottom - clearance
    }

    /**
     * True when the extra scroll IMPROVED things and should be kept.
     *
     * 📌 This is the "never make it worse" half, and it is the reason the fix
     * can ship without the reporting face's flow to test against. The extra
     * scroll is speculative: a container at the end of its content, or one
     * that overshoots, can leave the target further from view than it started
     * — or gone. So the position is re-measured afterwards and kept only if
     * the target is still there AND no closer to the edge than before. A
     * change that can only leave the target where it was or further from the
     * edge cannot redden a test that passes today ON POSITION.
     *
     * ⚠️ ON POSITION is the whole scope of that sentence, and 1.15.0 is where
     * the distinction starts to matter: the rule now runs on the scroll legs
     * too, so it fires far more often, and every firing costs a swipe and a
     * settle. Where a suite runs near a timeout, longer and redder are the
     * same event. The headroom is unmeasured.
     *
     * ⚠️ And the re-measure only means something if it reads a RESTING value.
     * Until 1.15.0 the caller sampled it one waitForIdle into a fling — 1184
     * against a resting 1157 in the reporting capture. The guard was sound and
     * its input was not, which no test of this function could ever show.
     */
    @JvmStatic
    fun keepScrolledPosition(
        before: Int?,
        after: Int?,
        surfaceBottom: Int
    ): Boolean {
        if (after == null) return false          // scrolled the target away
        if (before == null) return true          // it was not visible before
        val gainedBefore = surfaceBottom - before
        val gainedAfter = surfaceBottom - after
        return gainedAfter >= gainedBefore
    }
}

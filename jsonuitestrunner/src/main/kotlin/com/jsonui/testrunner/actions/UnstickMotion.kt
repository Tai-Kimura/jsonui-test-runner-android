package com.jsonui.testrunner.actions

import android.graphics.Point

/**
 * The shape of the clearance rule's corrective motion: a DRAG, not a fling.
 *
 * `UiDevice.swipe(x1, y1, x2, y2, steps)` lifts the finger the instant the
 * last move lands, so the release velocity is the swipe's average velocity —
 * for 20 steps (~100ms) over a few hundred px that is thousands of px/s, and
 * both Views' OverScroller and Compose's splineBasedDecay turn it into a
 * fling of unbounded, velocity-dependent length. The segment form below
 * moves the finger and then HOLDS it on the end point for [HOLD_SEGMENTS]
 * more segments before lifting, so the velocity tracker sees a stationary
 * finger at release and no fling follows. Measured on a 1080x2400 phone
 * (ScrollProbe, in-process scrollY): see [ViewportMargin.unstickTravel].
 *
 * The hold alternates 1px around the end point (ending on it) instead of
 * repeating it: moves at an unchanged position never reach the velocity
 * tracker, so a hold of repeated points was no hold — the release velocity
 * was the travel's, and the drag flung. Measured 2026-10-18 on the conf_ci
 * AVD (2560x1600, 320dpi, API 35, Views ScrollView): a 480px travel moved
 * 665–748px with a ~400ms tail whether the repeated-point hold lasted 80ms
 * (UiDevice.swipe) or 400ms (injected), and 463–465px with no tail when the
 * hold moved 1px each time (9 of 9 each way). From 1.15.5 (09c348e) the
 * device arm was red there in 3 of 3 runs.
 *
 * Not `UiDevice.drag`: that begins with a long-press hold, which on a row
 * with a long-click handler is an action, not a scroll.
 */
object UnstickMotion {
    /** Steps per segment; each step is one injected move ~5ms apart. */
    const val STEPS = 20

    /** Segments appended at the end point — the hold (1px apart, see above). */
    const val HOLD_SEGMENTS = 4

    /**
     * The y of each segment point: the start, then [HOLD_SEGMENTS] points
     * alternating between the end point plus 1px and the end point, the last
     * on the end point. Plain ints so the shape is pinned on the JVM
     * (`android.graphics.Point` is a stub there).
     */
    @JvmStatic
    fun segmentYs(fromY: Int, toY: Int): IntArray =
        intArrayOf(fromY) + IntArray(HOLD_SEGMENTS) { toY + (HOLD_SEGMENTS - 1 - it) % 2 }

    /** Finger path from (x, fromY) to (x, toY), then held there. */
    @JvmStatic
    fun path(x: Int, fromY: Int, toY: Int): Array<Point> =
        segmentYs(fromY, toY).map { Point(x, it) }.toTypedArray()
}

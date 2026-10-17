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
 * repeating it. A hold of repeated points mostly left velocity at release
 * and the drag flung — how it survives the hold was not measured. Measured
 * 2026-10-18 on Views ScrollViews (20 strokes per travel and direction):
 * on the conf_ci AVD (2560x1600, 320dpi, API 35) the repeated-point hold
 * left a tail in 120 of 120 strokes in one run and 113 of 120 in another,
 * a 480px travel moving up to 760px, and the same with the hold injected
 * for 400ms; on a 1080x2400 phone AVD (420dpi) 118 of 120; on CI's x86_64
 * tablet emulator 3 of 14. With the hold moving 1px each time: 0 of 120 on
 * both AVDs, 463 / -465px for 480px every time. From 1.15.5 (09c348e) the
 * device arm was red on conf_ci in 3 of 3 runs.
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

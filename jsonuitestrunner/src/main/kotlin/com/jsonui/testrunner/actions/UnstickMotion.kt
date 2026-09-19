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
 * Not `UiDevice.drag`: that begins with a long-press hold, which on a row
 * with a long-click handler is an action, not a scroll.
 */
object UnstickMotion {
    /** Steps per segment; each step is one injected move ~5ms apart. */
    const val STEPS = 20

    /** Zero-length segments appended at the end point — the hold. */
    const val HOLD_SEGMENTS = 4

    /**
     * The y of each segment point: the start, then the end point repeated
     * [HOLD_SEGMENTS] times. Plain ints so the shape is pinned on the JVM
     * (`android.graphics.Point` is a stub there).
     */
    @JvmStatic
    fun segmentYs(fromY: Int, toY: Int): IntArray =
        intArrayOf(fromY) + IntArray(HOLD_SEGMENTS) { toY }

    /** Finger path from (x, fromY) to (x, toY), then held there. */
    @JvmStatic
    fun path(x: Int, fromY: Int, toY: Int): Array<Point> =
        segmentYs(fromY, toY).map { Point(x, it) }.toTypedArray()
}

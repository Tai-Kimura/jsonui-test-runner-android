package com.jsonui.testrunner.actions

/** A swipe as UiDevice.swipe takes it: start point, end point (screen px). */
data class SwipeLine(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

/**
 * Geometry of a scroll swipe inside a rect, as a pure function (JVM-tested).
 *
 * The finger moves opposite to `direction` (content moves toward it), by 35%
 * of the rect's extent each side of its center.
 *
 * The START point never lies in the system's back-gesture edge zones:
 * with gesture navigation, a touch that goes DOWN within the left/right
 * edge inset is captured by the system and interpreted as a back gesture
 * even when it then moves vertically. Measured 2026-09-03 on a consumer
 * page: a 44px vertical swipe at x=74 inside a 148px-wide rect at the left
 * edge produced `CoreBackPreview: startBackNavigation` and popped two
 * screens. So the start x is clamped into
 * `[edgeInsetPx, displayWidth - edgeInsetPx]`; the end point may be
 * anywhere (only the DOWN location matters to the edge detector). When the
 * insets leave no safe band (degenerate display), the rect's own geometry is
 * used unchanged.
 */
internal fun scrollSwipe(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    direction: String,
    displayWidth: Int,
    edgeInsetPx: Int
): SwipeLine {
    val cx = (left + right) / 2
    val cy = (top + bottom) / 2
    val dy = ((bottom - top) * 0.35).toInt()
    val dx = ((right - left) * 0.35).toInt()
    val safeX = { x: Int -> clampToGestureSafeX(x, displayWidth, edgeInsetPx) }
    return when (direction) {
        "up" -> SwipeLine(safeX(cx), cy - dy, safeX(cx), cy + dy)
        "down" -> SwipeLine(safeX(cx), cy + dy, safeX(cx), cy - dy)
        "left" -> SwipeLine(safeX(cx - dx), cy, cx + dx, cy)
        "right" -> SwipeLine(safeX(cx + dx), cy, cx - dx, cy)
        else -> throw IllegalArgumentException("Invalid direction: $direction")
    }
}

/**
 * The x a swipe may START at: [x] moved into the band the back-gesture
 * detector does not own. Identity when the band is empty.
 */
internal fun clampToGestureSafeX(x: Int, displayWidth: Int, edgeInsetPx: Int): Int {
    val safeLeft = edgeInsetPx
    val safeRight = displayWidth - edgeInsetPx
    return if (safeLeft >= safeRight) x else x.coerceIn(safeLeft, safeRight)
}

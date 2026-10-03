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

/** The `swipe` action cannot start inside its element and outside the edge zones. */
internal class SwipeOutsideGestureSafeBandException(message: String) : IllegalStateException(message)

/**
 * Geometry of the `swipe` action on an element, as a pure function (JVM-tested).
 *
 * The finger moves in `direction` across the element's center by
 * `min(width, height) / 2 - inset` each side. Both endpoints sit [inset] px
 * inside the element: Compose routes the whole pointer stream by the hit test
 * of the DOWN event, and center ± width/2 is the element's exclusive edge
 * pixel — a down there misses the node, so a drag detector on it never sees
 * the gesture (driver 1.8.1; measured on the conformance host: edge-start
 * never fires, 8px-inset start always does).
 *
 * The START x also goes through [clampToGestureSafeX], the same rule as
 * [scrollSwipe]: on a full-width element the 8px inset alone lands the DOWN
 * point inside the system back-gesture zone (1.15.7: a full-width horizontal
 * pager on a 1080px phone swiped left from x=1072 and the system took it as
 * Back). Like scrollSwipe, up/down clamp x for both points and leave y alone;
 * the end of a horizontal swipe is not moved (it is already inside the
 * element, and only the DOWN location matters to the edge detector).
 *
 * When the clamp moves the start out of the element's inset interior, or
 * not past the end in the swipe's direction, no line both hits the element
 * and avoids the edge zone: [SwipeOutsideGestureSafeBandException] says so
 * instead of sending a Back.
 */
internal fun swipeActionLine(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    direction: String,
    displayWidth: Int,
    edgeInsetPx: Int,
    inset: Int = 8
): SwipeLine {
    val cx = (left + right) / 2
    val cy = (top + bottom) / 2
    val d = (minOf(right - left, bottom - top) / 2 - inset).coerceAtLeast(1)
    val raw = when (direction) {
        "up" -> SwipeLine(cx, cy + d, cx, cy - d)
        "down" -> SwipeLine(cx, cy - d, cx, cy + d)
        "left" -> SwipeLine(cx + d, cy, cx - d, cy)
        "right" -> SwipeLine(cx - d, cy, cx + d, cy)
        else -> throw IllegalArgumentException("Invalid direction: $direction")
    }
    val startX = clampToGestureSafeX(raw.startX, displayWidth, edgeInsetPx)
    if (startX == raw.startX) return raw
    val inside = startX in (left + inset)..(right - inset)
    val travels = when (direction) {
        "left" -> startX > raw.endX
        "right" -> startX < raw.endX
        else -> true
    }
    if (!inside || !travels) {
        throw SwipeOutsideGestureSafeBandException(
            "element x $left..$right has no start point ${inset}px inside it " +
                "that is outside the ${edgeInsetPx}px system gesture edge zone " +
                "(safe x $edgeInsetPx..${displayWidth - edgeInsetPx} on a ${displayWidth}px display); " +
                "a swipe from x=${raw.startX} would be taken as Back"
        )
    }
    return when (direction) {
        "up", "down" -> raw.copy(startX = startX, endX = startX)
        else -> raw.copy(startX = startX)
    }
}

package com.jsonui.testrunner.actions

/**
 * moveItem: where the finger presses and where it releases, or why the step fails before anything moves
 * (jsonui-test-runner schemas/actions.schema.json moveItemAction, specs/vectors/move-item.json — a byte copy in
 * src/test/resources/vectors, run by MoveItemPlanVectorsTest). The iOS and web drivers plan by the same vectors.
 *
 * The press is the handle's centre when the step gives `handle`, else the from row's centre; the release is in the
 * press's column, over the to row at 3/4 of its height going down and 1/4 going up — never on a centre, which the
 * libraries' reorder rule (jsonui-cli shared/core/reorder_vectors.json) does not count as passed and iOS 27's own
 * reordering does. Coordinates are screen pixels, y downward.
 */
object MoveItemPlan {
    /** press 1.0 s (KotlinJsonUI's drag begins after the long-press timeout, 0.4–0.5 s), hold 0.5 s before release */
    const val PRESS_MS = 1000L
    const val HOLD_MS = 500L

    /**
     * The vectors' edgeMargin, in the plan's own unit. On a device the driver passes it in dp
     * ([edgeMarginPx]): 100 px on a 420 dpi phone is 38 dp, inside KotlinJsonUI's own auto-scroll band (48 dp,
     * shared/core/reorder_vectors.json autoScroll) — the very scroll under a held finger the margin keeps clear of.
     */
    const val EDGE_MARGIN = 100.0

    /** The edge margin on a screen of [density] (px per dp): 100 dp. */
    fun edgeMarginPx(density: Float): Double = EDGE_MARGIN * density

    data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
        val left get() = x
        val top get() = y
        val right get() = x + width
        val bottom get() = y + height
        val centerX get() = x + width / 2
        val centerY get() = y + height / 2
    }

    /** The part of [a] inside [b] (an empty box at [a]'s corner when they do not meet). */
    fun intersect(a: Box, b: Box): Box {
        val left = maxOf(a.left, b.left); val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right); val bottom = minOf(a.bottom, b.bottom)
        return if (right <= left || bottom <= top) Box(a.x, a.y, 0.0, 0.0) else Box(left, top, right - left, bottom - top)
    }

    data class Point(val x: Double, val y: Double)

    sealed class Outcome {
        data class Gesture(val press: Point, val release: Point) : Outcome()
        data class Fail(val reason: String) : Outcome()
    }

    /** What the step gave and found for `handle`. */
    sealed class Handle {
        /** The step gives no handle: the row's centre is pressed. */
        object None : Handle()
        /** The step gives one; [box] is the element of that id found inside the from row, null when none was. */
        data class Given(val box: Box?) : Handle()
    }

    /** What can be judged before anything is looked for: the same row, a section other than 0. */
    fun early(from: Int, to: Int, section: Int): String? = when {
        from == to -> "same"
        section != 0 -> "section"
        else -> null
    }

    /**
     * [rows]: address -> the rects found for it (none or absent: not drawn; two or more: the address repeats).
     */
    fun plan(
        id: String, from: Int, to: Int, section: Int = 0, viewport: Box,
        rows: Map<String, List<Box>>, handle: Handle = Handle.None, edgeMargin: Double = EDGE_MARGIN
    ): Outcome {
        early(from, to, section)?.let { return Outcome.Fail(it) }
        fun row(n: Int): Pair<Box?, String?> {
            val address = "${id}_item_$n"
            val found = rows[address].orEmpty()
            if (found.size > 1) return null to "ambiguous:$address"
            val box = found.firstOrNull() ?: return null to "missing:$address"
            if (box.left < viewport.left || box.top < viewport.top || box.right > viewport.right || box.bottom > viewport.bottom) {
                return null to "offscreen:$address"
            }
            return box to null
        }
        val (fromRow, fromFail) = row(from)
        if (fromFail != null) return Outcome.Fail(fromFail)
        val (toRow, toFail) = row(to)
        if (toFail != null) return Outcome.Fail(toFail)
        val press = when (handle) {
            Handle.None -> Point(fromRow!!.centerX, fromRow.centerY)
            is Handle.Given -> handle.box?.let { Point(it.centerX, it.centerY) } ?: return Outcome.Fail("no-handle")
        }
        val release = Point(press.x, toRow!!.top + toRow.height * (if (to > from) 0.75 else 0.25))
        fun near(y: Double) = y - viewport.top < edgeMargin || viewport.bottom - y < edgeMargin
        if (near(press.y)) return Outcome.Fail("edge:${id}_item_$from")
        if (near(release.y)) return Outcome.Fail("edge:${id}_item_$to")
        return Outcome.Gesture(press, release)
    }

    /**
     * The list's margin, in dp: KotlinJsonUI's own auto-scroll band (48 dp from the edges of a list that scrolls
     * itself, shared/core/reorder_vectors.json autoScroll) and 8 dp more. A finger held nearer the list's edge than
     * the band scrolls the list under it, wherever the list is on the screen.
     */
    const val LIST_MARGIN_DP = 56.0

    /**
     * The plan on a device: the vectors' plan on the [screen] (its margin 100 dp, [screenMargin]), and the rows
     * inside the [list] that scrolls them, the press and the release at least [listMargin] from its top and bottom.
     * Compose reports a row's bounds clipped to the window only, so a row a list draws half under its own edge has
     * bounds past the list's: the list, not the screen, says it is not on screen. The failures keep the vectors'
     * order — the list's offscreen comes before the screen's edge.
     */
    fun planOnDevice(
        id: String, from: Int, to: Int, section: Int, screen: Box, list: Box?,
        rows: Map<String, List<Box>>, handle: Handle, screenMargin: Double, listMargin: Double
    ): Outcome {
        val onScreen = plan(id, from, to, section, screen, rows, handle, screenMargin)
        if (onScreen is Outcome.Fail && !onScreen.reason.startsWith("edge:")) return onScreen
        if (list != null) {
            val inList = plan(id, from, to, section, intersect(screen, list), rows, handle, listMargin)
            if (inList is Outcome.Fail) return inList
        }
        return onScreen
    }

    /** The sentence a failed plan gives the step (the iOS and web drivers say the same). */
    fun sentence(reason: String, from: Int, handle: String?): String {
        val kind = reason.substringBefore(':')
        val address = reason.substringAfter(':', "")
        return when (kind) {
            "same" -> "moveItem: 'from' and 'to' are the same row ($from): nothing to move"
            "section" -> "moveItem moves within section 0 (v1)"
            "ambiguous" -> "moveItem: '$address' is more than one element (a Collection of several sections: v1 moves within a single section)"
            "missing" -> "moveItem: '$address' was not found"
            "offscreen" -> "moveItem: '$address' is not on screen (scroll it into view first, e.g. scrollUntilVisible; the drag does not scroll)"
            "edge" -> "moveItem: '$address' is too near the screen's top or bottom (within ${EDGE_MARGIN.toInt()}: a list scrolls by itself there under a held finger); scroll it towards the middle first"
            "no-handle" -> "moveItem: the handle '${handle.orEmpty()}' was not found inside the row (from $from)"
            else -> "moveItem: $reason"
        }
    }

    /**
     * The finger's path from [press] to [release] for a slow drag: one point per [frameMs], at most [speedPxPerS],
     * the release point last. The press and the hold are the caller's (they stay in place).
     */
    fun path(press: Point, release: Point, speedPxPerS: Double, frameMs: Long = 16L): List<Point> {
        val distance = kotlin.math.abs(release.y - press.y) + kotlin.math.abs(release.x - press.x)
        val frames = maxOf(1, kotlin.math.ceil(distance / (speedPxPerS * frameMs / 1000.0)).toInt())
        return (1..frames).map { k ->
            val t = k.toDouble() / frames
            Point(press.x + (release.x - press.x) * t, press.y + (release.y - press.y) * t)
        }
    }
}

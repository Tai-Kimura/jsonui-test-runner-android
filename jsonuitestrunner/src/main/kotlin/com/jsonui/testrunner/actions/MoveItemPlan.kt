package com.jsonui.testrunner.actions

/**
 * moveItem: where the finger presses and where it releases, or why the step fails before anything moves, and after
 * the gesture whether the list scrolled under the finger (jsonui-test-runner schemas/actions.schema.json
 * moveItemAction, specs/vectors/move-item.json — a byte copy in src/test/resources/vectors, run by
 * MoveItemPlanVectorsTest). The iOS and web drivers plan by the same vectors.
 *
 * The plan's unit on Android is the dp (the driver divides the screen's pixels by the density before it plans and
 * multiplies the press and the release back), so a margin is the same distance under the finger on every platform.
 *
 * The press is the handle's centre when the step gives `handle`, else the from row's centre; the release is in the
 * press's column, over the to row at 3/4 of its height going down and 1/4 going up — never on a centre, which the
 * libraries' reorder rule (jsonui-cli shared/core/reorder_vectors.json) does not count as passed and iOS 27's own
 * reordering does.
 */
object MoveItemPlan {
    /** press 1.0 s (KotlinJsonUI's drag begins after the long-press timeout, 0.4–0.5 s), hold 0.5 s before release */
    const val PRESS_MS = 1000L
    const val HOLD_MS = 500L

    /**
     * The vectors' margins, in dp: the press and the release at least [EDGE_MARGIN] from the screen's top and bottom
     * and [LIST_MARGIN] from those of the list's visible part — each counted only towards a side the scroller can
     * still scroll. KotlinJsonUI's own auto-scroll band is 48 dp from the list's edges
     * (shared/core/reorder_vectors.json autoScroll).
     */
    const val EDGE_MARGIN = 100.0
    const val LIST_MARGIN = 56.0

    data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
        val left get() = x
        val top get() = y
        val right get() = x + width
        val bottom get() = y + height
        val centerX get() = x + width / 2
        val centerY get() = y + height / 2

        /** This box in another unit: every coordinate divided by [d] (px -> dp with the density). */
        fun div(d: Double) = Box(x / d, y / d, width / d, height / d)

        fun contains(b: Box) = b.left >= left && b.top >= top && b.right <= right && b.bottom <= bottom
    }

    data class Point(val x: Double, val y: Double)

    /** The part of [a] inside [b] (an empty box at [a]'s corner when they do not meet). */
    fun intersect(a: Box, b: Box): Box {
        val left = maxOf(a.left, b.left); val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right); val bottom = minOf(a.bottom, b.bottom)
        return if (right <= left || bottom <= top) Box(a.x, a.y, 0.0, 0.0) else Box(left, top, right - left, bottom - top)
    }

    /**
     * A row whose bounds end at the window's top or bottom (within 0.5): Compose clipped it there, so it is taken
     * as running 1 past that edge — not wholly on screen (nor a witness for the check after the gesture).
     */
    fun pastWindowEdge(row: Box, window: Box): Box = when {
        kotlin.math.abs(row.bottom - window.bottom) <= 0.5 -> Box(row.x, row.y, row.width, row.height + 1.0)
        kotlin.math.abs(row.top - window.top) <= 0.5 -> Box(row.x, row.y - 1.0, row.width, row.height + 1.0)
        else -> row
    }

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
     * [list]: the rect of the scrolling list that holds the rows, null when the window scrolls them; its visible part
     * is list ∩ viewport. [canScrollUp] / [canScrollDown]: whether the scroller can still scroll that way — the edge
     * rule (both margins) applies on a side only when it can.
     */
    fun plan(
        id: String, from: Int, to: Int, section: Int = 0, viewport: Box, list: Box? = null,
        rows: Map<String, List<Box>>, handle: Handle = Handle.None,
        canScrollUp: Boolean = true, canScrollDown: Boolean = true,
        edgeMargin: Double = EDGE_MARGIN, listMargin: Double = LIST_MARGIN
    ): Outcome {
        early(from, to, section)?.let { return Outcome.Fail(it) }
        val visible = list?.let { intersect(viewport, it) }
        fun row(n: Int): Pair<Box?, String?> {
            val address = "${id}_item_$n"
            val found = rows[address].orEmpty()
            if (found.size > 1) return null to "ambiguous:$address"
            val box = found.firstOrNull() ?: return null to "missing:$address"
            if (!viewport.contains(box) || (visible != null && !visible.contains(box))) return null to "offscreen:$address"
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
        fun near(y: Double): Boolean {
            val up = canScrollUp && (y - viewport.top < edgeMargin || (visible != null && y - visible.top < listMargin))
            val down = canScrollDown && (viewport.bottom - y < edgeMargin || (visible != null && visible.bottom - y < listMargin))
            return up || down
        }
        if (near(press.y)) return Outcome.Fail("edge:${id}_item_$from")
        if (near(release.y)) return Outcome.Fail("edge:${id}_item_$to")
        return Outcome.Gesture(press, release)
    }

    /**
     * The row checked after the gesture: one outside the moved range [min(from, to), max(from, to)] wholly inside
     * [visible], looked for at lo-1, hi+1, lo-2, hi+2; null when there is none (the check is skipped).
     */
    fun witness(from: Int, to: Int, found: Map<Int, Box>, visible: Box): Int? {
        val lo = minOf(from, to); val hi = maxOf(from, to)
        return listOf(lo - 1, hi + 1, lo - 2, hi + 2).firstOrNull { n -> n >= 0 && found[n]?.let { visible.contains(it) } == true }
    }

    /**
     * Whether the witness row moved up or down by more than 1, or is no longer found ([after] null: a lazy list
     * scrolled it away): the list scrolled under the finger. Only y is compared: a list scrolls up and down, and a
     * row's x moving (a redraw) said "moved by 0" when it was counted.
     */
    fun scrolled(before: Box, after: Box?): Boolean = after == null || kotlin.math.abs(after.y - before.y) > 1.0

    /**
     * The sentence of a scroll during the drag. [after] null: the witness row is gone from the tree after the gesture
     * (a lazy list scrolled it away) — said as moved off the screen.
     */
    fun scrolledSentence(address: String, before: Box, after: Box?): String {
        val moved = after?.let { "moved by ${kotlin.math.round(kotlin.math.abs(it.y - before.y)).toLong()}" } ?: "moved off the screen"
        return "moveItem: the list scrolled during the drag ('$address' $moved); the row may have landed elsewhere — " +
            "scroll the rows away from the list's edges first"
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
            "edge" -> "moveItem: '$address' is too near the top or bottom of the screen (${EDGE_MARGIN.toInt()}) or of its list (${LIST_MARGIN.toInt()}): the list scrolls by itself there under a held finger; scroll it towards the middle first"
            "no-handle" -> "moveItem: the handle '${handle.orEmpty()}' was not found inside the row (from $from)"
            else -> "moveItem: $reason"
        }
    }

    /**
     * The finger's path from [press] to [release] for a slow drag: one point per [frameMs], at most [speedPerS],
     * the release point last. The press and the hold are the caller's (they stay in place).
     */
    fun path(press: Point, release: Point, speedPerS: Double, frameMs: Long = 16L): List<Point> {
        val distance = kotlin.math.abs(release.y - press.y) + kotlin.math.abs(release.x - press.x)
        val frames = maxOf(1, kotlin.math.ceil(distance / (speedPerS * frameMs / 1000.0)).toInt())
        return (1..frames).map { k ->
            val t = k.toDouble() / frames
            Point(press.x + (release.x - press.x) * t, press.y + (release.y - press.y) * t)
        }
    }
}

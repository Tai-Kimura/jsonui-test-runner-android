// A copy of KotlinJsonUI library/src/main/kotlin/com/kotlinjsonui/components/CollectionReorderRules.kt at ae5fb38 (branch
// feat/collection-reorder), the package changed: the rows moveItem's on-device arm drags are the library's own.
// compose-probe cannot depend on KotlinJsonUI (its Kotlin and Compose are newer than this build's).
package com.jsonui.testrunner.composeprobe.reorder

/**
 * The rule a reorderable Collection follows (`reorderable` / `onReorder`; ticket jui-collection-drag-to-reorder), as
 * plain functions. The same rule is written for iOS (SwiftJsonUI CollectionReorderRule) and web, and jsonui-cli's
 * shared/core/reorder_vectors.json pins all three; the names and answers here are SwiftJsonUI's, one to one.
 *
 * Positions are in the rows' content coordinates (a scroll moves the rows and the finger alike, so it is part of the
 * drag's dy). Indexes are within one section; `to` is the index the row holds AFTER the move.
 */
object CollectionReorderRules {

    /** The top and bottom of one row, in content coordinates. */
    data class RowSpan(val top: Float, val bottom: Float) {
        val center: Float get() = (top + bottom) / 2
    }

    /**
     * Where the row grabbed at [from] lands while it is dragged [dy]: past every neighbour whose centre its own centre
     * has crossed (exactly on a centre is not past it), and never onto or past a locked row.
     */
    fun target(from: Int, dy: Float, rows: List<RowSpan>, locked: Set<Int>): Int {
        if (from !in rows.indices) return from
        val center = rows[from].center + dy
        var to = from
        if (dy > 0) {
            var j = from + 1
            while (j < rows.size && j !in locked && center > rows[j].center) { to = j; j++ }
        } else if (dy < 0) {
            var j = from - 1
            while (j >= 0 && j !in locked && center < rows[j].center) { to = j; j-- }
        }
        return to
    }

    /**
     * Whether letting go commits the move: the finger is inside the Collection's container (the part that holds the
     * rows, its header and footer included). Above the first row the row lands first, below the last row last
     * ([target] gives that); only outside the container is the move cancelled (ruling 2026-11-13, iOS 27's reading).
     * The container's bottom edge itself is outside it (reorder_vectors.json, release).
     */
    fun releaseCommits(fingerY: Float, containerTop: Float, containerBottom: Float): Boolean =
        fingerY >= containerTop && fingerY < containerBottom

    /** Whether the row at [from] can be picked up at all: a locked row never is. */
    fun canPickUp(from: Int, locked: Set<Int>): Boolean = from !in locked

    /**
     * Why a move from [from] to [to] in [count] rows is refused, or null when it is a move: "no-move" (to == from),
     * "out-of-range" (either index outside the rows), "locked" (the row is locked, or the move passes a locked row).
     * The library's own drag and the accessibility moves never make a refused move; this is the table's reading of
     * one, as any move reported to the view model must pass it.
     */
    fun refusal(count: Int, from: Int, to: Int, locked: Set<Int>): String? = when {
        from !in 0 until count || to !in 0 until count -> "out-of-range"
        crossesLocked(from, to, locked) -> "locked"
        from == to -> "no-move"
        else -> null
    }

    /** `onReorder` is called only for a real move. */
    fun shouldReport(from: Int, to: Int): Boolean = from != to

    /** Whether a move crosses a locked row (or starts on one). */
    fun crossesLocked(from: Int, to: Int, locked: Set<Int>): Boolean {
        if (from in locked) return true
        if (from == to) return false
        val range = if (from < to) (from + 1)..to else to..(from - 1)
        return range.any { it in locked }
    }

    /** Where a move ends short of the first locked row in its way; null when the row itself is locked. */
    fun clampedTo(from: Int, to: Int, locked: Set<Int>): Int? {
        if (from in locked) return null
        if (to > from) locked.filter { it > from && it <= to }.minOrNull()?.let { return it - 1 }
        if (to < from) locked.filter { it < from && it >= to }.maxOrNull()?.let { return it + 1 }
        return to
    }

    /** The edge band and the fastest auto-scroll, in dp and dp a second (SwiftJsonUI's points). */
    const val AUTO_SCROLL_BAND = 48f
    const val AUTO_SCROLL_MAX_SPEED = 800f

    /**
     * The auto-scroll velocity for a finger at [fingerY] in a viewport from [viewportTop] to [viewportBottom] (all in
     * dp): negative up, positive down, 0 outside both bands; faster the deeper into the band. In a viewport shorter
     * than two bands the nearer edge wins.
     */
    fun autoScrollVelocity(fingerY: Float, viewportTop: Float, viewportBottom: Float): Float {
        val band = AUTO_SCROLL_BAND
        val fromTop = fingerY - viewportTop
        val fromBottom = viewportBottom - fingerY
        val inTop = fromTop < band
        val inBottom = fromBottom < band
        if (!inTop && !inBottom) return 0f
        val upward = inTop && (!inBottom || fromTop <= fromBottom)
        val depth = if (upward) band - maxOf(0f, fromTop) else band - maxOf(0f, fromBottom)
        val speed = AUTO_SCROLL_MAX_SPEED * minOf(1f, maxOf(0f, depth / band))
        return if (upward) -speed else speed
    }

    /** The accessibility moves a row offers: up unless first, the row above locked, or locked itself; down likewise. */
    fun accessibilityMoves(index: Int, count: Int, locked: Set<Int>): Pair<Boolean, Boolean> {
        if (index in locked || index < 0 || index >= count) return false to false
        return (index > 0 && (index - 1) !in locked) to (index < count - 1 && (index + 1) !in locked)
    }
}

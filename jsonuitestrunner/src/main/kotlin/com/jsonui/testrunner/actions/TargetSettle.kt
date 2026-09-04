package com.jsonui.testrunner.actions

import kotlin.math.abs

/**
 * Pure geometry and timing behind two questions a scroll-then-tap pair has
 * to answer (JVM-tested; no device):
 *
 *  1. "Has the target stopped moving?" — `scrollUntilVisible` used to return
 *     the instant the target's node existed in the tree. With a container
 *     given, the scroll is an accessibility ACTION_SCROLL_FORWARD, which
 *     Compose animates, and the one `waitForIdle` in that loop runs BEFORE
 *     the lookup and waits on accessibility events Compose does not send
 *     under a bare UiAutomator (isEnabled=false, measured 2026-09-04). So the
 *     next step's tap could land on a target still sliding; Compose cancels
 *     a press whose release lands outside the node, and the press itself
 *     stops the animation — leaving the target parked exactly where the
 *     failed tap caught it (consumer capture: 23% inside the viewport at its
 *     top edge). The settle rule is deliberately simple: two consecutive
 *     bounds samples that agree.
 *
 *  2. "How much of the target is actually on screen?" — reported in the
 *     failure text of tap-then-expect actions so a capture can separate
 *     "tapped a sliver" from "tapped and nothing happened".
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height
    val isEmpty: Boolean get() = area == 0L
    fun toShortString(): String = "[$left,$top][$right,$bottom]"
}

object TargetSettle {
    /** Interval between bounds samples while waiting for the target to settle. */
    const val SAMPLE_INTERVAL_MS = 100L

    /** Upper bound on the settle wait; a target still moving after this is logged, not failed. */
    const val BUDGET_MS = 2_000L

    /** Settled = the last two samples agree. Fewer than two samples is never settled. */
    fun settled(samples: List<Box>): Boolean =
        samples.size >= 2 && samples[samples.size - 1] == samples[samples.size - 2]

    /** Manhattan displacement of the top-left corner between the first and last sample. */
    fun movedPx(samples: List<Box>): Int {
        if (samples.size < 2) return 0
        val first = samples.first()
        val last = samples.last()
        return abs(last.left - first.left) + abs(last.top - first.top)
    }

    /**
     * Percentage (0..100) of [full] covered by [visible]; 0 when either is
     * missing or [full] is empty, and never above 100 even if a clipped rect
     * is reported larger than its unclipped one.
     */
    fun visiblePercent(visible: Box?, full: Box?): Int {
        if (visible == null || full == null || full.isEmpty) return 0
        return ((visible.area * 100) / full.area).toInt().coerceIn(0, 100)
    }

    /**
     * One line for a failure message: how much of the target was on screen
     * and what clipped it. [clipper] is the nearest scrollable ancestor as
     * (id, bounds), when one was found.
     */
    fun describe(id: String, visible: Box?, full: Box?, clipper: Pair<String, Box>?): String {
        if (visible == null && full == null) return "target '$id' was not in the tree afterwards"
        val pct = visiblePercent(visible, full)
        val sb = StringBuilder("target '$id' visible $pct%")
        if (visible != null) sb.append(" (visible ${visible.toShortString()}")
        if (full != null) sb.append(if (visible != null) " of ${full.toShortString()}" else " (bounds ${full.toShortString()}")
        if (visible != null || full != null) sb.append(")")
        if (clipper != null) sb.append(", clipped by scrollable '${clipper.first}' ${clipper.second.toShortString()}")
        return sb.toString()
    }

    /** One line per scrollUntilVisible so a consumer can read whether targets ever move after being found. */
    fun settleLine(id: String, samples: List<Box>, elapsedMs: Long): String =
        "scrollUntilVisible '$id': settled=${settled(samples)} after ${elapsedMs}ms, " +
            "moved ${movedPx(samples)}px over ${samples.size} sample(s)" +
            (samples.lastOrNull()?.let { ", resting ${it.toShortString()}" } ?: "")
}

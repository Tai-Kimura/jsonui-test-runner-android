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

    /**
     * Which motion a settle wait is waiting out. `scrollUntilVisible` now
     * prints up to three settle lines per call, and without a label they are
     * indistinguishable — a face reading the log cannot tell "the target was
     * still sliding when we found it" from "our own clearance swipe was still
     * flinging".
     *
     * 📌 Deliberately coarse. The question a reader asks is WHICH MOTION this
     * waited out, and these three are the three motions. The call sites are
     * finer than that (primary leg / reverse leg both arrive via [ON_ARRIVAL])
     * and labelling at THAT grain would invent distinctions the question does
     * not have — see the sibling note on [UnstickVia].
     */
    const val ON_ARRIVAL = "on-arrival"
    const val AFTER_UNSTICK = "after-unstick"
    const val AFTER_REVERT = "after-revert"

    /**
     * One line per settle wait so a consumer can read whether targets ever
     * move after being found.
     *
     * 🚨 THE PREFIX `scrollUntilVisible '<id>'` IS SHARED WITH THE iOS DRIVER,
     * which spells its scroll failure the same way and has no settle concept
     * at all (ScrollDiagnosis.swift: "scrollUntilVisible '\(id)': scrolled
     * both ways and it is still not hittable"). As of 1.15.0 only ANDROID puts
     * a bracketed phase between the id and the colon, so a runbook that greps
     * for the id followed immediately by a colon now matches iOS and misses
     * Android — and reads as "the output disappeared" rather than "the format
     * moved". Grep for the id alone, or for `[after-unstick]` by name.
     *
     * Found by a lane whose search window covered both drivers; this repo's
     * own check had looked only at Android and at the CLI.
     */
    fun settleLine(id: String, phase: String, samples: List<Box>, elapsedMs: Long): String =
        "scrollUntilVisible '$id' [$phase]: settled=${settled(samples)} after ${elapsedMs}ms, " +
            "moved ${movedPx(samples)}px over ${samples.size} sample(s)" +
            (samples.lastOrNull()?.let { ", resting ${it.toShortString()}" } ?: "")
}

/**
 * Which EXIT of `scrollUntilVisible` ran the trailing-edge clearance rule.
 *
 * 1.14.0 shipped that rule on one exit of three: the early return taken when
 * the target was ALREADY visible on entry. The two scroll legs — which return
 * the instant the target appears, i.e. the instant it has entered from the
 * trailing edge — never ran it. That is the shape the rule was written for,
 * and it was the one shape the rule could not reach.
 *
 * 📌 Two values, not three. Both scroll legs (primary and reverse) report
 * [SCROLLED]: the question a reader asks is "did the rule fire on a target I
 * scrolled to, or on one that was already there", and splitting the legs
 * would be an identifier finer than the question — which manufactures
 * distinctions rather than answering it.
 */
object UnstickVia {
    /** The target was already in the projection when the step began. */
    const val ENTRY = "entry"

    /** The target appeared during a scroll leg (primary or reverse). */
    const val SCROLLED = "scrolled"
}

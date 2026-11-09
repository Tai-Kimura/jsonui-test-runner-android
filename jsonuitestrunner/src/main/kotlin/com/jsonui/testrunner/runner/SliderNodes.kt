package com.jsonui.testrunner.runner

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A slider's accessibility node, read fresh: the one with the id (inside
 * `within` when given) that carries a RangeInfo.
 *
 * A UiObject2 has nothing to read here — a Compose Slider's text and content
 * description are null; its value, bounds and type are on the node's
 * RangeInfo. The node is [AccessibilityNodeInfo.refresh]ed before it is read:
 * measured 2026-11-09 (Material3 Slider, API 35), a node fetched right after
 * ACTION_SET_PROGRESS still held the previous value, every time, and the
 * refreshed one held the new one.
 */
object SliderNodes {
    data class Reading(val node: AccessibilityNodeInfo, val min: Double, val max: Double, val current: Double)

    /** The node with [id] that has a RangeInfo, or null. */
    fun find(id: String, within: String?): AccessibilityNodeInfo? = runCatching {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return@runCatching null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to (within == null))
        while (queue.isNotEmpty()) {
            val (node, inside) = queue.removeFirst()
            if (inside && node.viewIdResourceName == id && node.rangeInfo != null) return@runCatching node
            val childrenInside = inside || node.viewIdResourceName == within
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to childrenInside) }
        }
        null
    }.getOrNull()

    /** Whether some node with [id] is there, slider or not (to tell "not a slider" from "not there"). */
    fun anyWithId(id: String, within: String?): Boolean = runCatching {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return@runCatching false
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to (within == null))
        while (queue.isNotEmpty()) {
            val (node, inside) = queue.removeFirst()
            if (inside && node.viewIdResourceName == id) return@runCatching true
            val childrenInside = inside || node.viewIdResourceName == within
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to childrenInside) }
        }
        false
    }.getOrDefault(false)

    private fun decimal(f: Float): Double = f.toString().toDouble()

    /** The slider's bounds and value now, or null when there is no slider with [id]. */
    fun read(id: String, within: String?): Reading? {
        NodeCache.clear()
        val node = find(id, within) ?: return null
        node.refresh()
        val range = node.rangeInfo ?: return null
        // RangeInfo holds floats: read each as the shortest decimal that is
        // that float (0.73f is 0.73, not 0.7300000190734863), so a value the
        // test wrote reads back as written.
        return Reading(node, decimal(range.min), decimal(range.max), decimal(range.current))
    }
}

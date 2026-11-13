package com.jsonui.testrunner.runner

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.jsonui.testrunner.actions.MoveItemPlan

/**
 * moveItem's reads of the accessibility tree: every node of an address with its bounds on screen, and the handle's
 * node inside the from row. A row's address (`<id>_item_<n>`) that repeats — a Collection of several sections — is
 * two or more nodes, which the plan refuses, so this reads them all rather than the first one a find returns.
 */
object MoveItemNodes {
    private fun root(): AccessibilityNodeInfo? =
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow }.getOrNull()

    private fun box(node: AccessibilityNodeInfo): MoveItemPlan.Box {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return MoveItemPlan.Box(r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble())
    }

    /** Every node tagged with one of [addresses] (inside [within] when given), by address. */
    fun rows(addresses: List<String>, within: String?): Map<String, List<AccessibilityNodeInfo>> = runCatching {
        val found = addresses.associateWith { mutableListOf<AccessibilityNodeInfo>() }
        val start = root() ?: return@runCatching found
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(start to (within == null))
        while (queue.isNotEmpty()) {
            val (node, inside) = queue.removeFirst()
            val tag = node.viewIdResourceName
            if (inside && tag != null) found[tag]?.add(node)
            val childrenInside = inside || tag == within
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to childrenInside) }
        }
        found
    }.getOrDefault(addresses.associateWith { emptyList() })

    fun boxes(nodes: Map<String, List<AccessibilityNodeInfo>>): Map<String, List<MoveItemPlan.Box>> =
        nodes.mapValues { (_, list) -> list.map(::box) }

    /**
     * The bounds of the nearest scrolling ancestor of [row] (the list that carries it), or null when none scrolls.
     * Compose reports a node's bounds clipped to what is drawn: a row half under its list's edge reads as a shorter
     * row inside the screen, and a row wholly under it is not in the tree. Measured against the list's own bounds,
     * a clipped row touches the list's edge — too near it, by the plan's margin.
     */
    fun scrollContainer(row: AccessibilityNodeInfo): MoveItemPlan.Box? = runCatching {
        var node: AccessibilityNodeInfo? = row.parent
        while (node != null) {
            if (node.isScrollable) return@runCatching box(node)
            node = node.parent
        }
        null
    }.getOrNull()

    /** The node tagged [handle] inside [row] (the row itself excluded), or null. */
    fun handle(row: AccessibilityNodeInfo, handle: String): MoveItemPlan.Box? = runCatching {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until row.childCount) row.getChild(i)?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.viewIdResourceName == handle) return@runCatching box(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        null
    }.getOrNull()
}

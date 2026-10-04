package com.jsonui.testrunner.runner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

/**
 * What a timed-out find for a resource-id says about itself: how long it
 * waited on each clock ([Deadline.describe]), and what the same query sees
 * the moment it gave up — how many nodes carry the id, with their bounds and
 * whether they are enabled.
 *
 * A consumer's `waitFor` failed while the failure-time dump held the id,
 * visible and enabled (ticket android-driver-misses-a-present-resource-id-
 * for-25s), and the message could say neither how long the wait had really
 * lasted nor whether the find would have matched at that instant. Each line
 * here answers one of those.
 *
 * Runs only on the failure path. Never throws: the failure is what is being
 * reported.
 */
object FindTimeoutReport {

    /** At most this many nodes are listed; the count is always the full one. */
    private const val NODE_LIMIT = 5

    fun render(device: UiDevice, id: String, deadline: Deadline): String {
        val census = runCatching {
            val nodes = device.findObjects(By.res(id))
            val listed = nodes.take(NODE_LIMIT).joinToString("; ") { node ->
                val b = node.visibleBounds
                "${node.className ?: "?"} bounds=[${b.left},${b.top}][${b.right},${b.bottom}] enabled=${node.isEnabled}"
            }
            "the same find right after giving up: ${nodes.size} node(s) with resource-id '$id'" +
                if (nodes.isEmpty()) "" else " — $listed" +
                    if (nodes.size > NODE_LIMIT) " (+${nodes.size - NODE_LIMIT} more)" else ""
        }.getOrElse { e -> "the same find right after giving up failed: ${e.javaClass.simpleName}: ${e.message}" }
        return "  ${deadline.describe()}\n  $census"
    }
}

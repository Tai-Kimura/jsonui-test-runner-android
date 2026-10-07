package com.jsonui.testrunner.runner

import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Failure-path instrumentation for a missing element (see ProjectionReport).
 *
 * Runs ONLY when a lookup has already failed, so a green run pays nothing.
 * Never throws: a probe that fails takes the failure message down with it,
 * and the failure is the thing being reported.
 */
object ProjectionProbe {

    /** Cap the walk: a wedged tree is exactly when this runs, and an
     *  unbounded traversal there would turn a failed step into a hang. */
    private const val MAX_NODES = 20000

    /**
     * Every `viewIdResourceName` the accessibility projection currently
     * exposes FOR THE APP UNDER TEST, read as an attribute.
     *
     * The package filter is not cosmetic: `uiAutomation.windows` spans the
     * system UI, the launcher and any other app on screen, so an unfiltered
     * set makes "present in the projection but not in the app's tree" true
     * of ids that were never the app's. Compose test tags carry no package
     * prefix, so filtering by the id's spelling cannot do it — the node's
     * own packageName can.
     */
    fun ownPackageIds(automation: UiAutomation, packageName: String): Set<String> = runCatching {
        val out = LinkedHashSet<String>()
        var budget = MAX_NODES
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        // `windows` is empty unless the a11y service is asked for it. Measured
        // 2026-09-04 with a positive control (a view with a real resource id,
        // on screen): without this flag the walk returned ZERO ids and every
        // verdict came back STILL_MISSING — a blind detector that would have
        // reported "the app side never projected it" about anything. UiDevice
        // sets the flag for its own queries; a probe reaching for the raw
        // UiAutomation must ask for itself.
        runCatching {
            val info = automation.serviceInfo
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }
        automation.windows.forEach { window -> window.root?.let { queue.add(it) } }
        // Even with the flag, `windows` can come back empty (no interactive
        // window yet, a display without one). The active root is then the
        // only view of the projection there is — and an empty set from here
        // must mean "nothing to see", never "nobody looked".
        if (queue.isEmpty()) {
            automation.rootInActiveWindow?.let { queue.add(it) }
        }
        while (queue.isNotEmpty() && budget > 0) {
            val node = queue.removeFirst()
            budget--
            if (node.packageName == packageName) {
                node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        out
    }.getOrDefault(emptySet())

    private fun hasId(node: AccessibilityNodeInfo, id: String): Boolean =
        node.viewIdResourceName?.let { it == id || it.endsWith(":id/$id") } == true

    private fun canScroll(node: AccessibilityNodeInfo, action: AccessibilityNodeInfo.AccessibilityAction): Boolean =
        node.actionList.any { it.id == action.id }

    private fun read(node: AccessibilityNodeInfo, source: ContainerSource) = ResolvedContainer(
        id = node.viewIdResourceName,
        source = source,
        scrollable = node.isScrollable,
        canScrollForward = canScroll(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD),
        canScrollBackward = canScroll(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD),
    )

    /**
     * The target's node(s) in the app's raw tree, and the scroll container
     * that decides whether it may lie off-screen — searched in this order:
     * the target's nearest scrollable ancestor (only reachable when the
     * target is in the tree: Views), the previous scroll step's container,
     * then the first scrollable on the app surface. Every source is named in
     * the report, because the third is a guess about which scroll mattered.
     */
    fun targetAndContainer(
        automation: UiAutomation,
        packageName: String,
        id: String,
        previousContainerId: String?
    ): Pair<TargetPresence, ResolvedContainer?> {
        val targets = mutableListOf<AccessibilityNodeInfo>()
        var previous: AccessibilityNodeInfo? = null
        var surface: AccessibilityNodeInfo? = null
        var budget = MAX_NODES
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.takeIf { it.packageName == packageName }?.let { queue.add(it) }
        if (queue.isEmpty()) {
            automation.windows.forEach { w -> w.root?.takeIf { it.packageName == packageName }?.let { queue.add(it) } }
        }
        while (queue.isNotEmpty() && budget > 0) {
            val node = queue.removeFirst()
            budget--
            if (hasId(node, id)) targets += node
            if (previous == null && previousContainerId != null && hasId(node, previousContainerId)) previous = node
            if (surface == null && node.isScrollable) surface = node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        val shown = targets.firstOrNull { it.isVisibleToUser } ?: targets.firstOrNull()
        val presence = TargetPresence(
            inTree = targets.isNotEmpty(),
            visibleToUser = targets.any { it.isVisibleToUser },
            bounds = shown?.let { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) }.toShortString() },
        )
        var ancestor = shown?.parent
        while (ancestor != null && !ancestor.isScrollable) ancestor = ancestor.parent
        val container = ancestor?.let { read(it, ContainerSource.TARGET_ANCESTOR) }
            ?: previous?.let { read(it, ContainerSource.PREVIOUS_STEP) }
            ?: surface?.let { read(it, ContainerSource.SURFACE) }
        return presence to container
    }

    /**
     * Measure, drop UiAutomator's cache, measure, resync the a11y service,
     * measure. The two recovery steps are applied and reported SEPARATELY
     * so the next occurrence says which one (if either) was enough — the
     * question a consumer report could not answer because the shape is
     * intermittent.
     */
    fun report(id: String): String = runCatching {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val pkg = instrumentation.targetContext.packageName

        val before = ownPackageIds(automation, pkg)
        // API 34+; older levels simply skip this arm and the verdict then
        // distinguishes the resync only.
        NodeCache.clear(automation)
        val afterClearCache = ownPackageIds(automation, pkg)
        runCatching { automation.serviceInfo = automation.serviceInfo }
        val afterServiceResync = ownPackageIds(automation, pkg)

        val (presence, container) = targetAndContainer(automation, pkg, id, ScrollHistory.previousContainerId)
        ProjectionReport.render(id, before, afterClearCache, afterServiceResync,
            container = container, presence = presence)
    }.getOrElse { e -> "projection probe for '$id' failed: ${e.javaClass.simpleName}: ${e.message}" }
}

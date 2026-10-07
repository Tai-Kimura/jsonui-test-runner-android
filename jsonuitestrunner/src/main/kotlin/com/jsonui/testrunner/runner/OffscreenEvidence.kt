package com.jsonui.testrunner.runner

/**
 * What the failure path can read about a missing or hidden id, beyond the
 * id census (ProjectionProbe): the target's own node, and the scroll
 * container that decides whether it may lie off-screen.
 *
 * Until 1.15.12 nothing resolved a container on the failure path, so
 * [OffscreenPossibility] was always UNKNOWN in production (ticket
 * android-driver-scroll-until-visible-stops-at-two-different-offsets-for-the-
 * same-target). Pure data, so ProjectionReport can be tested without a device.
 */

/**
 * The target's node in the raw tree, off-screen ones included.
 *
 * A Views ScrollView keeps its off-screen children in the tree with
 * `isVisibleToUser=false`, so a hidden View is PRESENT in the census while
 * UiAutomator cannot find it. Measured 2026-10-08 (FormFooterOnDeviceTest):
 * a label behind a fixed footer, `bounds=[0,2202][1080,2190]`, verdict
 * PRESENT_AT_FAILURE with no word about why it was not found. Compose does
 * not project off-screen nodes, so there the shape is STILL_MISSING instead.
 */
data class TargetPresence(val inTree: Boolean, val visibleToUser: Boolean, val bounds: String?) {
    /** In the tree, but no node with the id is visible to the user. */
    val hidden: Boolean get() = inTree && !visibleToUser
}

/** Where a resolved container came from, in the search order. */
enum class ContainerSource(val label: String) {
    TARGET_ANCESTOR("the target's scrollable ancestor"),
    PREVIOUS_STEP("the previous scroll step's container"),
    SURFACE("the first scrollable on the app surface"),
}

/**
 * A scroll container as the failure path read it. The two directions are
 * reported separately because "room" in either one keeps the off-screen case
 * open: a target can lie above the viewport as well as below it.
 */
data class ResolvedContainer(
    val id: String?,
    val source: ContainerSource,
    val scrollable: Boolean,
    val canScrollForward: Boolean,
    val canScrollBackward: Boolean,
) {
    val possibility: OffscreenPossibility
        get() = OffscreenPossibility.of(scrollable, atEnd = !canScrollForward && !canScrollBackward)

    fun describe(): String =
        "scroll container: '${id ?: "<no id>"}' (from ${source.label}) scrollable=$scrollable " +
            "canScrollForward=$canScrollForward canScrollBackward=$canScrollBackward"
}

/**
 * The container the last scroll step used, for the second step of the
 * failure path's search. Set by ActionExecutor (`scrollUntilVisible`'s
 * `container`, `scroll`'s `id`); cleared at the start of each case so a
 * container from an earlier case is never reported as "the previous step's".
 */
object ScrollHistory {
    @Volatile
    var previousContainerId: String? = null

    fun clear() {
        previousContainerId = null
    }
}

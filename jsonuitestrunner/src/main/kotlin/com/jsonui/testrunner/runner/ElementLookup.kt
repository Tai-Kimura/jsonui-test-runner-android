package com.jsonui.testrunner.runner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * The one place an element id is resolved, with or without a step's `within`.
 *
 * Without `within` (null) every lookup is exactly what the driver did before:
 * `device.findObject(By.res(id))`, `findObjects`, and the [ZeroSizeNodes] /
 * [HiddenNodes] reads of the whole semantics tree.
 *
 * With `within` the id names an element INSIDE another one — normally a
 * collection cell `<collectionId>_item_<n>`, whose children repeat the same
 * ids in every cell. The container is found by its own id (first match, as
 * any id is), and the target among the container's descendants
 * (`container.findObject(By.res(id))`). Measured on KotlinJsonUI Compose
 * Dynamic (testTagsAsResourceId): the tagged children of a cell are its
 * descendants with their own text, also when the cell root is clickable and
 * when the cell has one child.
 *
 * ⚠️ A cell exists in the tree only while it is drawn (a lazy list composes
 * the visible rows), so a missing container usually means "not scrolled to
 * yet" — [Within.containerNotFound] says so.
 *
 * The 0 x 0 fallback ([ZeroSizeNodes]) and `hidden` ([HiddenNodes]) are
 * scoped the same way: only nodes under a semantics node tagged `within`.
 */
class ElementLookup(private val device: UiDevice) {

    /** The container a scoped lookup searches in, or null when it is not in the tree. */
    fun container(within: String): UiObject2? = device.findObject(By.res(within))

    /** First element with [id] (inside [within] when given), or null. */
    fun find(id: String, within: String?): UiObject2? {
        if (within == null) return device.findObject(By.res(id))
        return try {
            container(within)?.findObject(By.res(id))
        } catch (e: StaleObjectException) {
            // The container was replaced between the two finds: not there now.
            null
        }
    }

    /** Every element with [id] (inside [within] when given). */
    fun findAll(id: String, within: String?): List<UiObject2> {
        if (within == null) return device.findObjects(By.res(id))
        return try {
            container(within)?.findObjects(By.res(id)) ?: emptyList()
        } catch (e: StaleObjectException) {
            emptyList()
        }
    }

    /** The 0 x 0 nodes tagged [id] (inside [within] when given), see [ZeroSizeNodes]. */
    fun zeroSize(id: String, within: String?): List<ZeroSizeNodes.Hit> = ZeroSizeNodes.find(id, within)

    /** Present as `waitFor` means it: found, or there with a 0 x 0 box. */
    fun isPresent(id: String, within: String?): Boolean =
        find(id, within) != null || zeroSize(id, within).isNotEmpty()

    /**
     * `hidden` (assertion and condition): every element with [id] in scope is
     * not drawn — none at all, or each one under the hidden marker
     * ([HiddenNodes]). A missing container holds no element, so it is hidden.
     * Without Compose the semantics tree cannot be read and the answer is
     * "no element found by resource-id", as before.
     */
    fun isHidden(id: String, within: String?): Boolean =
        HiddenNodes.isHidden(id, within) ?: (find(id, within) == null)

    /**
     * Why a scoped find missed, for a failure message; null when unscoped
     * (the caller keeps its own unscoped message).
     */
    fun scopedMiss(id: String, within: String?, timeoutMs: Long): String? {
        if (within == null) return null
        return if (container(within) == null) Within.containerNotFound(within, id, timeoutMs)
        else Within.targetNotInContainer(id, within, timeoutMs)
    }
}

/** Failure text and decisions for scoped (`within`) lookups. Pure, JVM-tested. */
object Within {

    /**
     * A step's or a condition's `within` names a container that is not in
     * the tree: `visible`, `notVisible`, `hidden` and `count` fail and a
     * condition is unmet, as on web and iOS. A cell that is not drawn would
     * otherwise pass "this cell's badge is not shown"; to claim the cell
     * itself is gone, `hidden` is written on the cell's id without `within`.
     */
    fun missingContainerFails(within: String?, containerFound: Boolean): Boolean =
        within != null && !containerFound

    /**
     * The container a scoped semantics read looks inside: the FIRST node, in
     * pre-order across [roots] (the order a resource-id lookup finds them,
     * as UiDevice.findObject does), that [isContainer] accepts — with the
     * state [enter] carried down to it (exposure as a resource-id, a hidden
     * marker above). Only one: two sections each have an `_item_0`, and the
     * scoped find, web and iOS all take the first.
     */
    fun <N, S> firstContainer(
        roots: List<N>,
        start: S,
        children: (N) -> List<N>,
        enter: (N, S) -> S,
        isContainer: (N, S) -> Boolean
    ): Pair<N, S>? {
        fun visit(node: N, above: S): Pair<N, S>? {
            val state = enter(node, above)
            if (isContainer(node, state)) return node to state
            for (child in children(node)) visit(child, state)?.let { return it }
            return null
        }
        for (root in roots) visit(root, start)?.let { return it }
        return null
    }

    /** How an id is named in a message: `'x'`, or `'x' inside 'c'`. */
    fun label(id: String, within: String?): String =
        if (within == null) "'$id'" else "'$id' inside '$within'"

    fun containerNotFound(within: String, id: String, timeoutMs: Long): String =
        "Container '$within' (the step's `within`) not found by resource-id within ${timeoutMs}ms, " +
            "so '$id' could not be looked for inside it. A collection cell (<collection>_item_<n>) " +
            "exists in the tree only while it is drawn: scrollUntilVisible to '$within' first."

    fun targetNotInContainer(id: String, within: String, timeoutMs: Long): String =
        "Element '$id' not found inside container '$within' within ${timeoutMs}ms: " +
            "'$within' is on screen, but none of its drawn descendants has the resource-id '$id'."
}

// A copy of KotlinJsonUI library/src/main/kotlin/com/kotlinjsonui/components/CollectionReorder.kt at ae5fb38 (branch
// feat/collection-reorder), the package changed: the rows moveItem's on-device arm drags are the library's own.
// compose-probe cannot depend on KotlinJsonUI (its Kotlin and Compose are newer than this build's).
// One change besides the package: NestedScrollSource.UserInput (Compose 1.7) is Drag, its name in this build's 1.6.
package com.jsonui.testrunner.composeprobe.reorder

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import java.util.Locale

/**
 * A reorderable Collection's declaration (`reorderable`, `reorderHandle`, `onReorder`; ticket
 * jui-collection-drag-to-reorder), the twin of SwiftJsonUI's `CollectionReorder`. One per Collection.
 *
 * A row is picked up by a long press — on the whole row, or with [usesHandle] on the element marked
 * [collectionReorderHandle] only — follows the finger, and on release [onReorder] is called once with the section,
 * the index it had and the index it holds after the move ([CollectionReorderRules]). The rows are not reordered here:
 * the view model reorders its items, and the Collection draws them again.
 */
@Stable
class CollectionReorder(
    val isEnabled: Boolean,
    val usesHandle: Boolean = false,
    val onReorder: (section: Int, from: Int, to: Int) -> Unit,
)

/** The container's and the drags' state, provided by [CollectionReorderContainer] to the rows under it. */
@Stable
class CollectionReorderState internal constructor() {
    internal var reorder by mutableStateOf<CollectionReorder?>(null)
    internal var containerTop = 0f
    internal var containerBottom = 0f
    internal var viewportTop = 0f
    internal var viewportBottom = Float.MAX_VALUE
    internal var density = 1f
    internal var scrollsItself = true
    private val drags = HashMap<Int, CollectionReorderDrag>()

    internal fun drag(section: Int): CollectionReorderDrag = drags.getOrPut(section) { CollectionReorderDrag() }
}

/** One section's drag: which row, how far (content coordinates, the auto-scroll included), and the rows it reads. */
@Stable
class CollectionReorderDrag internal constructor() {
    var dragging by mutableStateOf<Int?>(null)
        internal set
    var dy by mutableFloatStateOf(0f)
        internal set
    /** Each row's top and bottom as laid out (before any shift drawn for the drag), in content coordinates. */
    internal val spans = HashMap<Int, CollectionReorderRules.RowSpan>()
    internal var scrolled = 0f            // content scrolled since the drag began (the auto-scroll)
    internal var fingerDy = 0f            // the finger's own travel
    internal var fingerY = 0f             // the finger, in root coordinates
    internal var autoScrollVelocity = 0f  // px a second
    internal var locked: Set<Int> = emptySet()
    internal var count = 0
    /**
     * True from a committed move until the next drag begins. The data has moved, so each row is laid out where it
     * was drawn: the rows the drag shifted take their place at once. Animated back instead, a row that had made room
     * changed its index while it was still drawn a row away, and was drawn there — one row up or down — before it
     * slid back; the screen's accessibility bounds kept that drawn place (they follow layout and semantics, not a
     * redraw), and a finger aimed there landed one row further (measured: 5 of 12 moves made right after a move).
     */
    var committed by mutableStateOf(false)
        internal set

    internal fun rows(): List<CollectionReorderRules.RowSpan> =
        (0 until count).map { spans[it] ?: CollectionReorderRules.RowSpan(0f, 0f) }

    fun target(): Int {
        val from = dragging ?: return 0
        return CollectionReorderRules.target(from, dy, rows(), locked)
    }

    /** How far the row at [index] is drawn from where it is laid out while [dragging] is dragged. */
    internal fun shiftOf(index: Int): Float {
        val from = dragging ?: return 0f
        if (index == from) return dy
        val to = target()
        val h = spans[from]?.let { it.bottom - it.top } ?: 0f
        return when {
            to > from && index in (from + 1)..to -> -h
            to < from && index in to until from -> h
            else -> 0f
        }
    }
}

val LocalCollectionReorder = compositionLocalOf<CollectionReorderState?> { null }

/**
 * The state of one Collection's reorder, kept where the Collection is composed. [scrollsItself]: false for a NONE
 * column, whose auto-scroll bands are the window's edges (an outer scroll carries it); true for a LAZY list and an
 * EAGER column, whose visible part is the band's frame.
 */
@Composable
fun rememberCollectionReorder(reorder: CollectionReorder?, scrollsItself: Boolean = true): CollectionReorderState {
    val state = remember { CollectionReorderState() }
    state.reorder = reorder
    state.scrollsItself = scrollsItself
    state.density = LocalDensity.current.density
    return state
}

/**
 * On the Collection's container (the node that holds the rows, its header and footer with them): its bounds decide
 * whether a release commits (only outside it is the move cancelled) and where the auto-scroll's bands are. No layout
 * node is added: the container keeps its own modifiers (a weight in its parent's scope among them).
 */
fun Modifier.collectionReorderContainer(state: CollectionReorderState): Modifier = this.onGloballyPositioned { c ->
    val b = c.boundsInRoot()
    val windowBottom = c.findRootCoordinates().size.height.toFloat()
    state.containerTop = b.top
    state.containerBottom = b.bottom
    // What the auto-scroll's bands are measured against: the part of a container that scrolls itself (LAZY, EAGER)
    // that can be seen; for one that an outer scroll carries (NONE), the window — the container's own bottom there is
    // no edge to scroll at (a short list's last row sat in its band, measured).
    state.viewportTop = if (state.scrollsItself) maxOf(0f, b.top) else 0f
    state.viewportBottom = if (state.scrollsItself) minOf(windowBottom, b.bottom) else windowBottom
}

/** Hands [state] to the rows composed under [content] (a CompositionLocal: no layout node). */
@Composable
fun CollectionReorderScope(state: CollectionReorderState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCollectionReorder provides state) { content() }
}

/**
 * The three above in one Box, for a container that may take one. The auto-scroll is handed to the scrolling parent
 * — the LazyColumn, the EAGER column's scroll, or an outer scroll around a NONE column — as a nested scroll from the
 * dragged row, so one way serves all three.
 */
@Composable
fun CollectionReorderContainer(
    reorder: CollectionReorder?,
    modifier: Modifier = Modifier,
    scrollsItself: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = rememberCollectionReorder(reorder, scrollsItself)
    Box(modifier.collectionReorderContainer(state)) { CollectionReorderScope(state, content) }
}

/** The library's words for the two actions, in the app's language (ja, else en) — SwiftJsonUI's CollectionReorderText. */
internal object CollectionReorderText {
    private val ja get() = Locale.getDefault().language == "ja"
    val moveUp get() = if (ja) "上へ移動" else "Move up"
    val moveDown get() = if (ja) "下へ移動" else "Move down"
}

private class RowContext(val state: CollectionReorderState, val section: Int, val index: Int)

private val LocalReorderRow = compositionLocalOf<RowContext?> { null }

/**
 * The rows of one section of a NONE or EAGER column (the twin of SwiftJsonUI's `CollectionReorderRows`): [ids] are
 * the rows' keys in drawing order, [locked] the indexes of the rows that neither move nor are passed. Without a
 * reorder under a [CollectionReorderContainer] (or with it disabled) the rows are drawn as a plain loop.
 */
@Composable
fun CollectionReorderRows(section: Int, ids: List<String>, locked: Set<Int> = emptySet(), content: @Composable (Int) -> Unit) {
    val state = LocalCollectionReorder.current
    ids.forEachIndexed { index, id ->
        key(id) {
            if (state?.reorder?.isEnabled == true) ReorderRow(state, section, index, ids.size, locked) { content(index) }
            else content(index)
        }
    }
}

/** The same rows in a LAZY list: [state] is the one the enclosing [CollectionReorderContainer] provides. */
fun LazyListScope.collectionReorderRows(
    state: CollectionReorderState?,
    section: Int,
    ids: List<String>,
    locked: Set<Int> = emptySet(),
    itemContent: @Composable LazyItemScope.(Int) -> Unit,
) {
    items(ids.size, key = { "s$section:${ids[it]}" }) { index ->
        if (state?.reorder?.isEnabled == true) ReorderRow(state, section, index, ids.size, locked) { itemContent(index) }
        else itemContent(index)
    }
}

/** The state a LAZY list's rows read, taken where the LazyColumn is composed. */
@Composable
fun rememberCollectionReorderState(): CollectionReorderState? = LocalCollectionReorder.current

/**
 * One row of a reorderable section, for a caller that emits its rows itself (the Dynamic renderer's grid items and
 * column): [count] rows in the section, [locked] the indexes that neither move nor are passed. Without an enabled
 * reorder it draws [content] as it is.
 */
@Composable
fun CollectionReorderRow(
    state: CollectionReorderState?,
    section: Int,
    index: Int,
    count: Int,
    locked: Set<Int> = emptySet(),
    content: @Composable () -> Unit,
) {
    if (state?.reorder?.isEnabled == true) ReorderRow(state, section, index, count, locked, content) else content()
}

@Composable
private fun ReorderRow(
    state: CollectionReorderState,
    section: Int,
    index: Int,
    count: Int,
    locked: Set<Int>,
    content: @Composable () -> Unit,
) {
    val drag = state.drag(section)
    drag.count = count
    drag.locked = locked
    val reorder = state.reorder ?: return content()
    val isDragged = drag.dragging == index
    // A cancelled drag slides the rows it shifted back; a committed one leaves them where the data put them.
    val target = if (isDragged) 0f else drag.shiftOf(index)
    val shift = remember { Animatable(0f) }
    LaunchedEffect(target, drag.committed) { if (drag.committed) shift.snapTo(0f) else shift.animateTo(target) }
    val ctx = remember(state, section, index) { RowContext(state, section, index) }
    val rowGesture = if (!reorder.usesHandle && index !in locked) Modifier.reorderGesture(ctx) else Modifier
    // The auto-scroll goes from the dragged row up to whatever scrolls it (nested scroll), and a lazy list keeps the
    // dragged row composed while it is scrolled out of sight (pinned): its gesture would end with it otherwise.
    val dispatcher = remember { NestedScrollDispatcher() }
    val pinnable = LocalPinnableContainer.current
    LaunchedEffect(isDragged) {
        if (!isDragged) return@LaunchedEffect
        val handle = pinnable?.pin()
        try { autoScroll(drag, dispatcher) } finally { handle?.release() }
    }
    Box(
        Modifier
            .nestedScroll(remember { object : NestedScrollConnection {} }, dispatcher)
            .onGloballyPositioned { c ->
                // As laid out (the drawn shift below is not part of it), in content coordinates: root plus what the
                // drag has scrolled, so a scroll does not move a span.
                val top = c.positionInRoot().y + drag.scrolled
                drag.spans[index] = CollectionReorderRules.RowSpan(top, top + c.size.height)
            }
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                // The dragged row follows the finger: its layout moved with the scroll, so it is drawn dy from there.
                translationY = when {
                    isDragged -> drag.dy
                    drag.committed -> 0f
                    else -> shift.value
                }
                if (isDragged) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 8f * state.density }
            }
            .then(rowGesture),
    ) {
        CompositionLocalProvider(LocalReorderRow provides ctx) { content() }
    }
}

/**
 * For a lazy grid's item, whose root is the caller's own (it carries animateItem): the dragged row is drawn over the
 * others only when the item's ROOT is raised, so the caller puts this on it.
 */
fun Modifier.collectionReorderLayer(state: CollectionReorderState?, section: Int, index: Int): Modifier =
    if (state?.reorder?.isEnabled != true) this
    else this.zIndex(if (state.drag(section).dragging == index) 1f else 0f)

/**
 * "Move up" / "Move down" on a row, as the rule allows them ([CollectionReorderRules.accessibilityMoves]) — on the
 * cell's ROOT, the node TalkBack stops at: on a wrapper around the cell they were never offered, since a clickable
 * cell is a stop of its own (measured). A root that is not one already is made one (its content merged), so a row is
 * one stop with its two actions. The generated code and the Dynamic renderer put this on every cell's root; outside a
 * reorderable row it does nothing.
 */
fun Modifier.collectionReorderActions(): Modifier = composed {
    val ctx = LocalReorderRow.current ?: return@composed this
    val reorder = ctx.state.reorder ?: return@composed this
    val drag = ctx.state.drag(ctx.section)
    val (up, down) = CollectionReorderRules.accessibilityMoves(ctx.index, drag.count, drag.locked)
    val onReorder by rememberUpdatedState(reorder.onReorder)
    this.semantics(mergeDescendants = true) {
        customActions = listOfNotNull(
            if (up) CustomAccessibilityAction(CollectionReorderText.moveUp) { onReorder(ctx.section, ctx.index, ctx.index - 1); true } else null,
            if (down) CustomAccessibilityAction(CollectionReorderText.moveDown) { onReorder(ctx.section, ctx.index, ctx.index + 1); true } else null,
        )
    }
}

/**
 * The element a row is picked up by when the Collection declares `reorderHandle`: the generated code marks the
 * element with the handle's id. Outside a reorderable row it does nothing.
 */
fun Modifier.collectionReorderHandle(): Modifier = composed {
    val ctx = LocalReorderRow.current
    if (ctx == null || ctx.state.reorder?.usesHandle != true) this else this.reorderGesture(ctx)
}

private fun Modifier.reorderGesture(ctx: RowContext): Modifier = composed {
    val state = ctx.state
    val drag = state.drag(ctx.section)
    this.pointerInput(ctx.section, ctx.index) {
        var startRootY = 0f
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                if (ctx.index in drag.locked) return@detectDragGesturesAfterLongPress
                drag.committed = false
                drag.scrolled = 0f
                drag.fingerDy = 0f
                drag.dy = 0f
                startRootY = (drag.spans[ctx.index]?.top ?: 0f) + offset.y
                drag.fingerY = startRootY
                drag.dragging = ctx.index
            },
            onDrag = { change, amount ->
                if (drag.dragging != ctx.index) return@detectDragGesturesAfterLongPress
                change.consume()
                drag.fingerDy += amount.y
                drag.fingerY = startRootY + drag.fingerDy
                drag.dy = drag.fingerDy + drag.scrolled
                drag.autoScrollVelocity = CollectionReorderRules.autoScrollVelocity(
                    drag.fingerY / state.density, state.viewportTop / state.density, state.viewportBottom / state.density,
                ) * state.density
            },
            onDragEnd = { finish(state, drag, ctx.section, cancelled = false) },
            onDragCancel = { finish(state, drag, ctx.section, cancelled = true) },
        )
    }
}

private fun finish(state: CollectionReorderState, drag: CollectionReorderDrag, section: Int, cancelled: Boolean) {
    val from = drag.dragging ?: return
    val to = drag.target()
    val commits = !cancelled &&
        CollectionReorderRules.releaseCommits(drag.fingerY, state.containerTop, state.containerBottom)
    drag.autoScrollVelocity = 0f
    drag.dragging = null
    drag.dy = 0f
    // The spans stay (a row that is not laid out again keeps its own); they are only moved back into the frame of
    // no scroll, as the rows that are laid out again will record themselves.
    if (drag.scrolled != 0f) {
        val back = drag.scrolled
        drag.spans.replaceAll { _, s -> CollectionReorderRules.RowSpan(s.top - back, s.bottom - back) }
    }
    drag.scrolled = 0f
    if (commits && CollectionReorderRules.shouldReport(from, to)) {
        drag.committed = true
        state.reorder?.onReorder?.invoke(section, from, to)
    }
}

/** While a row is dragged near the container's visible edge, scroll its parent each frame (a nested scroll). */
private suspend fun autoScroll(drag: CollectionReorderDrag, dispatcher: NestedScrollDispatcher) {
    var last = 0L
    while (drag.dragging != null) {
        withFrameNanos { now ->
            val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
            last = now
            val v = drag.autoScrollVelocity
            if (v != 0f && dt > 0f) {
                // A positive v scrolls the content up (the finger near the bottom): the parent takes a negative delta.
                val consumed = dispatcher.dispatchPostScroll(Offset.Zero, Offset(0f, -v * dt), NestedScrollSource.Drag)
                val moved = -consumed.y
                if (moved != 0f) {
                    drag.scrolled += moved
                    drag.dy = drag.fingerDy + drag.scrolled
                }
            }
        }
    }
}

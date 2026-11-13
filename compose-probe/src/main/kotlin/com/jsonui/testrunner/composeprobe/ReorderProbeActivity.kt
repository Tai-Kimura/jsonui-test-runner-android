package com.jsonui.testrunner.composeprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.jsonui.testrunner.composeprobe.reorder.CollectionReorder
import com.jsonui.testrunner.composeprobe.reorder.CollectionReorderContainer
import com.jsonui.testrunner.composeprobe.reorder.CollectionReorderRows
import com.jsonui.testrunner.composeprobe.reorder.CollectionReorderScope
import com.jsonui.testrunner.composeprobe.reorder.collectionReorderActions
import com.jsonui.testrunner.composeprobe.reorder.collectionReorderContainer
import com.jsonui.testrunner.composeprobe.reorder.collectionReorderHandle
import com.jsonui.testrunner.composeprobe.reorder.collectionReorderRows
import com.jsonui.testrunner.composeprobe.reorder.rememberCollectionReorder

/**
 * Reorderable rows drawn by KotlinJsonUI's own drag (a copy, compose-probe/.../reorder) the way kjui emits them —
 * each row tagged `stops_item_<n>` with the accessibility moves on its root, and in the handle mode the same
 * reorderHandle id `grip` in every row — for MoveItemOnDeviceTest. Two modes (intent extra `mode`):
 *  - "list": a LazyColumn that scrolls itself, the whole row pressed (Collection's list route);
 *  - "handle": rows in a Column an outer scroll carries (lazy: none), pressed on the grip, row 3 locked;
 *  - "short": the list of "list" 330 dp tall in the screen's middle, a text below it: row 4 is half under its edge;
 *  - "few": six rows in a list exactly their height in the screen's middle — the list cannot scroll either way;
 *  - "header": the list of "list" under a 300 dp header scrolled up to its last 10 dp: row 0 whole, the header cut.
 * [rows] is the order the "view model" holds, moved on onReorder; [moves] the onReorder calls, read in-process.
 */
class ReorderProbeActivity : ComponentActivity() {
    companion object {
        val INITIAL = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T")
        const val LOCKED = 3
        val rows = mutableStateListOf<String>()
        val moves = mutableListOf<Triple<Int, Int, Int>>()
        @Volatile var resumed = false
        /** The lazy list's first visible item index, read in-process (null in the handle mode). */
        @Volatile var firstVisible: Int? = null
        /** The lazy list's place before a move, put back after it (see onCreate). */
        @Volatile var lazyState: androidx.compose.foundation.lazy.LazyListState? = null
        val restore = androidx.compose.runtime.mutableStateOf<Pair<Int, Int>?>(null)

        fun reset() {
            resumed = false
            rows.clear(); rows.addAll(INITIAL); moves.clear(); lazyState = null; restore.value = null
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode")
        val handle = mode == "handle"
        // This build's Compose (1.6.8) keeps a lazy list's first visible item in place BY KEY: moved, the list
        // scrolls after it. KotlinJsonUI's Compose (BOM 2026.09.00) holds the list's place instead (KotlinJsonUI's
        // CollectionReorderScrollAnchorTest pins it), so the probe puts the place back, as the library's rows behave.
        val reorder = CollectionReorder(isEnabled = true, usesHandle = handle) { section, from, to ->
            lazyState?.let { restore.value = it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
            moves.add(Triple(section, from, to))
            rows.add(to, rows.removeAt(from))
        }
        if (mode == "few") { rows.clear(); rows.addAll(INITIAL.take(6)) }
        setContent {
            val row = @androidx.compose.runtime.Composable { i: Int ->
                Row(
                    Modifier.collectionReorderActions().fillMaxWidth().height(64.dp).testTag("stops_item_$i")
                        .background(if (handle && i == LOCKED) Color(0xFFEDE7F6) else Color(0xFFF1F3F4)).clickable { },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(rows[i], Modifier.weight(1f).padding(start = 16.dp).testTag("stops_text"))
                    if (handle) Box(Modifier.size(56.dp).collectionReorderHandle().testTag("grip"), contentAlignment = Alignment.Center) { Text("≡") }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.White).semantics { testTagsAsResourceId = true }) {
                if (handle) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp, start = 16.dp, end = 16.dp)) {
                        CollectionReorderContainer(reorder, Modifier.testTag("stops"), scrollsItself = false) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CollectionReorderRows(0, rows.toList(), locked = setOf(LOCKED)) { i -> row(i) }
                            }
                        }
                        Spacer(Modifier.height(400.dp))
                    }
                } else {
                    val state = rememberCollectionReorder(reorder, scrollsItself = true)
                    CollectionReorderScope(state) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                            0, if (mode == "header") with(density) { 290.dp.roundToPx() } else 0
                        )
                        androidx.compose.runtime.SideEffect { firstVisible = listState.firstVisibleItemIndex; lazyState = listState }
                        androidx.compose.runtime.LaunchedEffect(restore.value) {
                            restore.value?.let { (i, o) -> listState.scrollToItem(i, o); restore.value = null }
                        }
                        androidx.compose.runtime.LaunchedEffect(listState) {
                            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }.collect { firstVisible = it }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (mode == "short" || mode == "few") Spacer(Modifier.height(240.dp))
                            LazyColumn(
                                when (mode) {
                                    "short" -> Modifier.fillMaxWidth().height(330.dp)
                                    "few" -> Modifier.fillMaxWidth().height(424.dp) // 6 rows of 64 dp, 8 dp apart
                                    else -> Modifier.fillMaxSize().padding(top = 40.dp)
                                }.testTag("stops").collectionReorderContainer(state),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (mode == "header") item(key = "header") {
                                    Box(Modifier.fillMaxWidth().height(292.dp).background(Color(0xFFE3F2FD)).testTag("stops_header"))
                                }
                                collectionReorderRows(state, 0, rows.toList()) { i -> row(i) }
                            }
                            if (mode == "short" || mode == "few") Text("below the list", Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }
}

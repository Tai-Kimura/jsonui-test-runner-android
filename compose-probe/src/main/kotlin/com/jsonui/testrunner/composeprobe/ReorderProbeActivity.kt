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
 *  - "short": the list of "list" 330 dp tall in the screen's middle, a text below it: row 4 is half under its edge.
 * [rows] is the order the "view model" holds, moved on onReorder; [moves] the onReorder calls, read in-process.
 */
class ReorderProbeActivity : ComponentActivity() {
    companion object {
        val INITIAL = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T")
        const val LOCKED = 3
        val rows = mutableStateListOf<String>()
        val moves = mutableListOf<Triple<Int, Int, Int>>()
        @Volatile var resumed = false

        fun reset() {
            resumed = false
            rows.clear(); rows.addAll(INITIAL); moves.clear()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode")
        val handle = mode == "handle"
        val reorder = CollectionReorder(isEnabled = true, usesHandle = handle) { section, from, to ->
            moves.add(Triple(section, from, to))
            rows.add(to, rows.removeAt(from))
        }
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
                        Column(Modifier.fillMaxSize()) {
                            if (mode == "short") Spacer(Modifier.height(240.dp))
                            LazyColumn(
                                (if (mode == "short") Modifier.fillMaxWidth().height(330.dp) else Modifier.fillMaxSize().padding(top = 40.dp))
                                    .testTag("stops").collectionReorderContainer(state),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                collectionReorderRows(state, 0, rows.toList()) { i -> row(i) }
                            }
                            if (mode == "short") Text("below the list", Modifier.padding(16.dp))
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

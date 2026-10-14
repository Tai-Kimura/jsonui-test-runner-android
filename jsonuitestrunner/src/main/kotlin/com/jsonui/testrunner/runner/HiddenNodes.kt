package com.jsonui.testrunner.runner

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * The `hidden` assertion and `when: { hidden }`: whether an id is not drawn,
 * wherever it is — not "not on screen".
 *
 * Hidden = no node carries the id, or every node that does is not drawn: the
 * node or an ancestor is marked `JsonUIHidden` (KotlinJsonUI marks
 * visibility "invisible" and `hidden: true` in the semantics tree; "gone" is
 * not composed at all). Shown — so `hidden` fails — is everything else,
 * including a node scrolled off screen, one under a dialog or a modal sheet,
 * one under another view, at opacity 0, or disabled.
 *
 * The accessibility tree (By.res) cannot answer it: Compose leaves an
 * off-screen node, and every node of a window under a dialog, out of it, so
 * there "not drawn" and "not on screen" are one answer. This reads the app's
 * unmerged semantics tree directly — the test runs in the app's process — in
 * every window (a dialog or a sheet is its own), as [ZeroSizeNodes] does.
 * Measured on API 35, KotlinJsonUI with the marker: plain / opacity 0 /
 * disabled / 3000dp below the fold / under a dialog shown; invisible / hidden /
 * their bindings / a child of an invisible parent marked; gone / absent not in
 * the tree.
 *
 * A KotlinJsonUI without the marker draws "invisible" and `hidden: true` at
 * alpha 0, which this cannot tell from `opacity: 0`: they read as shown.
 *
 * Compose is compileOnly. An app without Compose never loads these classes:
 * [available] is false and the caller decides with By.res instead.
 */
object HiddenNodes {

    /** The semantics key KotlinJsonUI sets on a view it keeps in the layout but does not draw. */
    const val MARKER = "JsonUIHidden"

    /** Whether the app under test has Compose on its classpath. */
    val available: Boolean by lazy {
        runCatching { Class.forName("androidx.compose.ui.node.RootForTest", false, HiddenNodes::class.java.classLoader) }
            .isSuccess
    }

    /** Whether [id] is hidden; null when the semantics tree cannot be read (no Compose). */
    fun isHidden(id: String): Boolean? {
        if (!available) return null
        var shown = false
        var read = false
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runCatching {
                    shown = anyShown(id)
                    read = true
                }
            }
        }
        return if (read) !shown else null
    }

    private fun rootViews(): List<View> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return android.view.inspector.WindowInspector.getGlobalWindowViews()
        }
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED)
            .flatMap { registry.getActivitiesInStage(it) }
            .mapNotNull { (it as Activity).window?.decorView }
    }

    private fun anyShown(id: String): Boolean {
        val views = ArrayDeque(rootViews())
        while (views.isNotEmpty()) {
            val view = views.removeFirst()
            if (view is ViewGroup) for (i in 0 until view.childCount) view.getChildAt(i)?.let(views::add)
            if (view !is RootForTest) continue
            if (walk(view.semanticsOwner.unmergedRootSemanticsNode, false, false, id)) return true
        }
        return false
    }

    /** True when a node tagged [id], exposed as a resource-id, is not under a marker. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun walk(node: SemanticsNode, asResourceId: Boolean, markedAbove: Boolean, id: String): Boolean {
        val config = node.config
        val exposed = asResourceId ||
            config.getOrElseNullable(SemanticsPropertiesAndroid.TestTagsAsResourceId) { null } == true
        val marked = markedAbove || config.any { it.key.name == MARKER && it.value == true }
        if (exposed && !marked && config.getOrElseNullable(SemanticsProperties.TestTag) { null } == id) return true
        for (child in node.children) if (walk(child, exposed, marked, id)) return true
        return false
    }
}

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
 * Compose nodes carrying a resource-id whose DRAWN box is 0 wide or 0 high.
 *
 * An element with a layout id is found by resource-id on iOS whatever its
 * size (XCUIElement `exists`). On Android an empty container — 0 high once its
 * testTag names the drawn box (KotlinJsonUI 2.43.5) — is not in the
 * accessibility tree at all: Compose exports a semantics node only when its
 * bounds intersect the space left to account for, and an empty rect never
 * does, so `By.res` (which also skips every node that is not visibleToUser)
 * has nothing to match. Measured on API 35, Compose 1.12.1: three 0-size
 * shapes absent from a full walk of rootInActiveWindow, present in the
 * semantics tree with size 0 x 0.
 *
 * The test runs in the app's process, so this reads that semantics tree
 * directly. It is consulted ONLY after By.res has missed, and returns ONLY
 * 0-size nodes: a node with a size that By.res does not find (scrolled off,
 * covered, not yet drawn) stays not found, as before.
 *
 * Mirrors what makes a testTag a resource-id: the node, or an ancestor in its
 * semantics tree, sets testTagsAsResourceId.
 *
 * Compose is compileOnly. An app without Compose never loads these classes:
 * [available] is false and every lookup returns nothing.
 */
object ZeroSizeNodes {

    /** One 0-size node with the id, and what an assertion can read from it. */
    data class Hit(val width: Int, val height: Int, val enabled: Boolean, val text: String)

    /** Whether the app under test has Compose on its classpath. */
    val available: Boolean by lazy {
        runCatching { Class.forName("androidx.compose.ui.node.RootForTest", false, ZeroSizeNodes::class.java.classLoader) }
            .isSuccess
    }

    /** The 0-size nodes tagged [id] that are exposed as a resource-id. */
    fun find(id: String): List<Hit> {
        if (!available) return emptyList()
        val hits = mutableListOf<Hit>()
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runCatching { collect(id, hits) }
            }
        }
        return hits
    }

    /** Every root view in the process — a modal sheet or dialog is its own window. */
    private fun rootViews(): List<View> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return android.view.inspector.WindowInspector.getGlobalWindowViews()
        }
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED)
            .flatMap { registry.getActivitiesInStage(it) }
            .mapNotNull { (it as Activity).window?.decorView }
    }

    private fun collect(id: String, out: MutableList<Hit>) {
        val views = ArrayDeque(rootViews())
        while (views.isNotEmpty()) {
            val view = views.removeFirst()
            if (view is ViewGroup) for (i in 0 until view.childCount) view.getChildAt(i)?.let(views::add)
            if (view !is RootForTest) continue
            walk(view.semanticsOwner.unmergedRootSemanticsNode, false, id, out)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun walk(node: SemanticsNode, asResourceId: Boolean, id: String, out: MutableList<Hit>) {
        val config = node.config
        val exposed = asResourceId ||
            config.getOrElseNullable(SemanticsPropertiesAndroid.TestTagsAsResourceId) { null } == true
        val size = node.size
        if (exposed && (size.width == 0 || size.height == 0) &&
            config.getOrElseNullable(SemanticsProperties.TestTag) { null } == id
        ) {
            val text = config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text
                ?: config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(" ") { it.text }
                ?: ""
            out.add(Hit(size.width, size.height, !config.contains(SemanticsProperties.Disabled), text))
        }
        for (child in node.children) walk(child, exposed, id, out)
    }
}

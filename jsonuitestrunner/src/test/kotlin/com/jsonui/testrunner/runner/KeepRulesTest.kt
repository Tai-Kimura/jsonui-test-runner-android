package com.jsonui.testrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep artifact's rules against what the driver's sources call in the app: a new Compose import or call that is
 * not kept passes every test here on a debuggable build and fails only under R8. On the device: the rules' removal,
 * one at a time, goes red in the probe app (jsonui-test-runner-android ticket on R8, 2026-11-06).
 */
class KeepRulesTest {
    private val repo: File = listOf(File("."), File("..")).first { File(it, "jsonuitestrunner-keep").isDirectory }
    private val rules = File(repo, "jsonuitestrunner-keep/consumer-rules.pro").readText()
    private val sources = File(repo, "jsonuitestrunner/src/main/kotlin/com/jsonui/testrunner").walkTopDown()
        .filter { it.extension == "kt" }.associate { it.name to it.readText() }

    /** How a source calls it, and the member the compiled call names. */
    private val calls = mapOf(
        ".semanticsOwner" to "getSemanticsOwner",
        ".unmergedRootSemanticsNode" to "getUnmergedRootSemanticsNode",
        ".config" to "getConfig",
        ".children" to "getChildren",
        ".size" to "getSize-*",
        ".width" to "getWidth-impl",
        ".height" to "getHeight-impl",
        "getOrElseNullable(" to "getOrElseNullable",
        ".contains(SemanticsProperties" to "contains",
        "SemanticsProperties.TestTag" to "getTestTag",
        "SemanticsProperties.Text" to "getText",
        "SemanticsProperties.EditableText" to "getEditableText",
        "SemanticsProperties.Disabled" to "getDisabled",
        "SemanticsPropertiesAndroid.TestTagsAsResourceId" to "getTestTagsAsResourceId",
        ".key.name" to "getName",
    )

    @Test
    fun everyComposeClassTheDriverImportsIsKeptOrKnownToBeOnlyAKey() {
        val imports = sources.values.flatMap { Regex("""^import (androidx\.compose\.[\w.]+)$""", RegexOption.MULTILINE).findAll(it).map { m -> m.groupValues[1] }.toList() }.toSet()
        // An annotation with no runtime reference.
        val notAtRunTime = setOf("androidx.compose.ui.ExperimentalComposeUiApi")
        assertEquals(
            setOf("androidx.compose.ui.node.RootForTest", "androidx.compose.ui.semantics.SemanticsNode",
                "androidx.compose.ui.semantics.SemanticsProperties", "androidx.compose.ui.semantics.SemanticsPropertiesAndroid"),
            imports - notAtRunTime
        )
        for (name in imports - notAtRunTime) assertTrue("$name is not kept", rules.contains(" $name "))
    }

    @Test
    fun everyCallIsKept() {
        val readers = listOf("HiddenNodes.kt", "StateBridge.kt", "ZeroSizeNodes.kt").joinToString("\n") { sources.getValue(it) }
        for ((call, member) in calls) {
            assertTrue("no reader calls $call any more: drop its rule and this row", readers.contains(call))
            assertTrue("$member is not kept", rules.contains(member))
        }
    }

    @Test
    fun whatTheCallsReturnIsKeptToo() {
        for (name in listOf("SemanticsOwner", "SemanticsConfiguration", "SemanticsPropertyKey", "text.AnnotatedString", "unit.IntSize")) {
            assertTrue("$name is not kept", rules.contains(name))
        }
    }

    @Test
    fun whatTheRunnerTakesIsKept() {
        for (name in listOf("androidx.tracing.Trace", "androidx.concurrent.futures.CallbackToFutureAdapter",
            "androidx.concurrent.futures.SuspendToFutureAdapter", "kotlin.**", "kotlinx.serialization.**", "kotlinx.coroutines.**")) {
            assertTrue("$name is not kept", rules.contains("-keep class $name { *; }"))
        }
    }

    @Test
    fun noRuleChangesWhatTheAppDoes() {
        assertFalse(rules.lineSequence().any { it.startsWith("-assume") || it.startsWith("-dont") })
    }

    @Test
    fun theArtifactIsTheDriversVersion() {
        val version = Regex("""coordinates\("io\.github\.tai-kimura", "([\w-]+)", "([^"]+)"\)""")
        val driver = version.find(File(repo, "jsonuitestrunner/build.gradle.kts").readText())!!
        val keep = version.find(File(repo, "jsonuitestrunner-keep/build.gradle.kts").readText())!!
        assertEquals("jsonui-test-runner-android-keep", keep.groupValues[1])
        assertEquals("io.github.tai-kimura:" + keep.groupValues[1], ComposeRead.KEEP_ARTIFACT)
        assertEquals(driver.groupValues[2], keep.groupValues[2])
    }
}

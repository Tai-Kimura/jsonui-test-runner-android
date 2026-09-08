package com.jsonui.testrunner.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * "Your results were discarded" must be said, once, regardless of `verbose`.
 *
 * 🚨 The runner has exactly ONE println and it sits inside `log()` behind
 * `config.verbose` (default false). A consumer lane could not tell whether its
 * cases ran in the orientation they declared: the driver computes the pair per
 * case, writes it only when `resultsPath` is set, and `resultsPath` defaults to
 * null while the README's config example named 6 of 17 fields — not that one.
 * Computed, then dropped, silently.
 */
class RunNoticesTest {

    @Before fun reset() = RunNotices.resetForTest()

    @Test
    fun `it says a thing once per process, not once per runner`() {
        // 🚨 The runner is NEW for every test, so an un-deduplicated notice
        // would print once per case — 76 identical lines on the reporting
        // lane. Same lifetime trap the orientation floor had.
        RunNotices.once("k", "message")
        RunNotices.once("k", "message")
        RunNotices.once("k", "message")
        assertEquals(setOf("k"), RunNotices.saidKeys())
    }

    @Test
    fun `different keys are said separately`() {
        RunNotices.once("a", "one")
        RunNotices.once("b", "two")
        assertEquals(setOf("a", "b"), RunNotices.saidKeys())
    }

    @Test
    fun `the control - nothing is said before the first call`() {
        assertEquals(emptySet<String>(), RunNotices.saidKeys())
    }

    @Test
    fun `the missing-results notice does NOT go through the verbose-gated log`() {
        // ⚠️ The arm that makes the fix mean anything. Routing this through
        // `log()` would compile, pass every behavioural arm, and say nothing
        // in the default configuration — which is the bug.
        val src = File(
            "src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt"
        ).readText()
        val block = src.substring(src.indexOf("private fun writeResultsIfNeeded"))
            .substringBefore("private fun stepDescription")
        assertTrue("the notice is no longer emitted when resultsPath is unset",
            block.contains("RunNotices.once("))
        assertTrue("the notice was routed back through the verbose-gated log()",
            !Regex("""\blog\("resultsPath""").containsMatchIn(block))
    }

    @Test
    fun `the notice names what is lost, not just that something is`() {
        // A warning nobody can act on teaches people to stop reading. This one
        // has to name the fields and the setting that keeps them.
        val src = File(
            "src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt"
        ).readText()
        val block = src.substring(src.indexOf("private fun writeResultsIfNeeded"))
            .substringBefore("private fun stepDescription")
        for (needle in listOf("declaredOrientation", "observedOrientation",
                              "resultsPath")) {
            assertTrue("the notice no longer names $needle", block.contains(needle))
        }
    }

    @Test
    fun `the README example names resultsPath`() {
        // The other half: the notice tells a face at run time, the README
        // tells them before they ever run. Measured before the fix: the
        // example named 6 of 17 config fields and not this one.
        val readme = File("../README.md").readText()
        assertTrue("the README config example dropped resultsPath",
            readme.contains("resultsPath ="))
    }
}

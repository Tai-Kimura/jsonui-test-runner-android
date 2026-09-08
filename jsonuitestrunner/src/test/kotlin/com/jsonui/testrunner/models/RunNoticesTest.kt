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

    @Test
    fun `the unstick notice names the sink, not just the rule`() {
        // 🚨 The face that accepted scrollUntilVisible greped for `unstick`,
        // got 0, and nearly reported "the rule never ran". THREE facts make
        // that 0: the rule did not fire; Android discarded System.out; the
        // grep missed logcat's `I/System.out:` tag. A notice that only says
        // "the rule exists" does not separate them — it has to name the sink.
        val src = java.io.File(
            javaClass.classLoader!!.getResource("")!!.path
                .substringBefore("/build/") +
                "/src/main/kotlin/com/jsonui/testrunner/actions/ActionExecutor.kt"
        ).readText()
        val code = src.lines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")

        // ⚠️ PINNED AS ONE CONTIGUOUS BLOCK, INDENTATION INCLUDED. The first
        // draft asserted the pieces separately, and `if (false) RunNotices…`
        // left every piece in place: the guard is a THIRD claim after "calls
        // it" and "says it". This lane made that exact mistake twice earlier
        // tonight and then made it again here — writing the rule down is not
        // the same as applying it.
        val block = "        RunNotices.once(\n" +
            "            \"unstick-output\",\n"
        assertTrue(
            "the unstick notice is no longer emitted unconditionally at the " +
                "top of the flush — a guard, a rename or a deletion all land here",
            code.contains(block)
        )

        // ⚠️ The sink must be named IN THE NOTICE, not merely somewhere in
        // the file. The first draft matched `System.out` anywhere in the
        // method, so a mutation that removed it from the sentence still
        // passed on the word's other occurrence.
        val notice = code.substringAfter(block).substringBefore(")\n")
        assertTrue("the notice must name System.out as the sink",
            notice.contains("System.out"))
        assertTrue("the notice must name logcat's tag, or a grep will miss it",
            notice.contains("I/System.out:"))
        assertTrue("the notice must say an absent line is not evidence",
            notice.contains("not evidence"))
        assertTrue(
            "the notice must NOT be routed through the verbose-gated log()",
            !code.contains("log(\"[ActionExecutor] unstick")
        )
    }
}

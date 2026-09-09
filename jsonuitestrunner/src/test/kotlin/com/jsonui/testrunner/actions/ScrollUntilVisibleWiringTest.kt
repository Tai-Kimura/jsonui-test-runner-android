package com.jsonui.testrunner.actions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-09-09 report is about WIRING, not arithmetic: every pure function
 * involved was already green and stayed green while the step returned with a
 * moving target. [ViewportMargin] and [TargetSettle] can only say what the
 * rule decides; whether the rule RUNS, and whether a settle follows the swipe
 * or precedes it, lives in the order of statements in ActionExecutor.
 *
 * So this reads the source. A source-reading arm cannot tell an
 * implementation from a sentence ABOUT the implementation, and this file's
 * subject is now covered in comments that name `awaitTargetSettled` and
 * `unstickFromTrailingEdge` a dozen times — an arm that matched its own
 * documentation would pass on a tree where the code was deleted. Comments and
 * string literals are therefore stripped before anything is asserted, and
 * [codeOnlyActuallyStrippedBothKinds] is the control that proves the
 * stripping happened rather than being assumed.
 */
class ScrollUntilVisibleWiringTest {

    private val source: String by lazy {
        val rel = "src/main/kotlin/com/jsonui/testrunner/actions/ActionExecutor.kt"
        val candidates = listOf(
            File(rel),
            File("jsonuitestrunner/$rel"),
            File("../jsonuitestrunner/$rel")
        )
        val found = candidates.firstOrNull { it.isFile }
        // Fail, never skip: a fixture this arm cannot find is a broken arm,
        // and a skipped arm reports the same green as a passing one.
        assertTrue(
            "ActionExecutor.kt not found from ${File(".").absolutePath}; tried " +
                candidates.joinToString { it.path },
            found != null
        )
        found!!.readText()
    }

    /** Source with comments and string literals removed. */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    while (i < text.length && text[i] != quote) {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                    sb.append("<lit>")
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Body of [name] from the stripped source, by brace matching. */
    private fun bodyOf(name: String): String {
        val code = codeOnly(source)
        val at = code.indexOf("private fun $name(")
        assertTrue("no declaration of $name in the stripped source", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("no body for $name", open >= 0)
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}') {
                depth--
                if (depth == 0) return code.substring(open, i + 1)
            }
            i++
        }
        throw AssertionError("unbalanced braces in $name")
    }

    /** Occurrences of each of [tokens] in [body], in the order they appear. */
    private fun sequenceOf(body: String, tokens: List<String>): List<String> =
        tokens.flatMap { t ->
            val hits = mutableListOf<Pair<Int, String>>()
            var from = 0
            while (true) {
                val at = body.indexOf(t, from)
                if (at < 0) break
                hits.add(at to t)
                from = at + 1
            }
            hits
        }.sortedBy { it.first }.map { it.second }

    /**
     * The control for every other arm here. Each half moves a real 1 -> 0: a
     * spelling that exists ONLY in a comment, and one that exists ONLY inside
     * a string literal. Asserting they are present in the raw text first is
     * what keeps this from passing because the file failed to load.
     */
    @Test
    fun codeOnlyActuallyStrippedBothKinds() {
        val onlyInAComment = "MID-FLING"
        val onlyInAString = "flush at "
        assertTrue("comment probe absent from raw source", source.contains(onlyInAComment))
        assertTrue("string probe absent from raw source", source.contains(onlyInAString))

        val code = codeOnly(source)
        assertFalse("comments were not stripped", code.contains(onlyInAComment))
        assertFalse("string literals were not stripped", code.contains(onlyInAString))
        // Positive control: the stripper kept the code it is supposed to keep.
        assertTrue(code.contains("private fun executeScrollUntilVisible"))
        assertTrue(code.contains("private fun unstickFromTrailingEdge"))
    }

    /**
     * 1.14.0 ran the trailing-edge clearance rule on ONE of the three exits —
     * the early return taken when the target was already visible. The two
     * scroll legs return the instant the target appears, which scrolling down
     * is the instant it has entered from the trailing edge: the exits most
     * likely to leave a target flush were the exits the rule never reached.
     *
     * The rule was reviewed per-RULE and shipped with two of its three call
     * sites bare, so the pin is per-CALL-SITE: this asserts the exact
     * sequence, not a count, because a count is satisfied by three unsticks
     * on one exit.
     */
    @Test
    fun everyExitOfScrollUntilVisibleRunsTheClearanceRule() {
        val body = bodyOf("executeScrollUntilVisible")
        val seq = sequenceOf(body, listOf("awaitTargetSettled(", "unstickFromTrailingEdge("))
        assertEquals(
            listOf(
                "awaitTargetSettled(", "unstickFromTrailingEdge(",
                "awaitTargetSettled(", "unstickFromTrailingEdge(",
                "awaitTargetSettled(", "unstickFromTrailingEdge("
            ),
            seq
        )
    }

    /**
     * (b) of the report: this helper is the LAST thing scrollUntilVisible
     * does, so its own swipe was the one motion no settle covered — the next
     * step tapped a sliding target. Both swipes must be followed by a settle.
     *
     * (a) of the report: `keepScrolledPosition` is the whole "never make it
     * worse" guarantee, and it was reading bounds mid-fling — the reporter
     * logged after=1184 for a target that came to rest at ~1157. So the
     * settle must sit BETWEEN the swipe and the re-measure, which an ordering
     * pin states and a pair of presence checks does not.
     */
    @Test
    fun everyMotionTheClearanceRuleMakesIsSettledBeforeItIsMeasured() {
        val body = bodyOf("unstickFromTrailingEdge")
        assertFalse(
            "waitForIdle does not wait out a Compose fling under a bare UiAutomator",
            body.contains("waitForIdle")
        )
        val seq = sequenceOf(
            body,
            listOf("device.swipe(", "awaitTargetSettled(", "keepScrolledPosition(")
        )
        assertEquals(
            listOf(
                "device.swipe(",            // clearance scroll
                "awaitTargetSettled(",      // ...settled BEFORE it is read
                "keepScrolledPosition(",    // ...and the guard reads a resting value
                "device.swipe(",            // rollback, when the guard said revert
                "awaitTargetSettled("       // ...and it is the last motion, so it settles too
            ),
            seq
        )
    }
}

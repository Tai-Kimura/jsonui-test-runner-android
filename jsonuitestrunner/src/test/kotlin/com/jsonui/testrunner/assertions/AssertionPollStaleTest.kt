package com.jsonui.testrunner.assertions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polling loop must survive the very race it exists to absorb.
 *
 * `assertText` polls because a state-driven UI updates bound text
 * asynchronously — the file says so itself. That same recomposition replaces
 * the a11y node behind a held handle, and uiautomator then raises
 * `StaleObjectException`, which `javap` reports as
 *
 *     public class androidx.test.uiautomator.StaleObjectException
 *         extends java.lang.RuntimeException
 *
 * — NOT an `AssertionError`. A loop that catches only `AssertionError`
 * therefore cannot see it: the poll is abandoned on its first occurrence and
 * the failure surfaces as an opaque uiautomator exception instead of the
 * assertion's own message. The run survives (the per-case catch is
 * `Throwable`), so the cost is the 100ms retry granularity the poll was built
 * to provide, traded for whole-case retry.
 *
 * These arms read the source. `AssertionExecutor` needs a `UiDevice`, which a
 * JVM test cannot construct, so the wiring is checked by reading it — and a
 * source-reading arm cannot tell an implementation from a sentence about one,
 * which is why comments and string literals are stripped first and
 * [strippingActuallyHappened] is the control that proves the stripping ran.
 */
class AssertionPollStaleTest {

    private val source: String by lazy {
        val rel = "src/main/kotlin/com/jsonui/testrunner/assertions/AssertionExecutor.kt"
        val candidates = listOf(File(rel), File("jsonuitestrunner/$rel"), File("../jsonuitestrunner/$rel"))
        val found = candidates.firstOrNull { it.isFile }
        // Fail, never skip: an arm that cannot find its subject is broken, and
        // a skipped arm reports the same green as a passing one.
        assertTrue(
            "AssertionExecutor.kt not found from ${File(".").absolutePath}; tried " +
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
                c == '/' && next == '/' -> { while (i < text.length && text[i] != '\n') i++ }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    i++
                    sb.append("\"\"")
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private val code: String by lazy { codeOnly(source) }

    @Test
    fun strippingActuallyHappened() {
        // The control for every arm below: if stripping silently did nothing,
        // each arm would be matching prose instead of code.
        assertTrue("comments survived the strip", source.contains("//") && !code.contains("//"))
        assertTrue(
            "the arm's own subject must appear in the PROSE of the file, so a " +
                "match in `code` cannot come from documentation",
            source.contains("recomposition")
        )
        assertTrue("string literals survived the strip", !code.contains("requires 'id'"))
    }

    @Test
    fun theLoopCatchesTheStaleExceptionRatherThanMerelyImportingIt() {
        // ⚠️ `code.contains("StaleObjectException")` is NOT enough: the import
        // line alone satisfies it, so that spelling stays green on a tree where
        // the catch clause was deleted. The clause itself is the subject.
        assertEquals(
            "pollUntil must CATCH StaleObjectException; importing the type " +
                "proves nothing about the retry loop",
            1,
            Regex("catch\\s*\\(\\s*\\w+\\s*:\\s*StaleObjectException").findAll(code).count()
        )
    }

    @Test
    fun bothTransientTypesShareOneRetryBody() {
        // Two catch clauses, one helper: a second copy of the policy is how a
        // future transient type gets handled one way here and another way three
        // lines down.
        assertEquals(
            "AssertionError must still be retried",
            1,
            Regex("catch\\s*\\(\\s*\\w+\\s*:\\s*AssertionError").findAll(code).count()
        )
        assertEquals(
            "both clauses must route through the same body",
            2,
            Regex("retryOrRethrow\\(").findAll(code).count() - 1
        )
    }

    @Test
    fun theTimeoutRethrowsTheFailureThatActuallyHappened() {
        // Throwing a manufactured AssertionError here would point the reader at
        // a value comparison that never ran.
        assertTrue(
            "the timeout branch must rethrow the caught throwable itself",
            Regex(">=\\s*timeout\\)\\s*throw\\s+last").containsMatchIn(code)
        )
    }

    @Test
    fun theStaleTypeIsImportedRatherThanMatchedByName() {
        assertTrue(
            "the handling must reference the real type, not a string that " +
                "happens to spell it",
            code.contains("import androidx.test.uiautomator.StaleObjectException")
        )
    }

    @Test
    fun theParentClassIsNotCaught() {
        // Catching RuntimeException would absorb unrelated failures into the
        // poll and turn them into a timeout — a defect-hiding direction.
        assertEquals(
            "catch (e: RuntimeException) would swallow unrelated failures",
            0,
            Regex("catch\\s*\\(\\s*\\w+\\s*:\\s*RuntimeException").findAll(code).count()
        )
    }
}

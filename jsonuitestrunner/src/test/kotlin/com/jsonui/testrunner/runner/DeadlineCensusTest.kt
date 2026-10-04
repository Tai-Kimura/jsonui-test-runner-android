package com.jsonui.testrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No wait in this driver is measured on the wall clock (ticket
 * android-driver-misses-a-present-resource-id-for-25s: a `waitFor` gave up at
 * once when the guest's wall clock was set forward mid-wait). Waits use
 * [Deadline], which is monotonic.
 *
 * 1.15.8 had 14 wall-clock deadlines, all moved to [Deadline]:
 * ActionExecutor 10 (waitForElement — every `waitFor` and tap goes through
 * it —, waitForAny, alertTap, selectOption's sheet wait and its option-text
 * wait, the two scroll legs' budgets, the gesture-scroll loop, the
 * semantics-scroll loop, the settle budget), AssertionExecutor 3
 * (assertScreen, the assertion poll, waitForElement) and ScreenRecorder 1
 * (the wait for screenrecord's file).
 *
 * Every `System.currentTimeMillis()` left in the code (comments and string
 * literals stripped) is a LABEL, counted per file below with its reason. A
 * new one fails here until it is either a [Deadline] or a label added to this
 * table. A comparison against the wall clock fails whatever the count.
 */
class DeadlineCensusTest {

    private val labels = mapOf(
        // durationMs / totalDurationMs in results, and the case wall time in
        // recording.json (a wall time far above the step budgets is how a
        // clock jump shows up there).
        "runner/JsonUITestRunner.kt" to 14,
        // A mock Location's fix time is a wall-clock timestamp by contract.
        "actions/ActionExecutor.kt" to 1,
        // Deadline's own wall clock, read only to report how far it moved.
        "runner/Deadline.kt" to 1,
    )

    private val root: File by lazy {
        val rel = "src/main/kotlin/com/jsonui/testrunner"
        val found = listOf(File(rel), File("jsonuitestrunner/$rel"), File("../jsonuitestrunner/$rel"))
            .firstOrNull { it.isDirectory }
        // Fail, never skip: an arm that cannot find its subject reports green.
        assertTrue("driver sources not found from ${File(".").absolutePath}", found != null)
        found!!
    }

    /** Source with comments and string literals removed (string templates go with them). */
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

    private val sources: Map<String, String> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to codeOnly(it.readText()) }
    }

    private val wallRead = Regex("""System\.currentTimeMillis\(\)""")

    /** A line that compares against the wall clock: a deadline in disguise. */
    private fun comparesWallClock(line: String): Boolean =
        wallRead.containsMatchIn(line) && Regex("""[<>]=?""").containsMatchIn(line.replace("->", ""))

    @Test
    fun everyWallClockReadIsALabelInTheTable() {
        val counts = sources.mapValues { (_, code) -> wallRead.findAll(code).count() }.filterValues { it > 0 }
        assertEquals(
            "wall-clock reads per file (a new one is a Deadline or a labelled entry here)",
            labels.toSortedMap(), counts.toSortedMap()
        )
    }

    @Test
    fun nothingComparesAgainstTheWallClock() {
        val offenders = sources.flatMap { (file, code) ->
            code.lines().filter(::comparesWallClock).map { "$file: ${it.trim()}" }
        }
        assertEquals("wall-clock comparisons (use Deadline)", emptyList<String>(), offenders)
    }

    /** Controls: the predicate catches the 1.15.8 loops, and the strip ran. */
    @Test
    fun thePredicateCatchesTheOldLoops() {
        assertTrue(comparesWallClock("        while (System.currentTimeMillis() - startTime < timeout) {"))
        assertTrue(comparesWallClock("            if (System.currentTimeMillis() >= deadline) break"))
        assertTrue(comparesWallClock("        while (System.currentTimeMillis() < deadline) {"))
        assertTrue(!comparesWallClock("            durationMs = System.currentTimeMillis() - startTime"))
        assertTrue(!comparesWallClock("        ids.forEach { x -> System.currentTimeMillis() }"))
        val stripped = codeOnly("val a = 1 // System.currentTimeMillis() < x\nval b = \"\${System.currentTimeMillis()}\"")
        assertTrue(stripped, !wallRead.containsMatchIn(stripped))
        assertTrue("the census found the sources", sources.size > 20)
    }
}

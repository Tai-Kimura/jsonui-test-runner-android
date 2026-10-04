package com.jsonui.testrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every wait that looks again after a stale or absent result drops the node
 * cache first ([NodeCache]; ticket test-android-stale-retry-rereads-the-same-
 * cached-node). Until 1.15.10 only recoverFrozenSemantics and ProjectionProbe
 * did, and assertText's poll re-read a stale node for its whole timeout.
 *
 * The population is read from the code: every function whose body both
 * loops (`while`, `for`, `repeat`, a [Deadline.poll]) and finds (a
 * `findObject`/`findObjects`, `findByViewId`, `boundsOf`, an
 * `Until.hasObject` wait, a step re-run or an assertion re-check). Each one
 * must call [NodeCache], or be named below with the reason its loop is not a
 * retry. A new wait is caught here the day it is written.
 */
class NodeCacheCensusTest {

    /** Loops that find, but do not look again after a miss. */
    private val notRetries = mapOf(
        "actions/ActionExecutor.kt#executeInput" to "types characters; its one find is before the loop",
        "actions/ActionExecutor.kt#describeTarget" to "walks a node's ancestors for a failure message",
    )

    private val root: File by lazy {
        val rel = "src/main/kotlin/com/jsonui/testrunner"
        val found = listOf(File(rel), File("jsonuitestrunner/$rel"), File("../jsonuitestrunner/$rel"))
            .firstOrNull { it.isDirectory }
        assertTrue("driver sources not found from ${File(".").absolutePath}", found != null)
        found!!
    }

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

    /** Function name -> body (from its first `{` to the matching `}`). */
    private fun functions(code: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""").findAll(code).forEach { m ->
            val open = code.indexOf('{', m.range.last)
            if (open < 0) return@forEach
            var depth = 0
            var k = open
            while (k < code.length) {
                if (code[k] == '{') depth++
                if (code[k] == '}') { depth--; if (depth == 0) break }
                k++
            }
            out.putIfAbsent(m.groupValues[1], code.substring(open, minOf(k + 1, code.length)))
        }
        return out
    }

    private val loop = Regex("""\bwhile\s*\(|\bfor\s*\(|\brepeat\s*\(|\.poll\s*[({]""")
    private val find = Regex("""findObjects?\(|findByViewId\(|boundsOf\(|Until\.hasObject|executeOnce\(|\bcheck\(\)""")

    private fun waits(): Map<String, String> =
        listOf("actions", "assertions", "runner").flatMap { dir ->
            File(root, dir).listFiles { f -> f.extension == "kt" }.orEmpty().flatMap { file ->
                functions(codeOnly(file.readText()))
                    .filterValues { loop.containsMatchIn(it) && find.containsMatchIn(it) }
                    .map { (name, body) -> "$dir/${file.name}#$name" to body }
            }
        }.toMap()

    @Test
    fun everyWaitThatLooksAgainDropsTheCache() {
        val missing = waits().filter { (key, body) -> key !in notRetries && !body.contains("NodeCache.") }.keys
        assertEquals("waits that look again without NodeCache (clear it, or name the reason in notRetries)",
            emptySet<String>(), missing)
    }

    @Test
    fun theNamedExceptionsStillExist() {
        assertEquals("notRetries entries the census no longer finds", emptySet<String>(), notRetries.keys - waits().keys)
    }

    /** The population, printed so a reader sees what the census covers, and its control. */
    @Test
    fun theCensusSeesTheWaitsAndCatchesAMissingClear() {
        val all = waits()
        println("[NodeCacheCensusTest] ${all.size} waits: ${all.keys.sorted()}")
        listOf(
            "actions/ActionExecutor.kt#execute", "actions/ActionExecutor.kt#waitForElement",
            "assertions/AssertionExecutor.kt#pollUntil", "actions/ActionExecutor.kt#awaitTargetSettled",
            "actions/ActionExecutor.kt#semanticsScrollUntilVisible",
        ).forEach { assertTrue("$it is not in the census: ${all.keys}", it in all) }
        val planted = functions(codeOnly("fun waitPlanted() { while (true) { device.findObject(x) } }"))
        assertTrue(loop.containsMatchIn(planted.getValue("waitPlanted")) &&
            find.containsMatchIn(planted.getValue("waitPlanted")) &&
            !planted.getValue("waitPlanted").contains("NodeCache."))
        assertTrue("comments are not code", !codeOnly("// NodeCache.clear()").contains("NodeCache."))
    }
}

package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress records (progress.schema.json): the exact keys of each event, the
 * 200-character cut, the attempt-0 skip, and the rule that emitting never
 * changes a result. The runner needs instrumentation; ProgressReporter and
 * ReopenedCases do not, so the record stream is pinned here.
 *
 * Every line a sink receives is also printed as `PROGRESS_RECORD <json>` so
 * the run's JUnit XML can be validated against the canonical schema.
 */
class ProgressReporterTest {

    private val run = "0f8fad5b-d9cb-469f-a165-70867728950e"

    /** A sink that keeps what it receives (and prints it for schema validation). */
    private class Recording : ProgressSink {
        val lines = mutableListOf<String>()
        override fun emit(line: String) {
            println("PROGRESS_RECORD $line")
            lines.add(line)
        }
        val records: List<JsonObject> get() = lines.map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun reporter(sink: ProgressSink?, clock: () -> Long = { 1_000L }) = ProgressReporter(
        sink = sink,
        clock = clock,
        newRunId = { run },
        device = { "Pixel Probe" }
    )

    /** Built records are printed too, for the same schema check. */
    private fun out(r: JsonObject) = r.also { println("PROGRESS_RECORD $it") }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun pass(name: String) = TestResult("T", name, passed = true)
    private fun fail(name: String) = TestResult("T", name, passed = false, error = "boom")
    private fun skip(name: String) = TestResult("T", name, passed = true, skipped = true)

    // MARK: exact keys per event

    @Test
    fun `run_start carries the common keys, files and device - nothing else`() {
        val r = out(ProgressRecords.runStart(run, 5, "android", files = 1, device = "Pixel 9"))
        assertEquals(setOf("v", "t", "platform", "run", "event", "device", "files"), r.keys)
        assertEquals(1, r["v"]!!.jsonPrimitive.int)
        assertEquals(5L, r["t"]!!.jsonPrimitive.long)
        assertEquals("run_start", r.str("event"))
        assertEquals("Pixel 9", r.str("device"))
        assertEquals(1, r["files"]!!.jsonPrimitive.int)
    }

    @Test
    fun `run_start without a device omits the key rather than writing null`() {
        val r = out(ProgressRecords.runStart(run, 5, "android", files = 1, device = null))
        assertEquals(setOf("v", "t", "platform", "run", "event", "files"), r.keys)
    }

    @Test
    fun `case_start and case_end keys`() {
        val start = out(ProgressRecords.caseStart(run, 5, "android", "screens/a.test.json", "A", "c", 1))
        assertEquals(setOf("v", "t", "platform", "run", "event", "file", "test", "case", "attempt"), start.keys)
        val end = out(ProgressRecords.caseEnd(run, 6, "android", "screens/a.test.json", "A", "c", 2, "failed", 17))
        assertEquals(
            setOf("v", "t", "platform", "run", "event", "file", "test", "case", "attempt", "status", "durationMs"),
            end.keys
        )
        assertEquals(2, end["attempt"]!!.jsonPrimitive.int)
        assertEquals("failed", end.str("status"))
        assertEquals(17L, end["durationMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun `run_end keys`() {
        val r = out(ProgressRecords.runEnd(run, 5, "android", 1, 2, 3))
        assertEquals(setOf("v", "t", "platform", "run", "event", "passed", "failed", "skipped"), r.keys)
    }

    // MARK: truncation

    @Test
    fun `200 characters stay whole, 201 are cut to 200 plus an ellipsis`() {
        val at = "a".repeat(200)
        val whole = out(ProgressRecords.caseStart(run, 1, "android", "f", at, "c", 1))
        assertEquals(at, whole.str("test"))
        assertNull(whole["truncated"])

        val over = out(ProgressRecords.caseStart(run, 1, "android", "f", "a".repeat(201), "c", 1))
        assertEquals("a".repeat(200) + "…", over.str("test"))
        assertEquals("true", over.str("truncated"))
    }

    @Test
    fun `the cut counts characters, not UTF-16 units`() {
        // BMP non-ASCII: one unit per character.
        val (kana, kanaCut) = ProgressRecords.cut("あ".repeat(201))
        assertTrue(kanaCut)
        assertEquals("あ".repeat(200) + "…", kana)
        // Astral: two units per character. 150 of them are 300 units but
        // 150 characters — not cut; 201 are cut after the 200th, never mid-pair.
        val face = "😀"
        assertFalse(ProgressRecords.cut(face.repeat(150)).second)
        // 200 of them are 400 units, still 200 characters: whole.
        assertEquals(face.repeat(200) to false, ProgressRecords.cut(face.repeat(200)))
        val (faces, facesCut) = ProgressRecords.cut(face.repeat(201))
        assertTrue(facesCut)
        assertEquals(face.repeat(200) + "…", faces)
        assertEquals(201, faces.codePointCount(0, faces.length))
    }

    @Test
    fun `a long case or file marks the line truncated too`() {
        val case = out(ProgressRecords.caseEnd(run, 1, "android", "f", "t", "c".repeat(201), 1, "passed", 0))
        assertEquals("true", case.str("truncated"))
        val file = out(ProgressRecords.caseStart(run, 1, "android", "d/".repeat(101), "t", "c", 1))
        assertEquals(201, file.str("file").length)
        assertEquals("true", file.str("truncated"))
    }

    // MARK: the record stream

    @Test
    fun `a skipped row is one case_end at attempt 0 with no case_start`() {
        val sink = Recording()
        val progress = reporter(sink)
        progress.run("screens/s.test.json", rows = { it: List<TestResult> -> it }) {
            listOf(progress.row(skip("gated")))
        }
        val skipped = sink.records.filter { it.str("event").startsWith("case_") }
        assertEquals(1, skipped.size)
        val end = skipped.single()
        assertEquals("case_end", end.str("event"))
        assertEquals(0, end["attempt"]!!.jsonPrimitive.int)
        assertEquals("skipped", end.str("status"))
        assertEquals(0L, end["durationMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun `retried case - one pair per attempt, the last pair's status is the case's, run_end counts it once`() {
        val sink = Recording()
        val progress = reporter(sink)
        val suite = progress.run("screens/r.test.json", rows = { s: TestSuiteResult -> s.results }) {
            val rows = mutableListOf<TestResult>()
            rows.add(progress.row(skip("skipped one")))
            var attempt = 0
            var calls = 0
            val (result, attempts) = CaseRetry.run(retries = 2, isPass = { r: TestResult -> r.passed }) {
                attempt++
                progress.attempt("T", "flaky", attempt, isPass = { r: TestResult -> r.passed }) {
                    calls++
                    if (calls < 3) fail("flaky") else pass("flaky")
                }
            }
            rows.add(result.copy(attempts = attempts))
            var hopeless = 0
            val (lost, lostAttempts) = CaseRetry.run(retries = 1, isPass = { r: TestResult -> r.passed }) {
                hopeless++
                progress.attempt("T", "hopeless", hopeless, isPass = { r: TestResult -> r.passed }) { fail("hopeless") }
            }
            rows.add(lost.copy(attempts = lostAttempts))
            TestSuiteResult("T", rows)
        }

        val events = sink.records.map {
            listOf(it.str("event"), it["case"]?.jsonPrimitive?.content, it["attempt"]?.jsonPrimitive?.content, it["status"]?.jsonPrimitive?.content)
        }
        assertEquals(
            listOf(
                listOf("run_start", null, null, null),
                listOf("case_end", "skipped one", "0", "skipped"),
                listOf("case_start", "flaky", "1", null),
                listOf("case_end", "flaky", "1", "failed"),
                listOf("case_start", "flaky", "2", null),
                listOf("case_end", "flaky", "2", "failed"),
                listOf("case_start", "flaky", "3", null),
                listOf("case_end", "flaky", "3", "passed"),
                listOf("case_start", "hopeless", "1", null),
                listOf("case_end", "hopeless", "1", "failed"),
                listOf("case_start", "hopeless", "2", null),
                listOf("case_end", "hopeless", "2", "failed"),
                listOf("run_end", null, null, null)
            ),
            events
        )
        // run_end counts exactly what results.json writes for the same suite.
        val written = ResultsWriter.toJson(listOf(suite), "android", "probe")["suites"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray.map { it.jsonObject.str("status") }
        val end = sink.records.last()
        assertEquals(written.count { it == "passed" }, end["passed"]!!.jsonPrimitive.int)
        assertEquals(written.count { it == "failed" }, end["failed"]!!.jsonPrimitive.int)
        assertEquals(written.count { it == "skipped" }, end["skipped"]!!.jsonPrimitive.int)
        assertEquals(listOf(1, 1, 1), listOf(end["passed"], end["failed"], end["skipped"]).map { it!!.jsonPrimitive.int })
        // One run id and the file on every line; the file '/'-separated.
        assertTrue(sink.records.all { it.str("run") == run && it.str("platform") == "android" })
        assertTrue(sink.records.filter { it.str("event").startsWith("case_") }.all { it.str("file") == "screens/r.test.json" })
    }

    @Test
    fun `durationMs is the attempt's own interval on the clock`() {
        val sink = Recording()
        var now = 100L
        val progress = reporter(sink, clock = { now })
        progress.attempt("T", "c", 1, isPass = { _: Unit -> true }) { now += 42 }
        assertEquals(42L, sink.records.last()["durationMs"]!!.jsonPrimitive.long)
        assertEquals(100L, sink.records.first()["t"]!!.jsonPrimitive.long)
        assertEquals(142L, sink.records.last()["t"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a row no attempt produced gets a start-end pair at attempt 1 with its status`() {
        val sink = Recording()
        reporter(sink).row(TestResult("T", "teardown", passed = false, durationMs = 0))
        assertEquals(listOf("case_start", "case_end"), sink.records.map { it.str("event") })
        assertEquals("failed", sink.records.last().str("status"))
        assertEquals(1, sink.records.last()["attempt"]!!.jsonPrimitive.int)
    }

    private fun case(name: String, skip: Boolean = false) = TestCase(name = name, skip = if (skip) true else null, steps = emptyList())

    /** The runner's reopen hooks in miniature, rows as TestResult. */
    private fun reopenHooks(
        reopenThrows: (TestCase) -> Boolean = { false },
        bodyPasses: (TestCase, Int) -> Boolean = { _, _ -> true },
        teardownThrows: (String) -> Boolean = { false }
    ): ReopenedCases.Hooks<TestResult> {
        var current = ""
        val calls = mutableMapOf<String, Int>()
        return ReopenedCases.Hooks(
            gate = { tc -> if (tc.skip == true) skip(tc.name) else null },
            reopen = { tc -> current = tc.name; if (reopenThrows(tc)) throw IllegalStateException("launch failed") },
            setup = {},
            body = { tc ->
                val n = (calls[tc.name] ?: 0) + 1
                calls[tc.name] = n
                if (bodyPasses(tc, n)) pass(tc.name) else fail(tc.name)
            },
            teardown = { if (teardownThrows(current)) throw IllegalStateException("teardown failed") },
            setupFailed = { tc, _ -> TestResult("T", tc.name, passed = false, error = "launch", durationMs = 0) },
            teardownFailed = { _, _ -> TestResult("T", "teardown", passed = false, error = "teardown", durationMs = 0) },
            isPass = { it.passed },
            stampAttempts = { r, n -> r.copy(attempts = n) }
        )
    }

    private fun caseLines(records: List<JsonObject>) = records.filter { it.str("event").startsWith("case_") }.map {
        listOf(it.str("event"), it.str("case"), it["attempt"]!!.jsonPrimitive.content, it["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reopen isolation - one pair per attempt, reopen through teardown, numbered from 1`() {
        val sink = Recording()
        val progress = reporter(sink)
        val rows = progress.run("screens/r.test.json", rows = { it: List<TestResult> -> it }) {
            ReopenedCases.run(
                cases = listOf(case("a"), case("b")),
                retries = 2,
                hooks = reopenHooks(bodyPasses = { tc, n -> tc.name == "b" || n >= 2 }),
                progress = ReopenedCases.Progress(progress, "T") { it }
            )
        }
        assertEquals(listOf(true, true), rows.map { it.passed })
        assertEquals(
            listOf(
                listOf("case_start", "a", "1", null), listOf("case_end", "a", "1", "failed"),
                listOf("case_start", "a", "2", null), listOf("case_end", "a", "2", "passed"),
                listOf("case_start", "b", "1", null), listOf("case_end", "b", "1", "passed")
            ),
            caseLines(sink.records)
        )
    }

    @Test
    fun `reopen isolation - a launch failure the driver retries is one pair per real attempt`() {
        val sink = Recording()
        val progress = reporter(sink)
        val rows = progress.run("screens/r.test.json", rows = { it: List<TestResult> -> it }) {
            ReopenedCases.run(
                cases = listOf(case("never launches")),
                retries = 2,
                hooks = reopenHooks(reopenThrows = { true }),
                progress = ReopenedCases.Progress(progress, "T") { it }
            )
        }
        assertEquals(3, rows.single().attempts)
        assertEquals(
            (1..3).flatMap { n ->
                listOf(listOf("case_start", "never launches", "$n", null), listOf("case_end", "never launches", "$n", "failed"))
            },
            caseLines(sink.records)
        )
    }

    /**
     * Each row's final case_end: a case_start at attempt > 1 supersedes the
     * case_end before it for the same (test, case); every other case_end
     * stands — so two `teardown` rows of one file stay two.
     */
    private fun finalCaseEnds(records: List<JsonObject>): List<JsonObject> {
        val finals = mutableListOf<JsonObject>()
        for (r in records) {
            when (r.str("event")) {
                "case_start" -> if (r["attempt"]!!.jsonPrimitive.int > 1) {
                    val i = finals.indexOfLast { it.str("test") == r.str("test") && it.str("case") == r.str("case") }
                    if (i >= 0) finals.removeAt(i)
                }
                "case_end" -> finals.add(r)
            }
        }
        return finals
    }

    @Test
    fun `every results row has case lines - final case_end statuses add up to run_end and follow the rows`() {
        // One file under reopen: a case whose launch fails (the body never
        // ran), a skipped case, a case that fails, retries and still fails, a
        // flaky one that passes on its retry, and a pass whose teardown fails
        // (its own row). Then the two rows the shared path adds without an
        // attempt: a launch / setup failure and the file's teardown row.
        val sink = Recording()
        val progress = reporter(sink)
        val suite = progress.run("screens/x.test.json", rows = { s: TestSuiteResult -> s.results }) {
            val rows = ReopenedCases.run(
                cases = listOf(case("launch fails"), case("skipped", skip = true), case("fails"), case("flaky"), case("passes, teardown fails")),
                retries = 1,
                hooks = reopenHooks(
                    reopenThrows = { it.name == "launch fails" },
                    bodyPasses = { tc, n -> tc.name != "fails" && (tc.name != "flaky" || n >= 2) },
                    teardownThrows = { it == "passes, teardown fails" }
                ),
                progress = ReopenedCases.Progress(progress, "T") { it }
            ).toMutableList()
            rows.add(progress.row(TestResult("T", "setup never confirmed", passed = false, error = "setup", durationMs = 0)))
            rows.add(progress.row(TestResult("T", "teardown", passed = false, error = "teardown", durationMs = 0)))
            TestSuiteResult("T", rows)
        }
        val records = sink.records
        val written = ResultsWriter.toJson(listOf(suite), "android", "probe")["suites"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        val finals = finalCaseEnds(records)

        // (test, case, status) of the final case_ends == the results rows, in order.
        assertEquals(
            written.map { Triple(it.str("testName"), it.str("caseName"), it.str("status")) },
            finals.map { Triple(it.str("test"), it.str("case"), it.str("status")) }
        )
        // Counted per status, they are run_end.
        val end = records.last()
        assertEquals("run_end", end.str("event"))
        for (status in listOf("passed", "failed", "skipped")) {
            assertEquals(status, finals.count { it.str("status") == status }, end[status]!!.jsonPrimitive.int)
        }
        assertEquals(listOf(2, 5, 1), listOf("passed", "failed", "skipped").map { end[it]!!.jsonPrimitive.int })
        // The rows that never ran an attempt: one pair at attempt 1 (the
        // teardown rows under that row's own test / case), the skip only its
        // attempt-0 end.
        val td = records.filter { it["case"]?.jsonPrimitive?.content == "teardown" }
        assertEquals(listOf("case_start", "case_end", "case_start", "case_end"), td.map { it.str("event") })
        assertTrue(td.all { it.str("test") == "T" && it["attempt"]!!.jsonPrimitive.int == 1 })
        assertEquals(
            listOf(listOf("case_end", "skipped", "0", "skipped")),
            caseLines(records).filter { it[1] == "skipped" }
        )
    }

    @Test
    fun `a test with no source file is file inline`() {
        val sink = Recording()
        val progress = reporter(sink)
        progress.run("", rows = { it: List<TestResult> -> it }) {
            listOf(progress.attempt("T", "c", 1, isPass = { r: TestResult -> r.passed }) { pass("c") })
        }
        assertTrue(sink.records.filter { it.str("event").startsWith("case_") }.all { it.str("file") == "inline" })
        progress.run("screens\\win.test.json", rows = { it: List<TestResult> -> it }) { emptyList() }
        assertEquals("screens/win.test.json", progress.fileOf("screens\\win.test.json"))
        // The default before any run is inline too, so a stray line is valid.
        assertEquals("inline", ProgressRecords.INLINE_FILE)
    }

    // MARK: emitting never changes the test

    @Test
    fun `a missing sink does nothing and returns every result unchanged`() {
        val progress = reporter(null)
        val row = fail("x")
        val back = progress.run("f", rows = { it: List<TestResult> -> it }) {
            listOf(progress.attempt("T", "x", 1, isPass = { r: TestResult -> r.passed }) { row }, progress.row(skip("y")))
        }
        assertSame(row, back[0])
        assertTrue(back[1].skipped)
    }

    @Test
    fun `a sink that throws changes no result and throws nothing out`() {
        val throwing = ProgressSink { throw IllegalStateException("logcat is gone") }
        val progress = reporter(throwing)
        val failed = fail("x")
        val passed = pass("y")
        val suite = progress.run("f", rows = { s: TestSuiteResult -> s.results }) {
            var n = 0
            val (result, attempts) = CaseRetry.run(retries = 1, isPass = { r: TestResult -> r.passed }) {
                n++
                progress.attempt("T", "x", n, isPass = { r: TestResult -> r.passed }) { failed }
            }
            TestSuiteResult("T", listOf(
                result.copy(attempts = attempts),
                progress.attempt("T", "y", 1, isPass = { r: TestResult -> r.passed }) { passed },
                progress.row(skip("z"))
            ))
        }
        assertEquals(listOf("failed", "passed", "skipped"), suite.results.map { ProgressRecords.status(it) })
        assertEquals(2, suite.results[0].attempts)
        assertEquals("boom", suite.results[0].error)
        assertSame(passed, suite.results[1])
    }

    @Test
    fun `the real logcat sink throws on the JVM and the reporter swallows it`() {
        // android.util.Log is a stub off-device: this is the default sink
        // failing for real, not a staged one.
        val progress = ProgressReporter(newRunId = { run }, device = { null })
        val row = pass("x")
        assertSame(row, progress.attempt("T", "x", 1, isPass = { r: TestResult -> r.passed }) { row })
    }
}

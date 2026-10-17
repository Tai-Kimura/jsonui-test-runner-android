package com.jsonui.testrunner.runner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jsonui.testrunner.JsonUITest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the progress records (progress.schema.json): runs a
 * real test file from this APK's assets through the real runner and reads
 * back what reached the sink.
 *
 * The file has a pass, a deterministic failure (an id no screen has) run
 * twice under caseRetries = 1, a `skip: true` case, a platform-gated case,
 * a case name of exactly 200 characters (kept whole), one past the cut, and
 * a teardown that fails (its own `teardown` row). The sink tees into the real
 * [LogcatProgressSink], so the same lines can be read off the device:
 *   adb -s <serial> logcat -v raw -s JsonUITestProgress:I
 *
 * The second test makes the sink throw and requires the very same results:
 * emitting must never change a test.
 *
 * Execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.jsonui.testrunner.runner.ProgressOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class ProgressOnDeviceTest {

    private val assetPath = "jsonui-tests/screens/progress_probe.test.json"

    private fun runProbe(sink: ProgressSink): List<JsonObject> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val loaded = JsonUITest.loadFromAssets(context, assetPath)
        val runner = JsonUITestRunner(
            TestRunnerConfig(
                caseRetries = 1,
                defaultTimeout = 500L,
                screenshotOnFailure = false,
                verifyScreenTransitions = false
            )
        )
        runner.progress = ProgressReporter(sink = sink)
        val suite = runner.run(loaded)
        return ResultsWriter.toJson(listOf(suite), platform = "android", generatedAt = "probe")["suites"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private val expectedStatuses = listOf("passed", "failed", "skipped", "skipped", "passed", "passed", "failed")

    @Test
    fun aRealRunEmitsOneRecordStreamThatMatchesItsResults() {
        val lines = mutableListOf<String>()
        val rows = runProbe { line ->
            lines.add(line)
            LogcatProgressSink.emit(line)
        }
        val records = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(expectedStatuses, rows.map { it.str("status") })

        // run_start → (case_start / case_end)* → run_end, one run id.
        assertEquals("run_start", records.first().str("event"))
        assertEquals("run_end", records.last().str("event"))
        val cases = records.subList(1, records.size - 1)
        assertTrue(cases.all { it.str("event") == "case_start" || it.str("event") == "case_end" })
        assertEquals(1, records.map { it.str("run") }.toSet().size)
        assertEquals(1, records.first()["files"]!!.jsonPrimitive.int)
        assertEquals(android.os.Build.MODEL, records.first().str("device"))
        assertTrue(cases.all { it.str("file") == assetPath && it.str("test") == "ProgressProbe" })

        // (case, attempt, status) per case_end; a case_start right before
        // every case_end except the skipped ones (attempt 0).
        val ends = cases.filter { it.str("event") == "case_end" }
        val atLimit = rows[4].str("caseName")
        assertEquals(200, atLimit.codePointCount(0, atLimit.length))
        val long = rows[5].str("caseName")
        val longCut = long.substring(0, long.offsetByCodePoints(0, 200)) + "…"
        assertEquals(
            listOf(
                Triple("settles first try", 1, "passed"),
                Triple("hopeless", 1, "failed"),
                Triple("hopeless", 2, "failed"),
                Triple("skipped by flag", 0, "skipped"),
                Triple("ios only", 0, "skipped"),
                Triple(atLimit, 1, "passed"),
                Triple(longCut, 1, "passed"),
                Triple("teardown", 1, "failed")
            ),
            ends.map { Triple(it.str("case"), it["attempt"]!!.jsonPrimitive.int, it.str("status")) }
        )
        assertEquals(null, ends[5]["truncated"])
        assertEquals("true", ends[6].str("truncated"))
        for ((i, record) in cases.withIndex()) {
            if (record.str("event") != "case_end" || record["attempt"]!!.jsonPrimitive.int == 0) continue
            val start = cases[i - 1]
            assertEquals("case_start", start.str("event"))
            assertEquals(record.str("case"), start.str("case"))
            assertEquals(record["attempt"], start["attempt"])
        }

        // Each row's final case_end (a case_start at attempt > 1 supersedes
        // the end before it): its (test, case, status) sequence is the rows'
        // — the cut name compared by prefix, as a reader matches it.
        val finals = mutableListOf<JsonObject>()
        for (r in cases) {
            if (r.str("event") == "case_start" && r["attempt"]!!.jsonPrimitive.int > 1) finals.removeAt(finals.lastIndex)
            if (r.str("event") == "case_end") finals.add(r)
        }
        assertEquals(rows.size, finals.size)
        for ((row, end) in rows.zip(finals)) {
            assertEquals(row.str("testName"), end.str("test"))
            assertEquals(row.str("status"), end.str("status"))
            val case = end.str("case")
            if (end["truncated"] == null) assertEquals(row.str("caseName"), case)
            else assertTrue(row.str("caseName").startsWith(case.removeSuffix("…")))
        }

        // run_end counts what results.json counts — and what the final ends add up to.
        val end = records.last()
        for (status in listOf("passed", "failed", "skipped")) {
            assertEquals(status, rows.count { it.str("status") == status }, end[status]!!.jsonPrimitive.int)
            assertEquals(status, finals.count { it.str("status") == status }, end[status]!!.jsonPrimitive.int)
        }
        assertEquals(listOf(3, 2, 2), listOf("passed", "failed", "skipped").map { end[it]!!.jsonPrimitive.int })
    }

    @Test
    fun aSinkThatThrowsChangesNoResult() {
        var calls = 0
        val rows = runProbe { calls++; throw IllegalStateException("sink is gone") }
        assertTrue("the sink was reached", calls > 0)
        assertEquals(expectedStatuses, rows.map { it.str("status") })
        assertEquals(2, rows[1]["attempts"]!!.jsonPrimitive.int)
    }
}

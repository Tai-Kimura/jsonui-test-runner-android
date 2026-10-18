package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Where a progress line goes. One call per record, the line already
 * serialized (one JSON object, no newline).
 */
internal fun interface ProgressSink {
    fun emit(line: String)
}

/**
 * The Android sink: logcat, tag [TAG], Info. A reader takes the lines with
 * `adb -s <serial> logcat -v raw -s JsonUITestProgress:I` — the serial is
 * what tells it which device they came from (see [ProgressRecords.runStart]).
 */
internal object LogcatProgressSink : ProgressSink {
    const val TAG = "JsonUITestProgress"

    override fun emit(line: String) {
        android.util.Log.i(TAG, line)
    }
}

/**
 * The records themselves (progress.schema.json, v 1). Pure on purpose, like
 * [CaseRetry]: the runner needs instrumentation, the record shapes do not,
 * so the JVM suite pins every key here.
 *
 * Every key the schema allows for an event and nothing else — the schema is
 * `additionalProperties: false`, and no failure message or stack rides along.
 */
internal object ProgressRecords {

    const val VERSION = 1

    /** Longest test / case / file kept whole; a longer one is cut here and ends in [ELLIPSIS]. */
    const val MAX_CHARS = 200
    const val ELLIPSIS = "…"

    /** The `file` of a test that has no source file (built in code / inline JSON). */
    const val INLINE_FILE = "inline"

    /**
     * Cut to [MAX_CHARS] CODE POINTS, not UTF-16 units: the web and iOS
     * drivers count characters, and counting units here would cut a name of
     * astral characters at a different place on this face — the prefix a
     * reader matches against results.json would differ per platform.
     * Returns the value and whether it was cut.
     */
    fun cut(value: String): Pair<String, Boolean> {
        if (value.codePointCount(0, value.length) <= MAX_CHARS) return value to false
        return value.substring(0, value.offsetByCodePoints(0, MAX_CHARS)) + ELLIPSIS to true
    }

    fun runStart(run: String, t: Long, platform: String, files: Int, device: String?): JsonObject =
        buildJsonObject {
            common(run, t, platform, "run_start")
            device?.let { put("device", it) }
            put("files", files)
        }

    fun caseStart(run: String, t: Long, platform: String, file: String, test: String, case: String, attempt: Int): JsonObject =
        buildJsonObject {
            common(run, t, platform, "case_start")
            names(file, test, case)
            put("attempt", attempt)
        }

    /**
     * [status] is "passed" | "failed" for an attempt (attempt >= 1), and
     * "skipped" only with attempt 0 / durationMs 0 — the single record of a
     * case that never ran.
     */
    fun caseEnd(
        run: String, t: Long, platform: String,
        file: String, test: String, case: String,
        attempt: Int, status: String, durationMs: Long
    ): JsonObject =
        buildJsonObject {
            common(run, t, platform, "case_end")
            names(file, test, case)
            put("attempt", attempt)
            put("status", status)
            put("durationMs", durationMs)
        }

    fun runEnd(run: String, t: Long, platform: String, passed: Int, failed: Int, skipped: Int): JsonObject =
        buildJsonObject {
            common(run, t, platform, "run_end")
            put("passed", passed)
            put("failed", failed)
            put("skipped", skipped)
        }

    /** The status results.json writes for [row] — ResultsWriter's own precedence. */
    fun status(row: TestResult): String = when {
        row.skipped -> "skipped"
        row.passed -> "passed"
        else -> "failed"
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.common(run: String, t: Long, platform: String, event: String) {
        put("v", VERSION)
        put("t", t)
        put("platform", platform)
        put("run", run)
        put("event", event)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.names(file: String, test: String, case: String) {
        val (f, fCut) = cut(file)
        val (tn, tCut) = cut(test)
        val (c, cCut) = cut(case)
        put("file", f)
        put("test", tn)
        put("case", c)
        // Only when true: the schema's `truncated` is const true, so a false
        // would make the line invalid, not merely noisy.
        if (fCut || tCut || cCut) put("truncated", true)
    }
}

/**
 * Emits the progress records of one run at a time (a run = one call of
 * `runScreenTest` / `runFlowTest`, which is also what one results.json
 * describes on this driver — the run_end counts are that file's).
 *
 * Emitting NEVER affects the test: every emit swallows what its sink throws,
 * and a null [sink] means do nothing. The [sink], [clock], [newRunId] and
 * [device] are the seams the tests inject; the defaults are the real ones.
 */
internal class ProgressReporter(
    private val sink: ProgressSink? = LogcatProgressSink,
    private val platform: String = "android",
    private val clock: () -> Long = System::currentTimeMillis,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
    private val device: () -> String? = { android.os.Build.MODEL }
) {
    // A fresh id per [run]; this first one only names records emitted
    // outside any run, so that even those stay valid lines.
    private var run = newRunId()
    private var file = ProgressRecords.INLINE_FILE

    /**
     * The run id and `file` the records of the current (or just finished)
     * run carry — read by the default results write so its file names the
     * same run as these lines. `file` as the records carry it (cut).
     */
    val currentRun: String get() = run
    val currentFile: String get() = ProgressRecords.cut(file).first

    /**
     * Run [body] as one run of [files] file(s), [file] being the path the
     * runner read it from: run_start before, run_end after with the counts of
     * the rows [body] returned. A body that throws gets no run_end — a reader
     * sees a run that never finished, which is what happened.
     */
    fun <S> run(file: String, files: Int = 1, rows: (S) -> List<TestResult>, body: () -> S): S {
        run = newRunId()
        this.file = fileOf(file)
        // Read the device inside the emit: Build.MODEL is a stub on the JVM.
        emit { ProgressRecords.runStart(run, clock(), platform, files, device()) }
        val suite = body()
        emit {
            val all = rows(suite)
            ProgressRecords.runEnd(
                run, clock(), platform,
                passed = all.count { ProgressRecords.status(it) == "passed" },
                failed = all.count { ProgressRecords.status(it) == "failed" },
                skipped = all.count { ProgressRecords.status(it) == "skipped" }
            )
        }
        return suite
    }

    /**
     * One attempt ([attempt] >= 1) of a case: case_start, [body], case_end
     * with the attempt's status and duration. Returns [body]'s result as is.
     */
    fun <R> attempt(test: String, case: String, attempt: Int, isPass: (R) -> Boolean, body: () -> R): R {
        val started = clock()
        emit { ProgressRecords.caseStart(run, started, platform, file, test, case, attempt) }
        val result = body()
        emit {
            val now = clock()
            ProgressRecords.caseEnd(
                run, now, platform, file, test, case, attempt,
                status = if (isPass(result)) "passed" else "failed",
                durationMs = maxOf(0L, now - started)
            )
        }
        return result
    }

    /**
     * A row that no attempt produced, recorded when the runner adds it.
     * Skipped → the single case_end at attempt 0. Anything else (a case
     * failed by its file's setup / mocks / launch before it could run, an
     * extra teardown row) → one start/end pair at attempt 1 with the row's
     * status and duration: the schema keeps attempt 0 for skips, and without
     * the pair the run_end counts would exceed what the case_ends add up to.
     */
    fun row(row: TestResult): TestResult {
        if (row.skipped) {
            emit {
                ProgressRecords.caseEnd(run, clock(), platform, file, row.testName, row.caseName, 0, "skipped", 0)
            }
        } else {
            val now = clock()
            emit { ProgressRecords.caseStart(run, now, platform, file, row.testName, row.caseName, 1) }
            emit {
                ProgressRecords.caseEnd(
                    run, now, platform, file, row.testName, row.caseName, 1,
                    ProgressRecords.status(row), maxOf(0L, row.durationMs)
                )
            }
        }
        return row
    }

    /**
     * The `file` of the lines: the path the runner read the test from,
     * '/'-separated; a test with no source file (built in code, or a caller
     * that passed no path) is "inline" — the same word iOS and
     * `TestLoader.loadFromString` use.
     */
    internal fun fileOf(path: String): String =
        if (path.isBlank()) ProgressRecords.INLINE_FILE else path.replace('\\', '/')

    /** The one place a record leaves; nothing it throws reaches the test. */
    private fun emit(record: () -> JsonObject) {
        val target = sink ?: return
        try {
            target.emit(record().toString())
        } catch (t: Throwable) {
            // Same line the runner draws: never swallow OOM / StackOverflow.
            if (t is VirtualMachineError) throw t
        }
    }
}

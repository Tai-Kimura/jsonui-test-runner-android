package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestResult

/**
 * The per-case sequence for `caseIsolation: "reopen"`
 * (screen-test.schema.json): reopen → setup → case → teardown, for every case.
 *
 * Pure on purpose, like [CaseRetry]: the runner needs instrumentation, the
 * ordering and its failure semantics do not, so the JVM suite pins them here.
 */
internal object ReopenedCases {

    class Hooks<R>(
        /** The skipped row for a case gated out (skip / platform / responsive), or null. */
        val gate: (TestCase) -> R?,
        /**
         * Bring the screen up afresh for this case (re-apply mocks and launch
         * state, relaunch with the case's initialState, re-apply orientation,
         * wait for the receipt).
         */
        val reopen: (TestCase) -> Unit,
        val setup: () -> Unit,
        val body: (TestCase) -> R,
        val teardown: () -> Unit,
        /** The row for a case whose reopen or setup threw; the body never ran. */
        val setupFailed: (TestCase, Throwable) -> R,
        /** The extra row recorded when a case's teardown threw. */
        val teardownFailed: (TestCase, Throwable) -> R,
        val isPass: (R) -> Boolean,
        val stampAttempts: (R, Int) -> R,
        /** Rethrow what must never be recorded as a row (VirtualMachineError). */
        val rethrowIfFatal: (Throwable) -> Unit = {},
        val onRetry: (attemptNumber: Int, maxAttempts: Int) -> Unit = { _, _ -> }
    )

    /**
     * Where this loop's progress records go (progress.schema.json): every row
     * it returns gets its case lines HERE, so no caller can add a row the
     * records miss. [testName] is the file's testName; [asRow] reads a row
     * as the TestResult results.json writes for it.
     */
    class Progress<R>(
        val reporter: ProgressReporter,
        val testName: String,
        val asRow: (R) -> TestResult
    )

    /**
     * - A gated case is recorded without reopening anything.
     * - A reopen or setup failure fails that case alone; its teardown still
     *   runs, and the next case reopens independently.
     * - Each retry attempt is a full reopen → setup → body → teardown, so a
     *   retry starts clean. Only the final attempt's teardown failure is
     *   recorded (earlier attempts were superseded, as their results were).
     *
     * With [progress]: a gated row → its attempt-0 case_end; each attempt
     * (reopen through teardown, so a reopen / launch failure the retry loop
     * runs again is one pair per real attempt) → a case_start / case_end
     * pair, attempt 1, 2, ...; a teardown row → its own pair at attempt 1.
     */
    fun <R> run(cases: List<TestCase>, retries: Int, hooks: Hooks<R>, progress: Progress<R>? = null): List<R> {
        val rows = mutableListOf<R>()
        for (testCase in cases) {
            val gated = hooks.gate(testCase)
            if (gated != null) {
                progress?.let { it.reporter.row(it.asRow(gated)) }
                rows.add(gated)
                continue
            }
            var teardownError: Throwable? = null
            var attempt = 0
            val (result, attempts) = CaseRetry.run(
                retries = retries,
                isPass = hooks.isPass,
                onRetry = hooks.onRetry
            ) {
                attempt++
                val body = {
                    val result = try {
                        hooks.reopen(testCase)
                        hooks.setup()
                        hooks.body(testCase)
                    } catch (e: Throwable) {
                        hooks.rethrowIfFatal(e)
                        hooks.setupFailed(testCase, e)
                    }
                    teardownError = null
                    try {
                        hooks.teardown()
                    } catch (e: Throwable) {
                        hooks.rethrowIfFatal(e)
                        teardownError = e
                    }
                    result
                }
                if (progress == null) body()
                else progress.reporter.attempt(progress.testName, testCase.name, attempt, hooks.isPass, body)
            }
            rows.add(hooks.stampAttempts(result, attempts))
            teardownError?.let { error ->
                val row = hooks.teardownFailed(testCase, error)
                progress?.let { it.reporter.row(it.asRow(row)) }
                rows.add(row)
            }
        }
        return rows
    }
}

package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestCase

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
     * - A gated case is recorded without reopening anything.
     * - A reopen or setup failure fails that case alone; its teardown still
     *   runs, and the next case reopens independently.
     * - Each retry attempt is a full reopen → setup → body → teardown, so a
     *   retry starts clean. Only the final attempt's teardown failure is
     *   recorded (earlier attempts were superseded, as their results were).
     */
    fun <R> run(cases: List<TestCase>, retries: Int, hooks: Hooks<R>): List<R> {
        val rows = mutableListOf<R>()
        for (testCase in cases) {
            val gated = hooks.gate(testCase)
            if (gated != null) {
                rows.add(gated)
                continue
            }
            var teardownError: Throwable? = null
            val (result, attempts) = CaseRetry.run(
                retries = retries,
                isPass = hooks.isPass,
                onRetry = hooks.onRetry
            ) {
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
            rows.add(hooks.stampAttempts(result, attempts))
            teardownError?.let { rows.add(hooks.teardownFailed(testCase, it)) }
        }
        return rows
    }
}

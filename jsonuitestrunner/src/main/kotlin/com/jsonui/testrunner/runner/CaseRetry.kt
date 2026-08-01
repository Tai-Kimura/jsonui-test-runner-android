package com.jsonui.testrunner.runner

/**
 * Case-level retry loop: re-invokes [attempt] while the result fails, up to
 * [retries] extra times, and reports the total attempt count.
 *
 * Pure on purpose — the runner itself needs instrumentation (UiDevice), so
 * the attempts/flaky accounting lives here where a JVM unit test can pin it
 * (results.schema.json: attempts = total runs, 1 = settled first try).
 */
internal object CaseRetry {
    fun <T> run(
        retries: Int,
        isPass: (T) -> Boolean,
        onRetry: (attemptNumber: Int, maxAttempts: Int) -> Unit = { _, _ -> },
        attempt: () -> T
    ): Pair<T, Int> {
        val maxAttempts = maxOf(0, retries) + 1
        var attempts = 1
        var result = attempt()
        while (!isPass(result) && attempts < maxAttempts) {
            attempts++
            onRetry(attempts, maxAttempts)
            result = attempt()
        }
        return result to attempts
    }
}

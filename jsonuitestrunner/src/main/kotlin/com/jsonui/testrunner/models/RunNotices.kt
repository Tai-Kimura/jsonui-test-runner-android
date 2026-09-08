package com.jsonui.testrunner.models

/**
 * Things a face must see even when `verbose` is off — printed once per run.
 *
 * 🚨 WHY A SECOND PRINT PATH EXISTS. The runner has exactly one `println`, and
 * it sits inside `log()` behind `config.verbose`, whose default is false. So
 * everything the runner "says" is, by default, said to nobody. That is fine
 * for tracing. It is not fine for "I am discarding your results", which is a
 * fact about the run's OUTPUT and cannot be conditional on a debug flag.
 *
 * Measured 2026-09-08: a consumer lane could not tell whether its cases ran in
 * the orientation they declared. The driver computes `declaredOrientation` and
 * `observedOrientation` per case and writes both to the results JSON — but
 * `resultsPath` defaults to null, the writer returns early, and the README's
 * `TestRunnerConfig` example names 6 of 17 fields, `resultsPath` not among
 * them. So the pair was computed and dropped, and nothing said so.
 *
 * ⚠️ Once per PROCESS, not per runner: `JsonUITest.createRunner()` returns a
 * new runner for every test, so an un-deduplicated notice would print once per
 * case — 76 identical lines on the lane that reported this, which is how a
 * notice teaches people to stop reading. Same lifetime reasoning as
 * [RunOrientationFloor], and the same trap: an instance field would look
 * correct and fire every time.
 */
object RunNotices {

    private val said = mutableSetOf<String>()

    /** Print *message* the first time this key is seen in this process. */
    @Synchronized
    fun once(key: String, message: String) {
        if (said.add(key)) {
            println("[JsonUITestRunner] NOTICE: $message")
        }
    }

    /** Testing seam only — a real run never resets. */
    @Synchronized
    fun resetForTest() = said.clear()

    /** What has been said, for arms that need to count rather than capture. */
    fun saidKeys(): Set<String> = said.toSet()
}

package com.jsonui.testrunner.models

/**
 * The orientation a RUN started in, held for the life of the process.
 *
 * 🚨 WHY THIS IS NOT A FIELD ON THE RUNNER. It was, in 1.13.0-alpha01, and it
 * could never fire. `JsonUITest.createRunner()` returns a NEW runner for every
 * test, and `applyRunOrientation` runs once per runner — so the capture and
 * the comparison happened in the same call:
 *
 *     if (runStartOrientation == null) runStartOrientation = observeOrientation()
 *     val wanted = declared ?: tierDefault ?: runStartOrientation
 *     if (observeOrientation() == wanted) { log("already …"); return }
 *
 * With nothing declared, `wanted` WAS the reading taken one line earlier, so
 * the comparison was always true and the floor was always a no-op. Worse, on
 * the next file it captured whatever the previous file's `setOrientation` had
 * left — the floor adopted the state it exists to undo.
 *
 * Found by the reporting lane before release, by reading the runner's
 * lifetime rather than its logic. ⚠️ The alpha's own docstring said "captured
 * ONCE, from the first case, before any rotation" — true of the intent, false
 * of the scope, and the word "once" hid the difference.
 *
 * One instrumentation process runs one lane's whole suite, so process scope is
 * run scope here. ⚠️ Behaviour across a process restart mid-run, or under
 * parallel instrumentation, is NOT measured — if either becomes real, the
 * floor becomes per-process rather than per-run and this note is the place to
 * start.
 */
object RunOrientationFloor {

    @Volatile
    private var captured: String? = null

    @Volatile
    private var hasCaptured: Boolean = false

    /**
     * Record *observed* if nothing has been recorded yet, and return the floor.
     *
     * ⚠️ `hasCaptured` is separate from `captured != null` on purpose: a run
     * whose first observation FAILS (null) must not keep re-capturing on every
     * later file, because by then the device may have been rotated. One
     * attempt, and its answer stands — including "unknown".
     */
    @Synchronized
    fun captureOnce(observed: String?): String? {
        if (!hasCaptured) {
            captured = observed
            hasCaptured = true
        }
        return captured
    }

    /** The floor without recording anything. Null until the first capture. */
    fun current(): String? = captured

    /** Testing seam only — an instrumentation process never resets. */
    @Synchronized
    fun resetForTest() {
        captured = null
        hasCaptured = false
    }
}

package com.jsonui.testrunner.runner

/**
 * A wait's time budget, measured on the MONOTONIC clock — the time the
 * device actually ran — never on the wall clock.
 *
 * Until 1.15.9 every wait in this driver measured its timeout with
 * `System.currentTimeMillis()`. The wall clock is not the time the device
 * ran: on a consumer's run (2026-10-04, ticket android-driver-misses-a-
 * present-resource-id-for-25s) the recording's frames span 1.45 s of the
 * device's monotonic time while the status bar's clock went 8:04 → 8:31 and
 * the case's wall time was 1,641,430 ms. A `waitFor` with a 25 s timeout
 * gave up on the target card that the recording shows drawn at 8:04, and
 * the failure-time dump held it, visible and enabled. The Android half of
 * that run lasted about 14 minutes of real time, so the case did not run for
 * 27. The reading that fits every measurement (not a measured cause): the
 * guest's wall clock, running about 27 minutes behind, was set forward in the
 * middle of the wait, and a wall-clock deadline expired at once. An emulator
 * on the same machine was found 27 minutes behind its host the same morning.
 * A monotonic deadline does not move when the wall clock is set.
 *
 * [poll] always looks once more after the budget runs out: its last action
 * before giving up is a look, never a sleep. The old loops slept, then judged
 * the deadline, and gave up without looking at what the sleep let appear.
 *
 * Wall-clock timestamps that only label things (durations in results, file
 * names, logs) stay on the wall clock. Only deadlines live here
 * (DeadlineCensusTest holds the driver to that).
 */
class Deadline internal constructor(
    private val budgetMs: Long,
    private val monotonicMs: () -> Long,
    private val wallMs: () -> Long
) {
    private val startedAt = monotonicMs()
    private val wallStartedAt = wallMs()

    /** Monotonic time spent since this deadline was set. */
    val elapsedMs: Long get() = monotonicMs() - startedAt

    fun expired(): Boolean = elapsedMs >= budgetMs

    /**
     * How long this wait took on each clock. Equal clocks within a few ms is
     * the normal case; clocks far apart name a clock jump (the wall clock set
     * forward or back) as what the wait went through.
     */
    fun describe(): String {
        val wall = wallMs() - wallStartedAt
        val mono = elapsedMs
        val jump = if (kotlin.math.abs(wall - mono) > CLOCK_JUMP_NOTICE_MS) {
            " — the wall clock moved ${wall - mono}ms more than the device's monotonic clock " +
                "(the wall clock was set during the wait; that is not time the device spent waiting)"
        } else ""
        return "waited ${mono}ms of a ${budgetMs}ms budget (wall clock ${wall}ms)$jump"
    }

    /**
     * Look until [probe] answers, then return the answer; null when the budget
     * ran out. The last action before giving up is always a look: look, judge
     * the deadline, sleep — never sleep, judge, give up.
     */
    fun <T : Any> poll(intervalMs: Long = 100, sleep: (Long) -> Unit = { Thread.sleep(it) }, probe: () -> T?): T? {
        while (true) {
            probe()?.let { return it }
            if (expired()) return null
            sleep(intervalMs)
        }
    }

    companion object {
        /** Clocks this far apart over one wait are worth naming in a failure. */
        const val CLOCK_JUMP_NOTICE_MS = 2_000L

        fun of(budgetMs: Long): Deadline =
            Deadline(budgetMs, { System.nanoTime() / 1_000_000 }, { System.currentTimeMillis() })
    }
}

package com.jsonui.testrunner.runner

/**
 * The machine-readable half of a failed result (`results.schema.json`
 * `failureReason`), beside the prose in `error`.
 *
 * Prose moves between releases. A consumer aggregation matched on a sentence
 * that existed in one driver's 1.9.4 through 1.9.7 and was deleted in 1.9.8,
 * and would have reported zero occurrences of a thing that had only been
 * reworded. `skipReason` has been an enum since it existed; failures had no
 * such channel.
 *
 * The vocabulary is the failure's STAGE, not the exception class. The three
 * drivers share no taxonomy to map — this one throws IllegalArgumentException
 * at 75 sites and AssertionError at 28, ios has five typed enums, web throws
 * a bare Error at 80 — so mapping classes would have produced three different
 * enums, which is the spelling problem rebuilt in a new spelling.
 */
enum class FailureReason(val wireValue: String) {
    /** The target could not be resolved at all. */
    ELEMENT_NOT_FOUND("element-not-found"),
    /** A wait expired. */
    TIMEOUT("timeout"),
    /** An expectation was evaluated and did not hold. */
    ASSERTION("assertion"),
    /** The test file or step is malformed. The suite is wrong, not the app. */
    INVALID_TEST("invalid-test"),
    /** Talking to the mock server failed. */
    MOCK("mock"),
    /** The case's own setup steps failed, so the body never ran. */
    SETUP("setup"),
    /** The case's own teardown steps failed. Says nothing about the behaviour under test. */
    TEARDOWN("teardown"),
    /** The app or screen could not be brought up, so nothing was measured. */
    LAUNCH("launch"),
    /** An action ran and did not achieve its effect. */
    ACTION("action"),
    /**
     * Unclassified. Carries no information alone, so a result using it must
     * still carry prose in `error` — and a rising count of it is how this
     * list says it is too short.
     */
    OTHER("other"),
}

object FailureClassifier {

    /**
     * Derive the reason from the throwable a case actually failed with.
     *
     * Returns null when there is nothing to classify — absent must read as
     * "unknown", never as "no reason".
     */
    @JvmStatic
    fun classify(error: Throwable?): FailureReason? {
        if (error == null) return null
        return when (error) {
            is AssertionError -> FailureReason.ASSERTION
            is MockClientException -> FailureReason.MOCK
            is CaseNotFoundException, is NotAScreenTestException -> FailureReason.INVALID_TEST
            is LaunchConfigException -> FailureReason.LAUNCH
            // Ordered after LaunchConfigException, which extends it.
            is IllegalStateException -> FailureReason.OTHER
            // The step could not be executed as written: a missing or
            // unparseable argument, an action this driver does not have.
            is IllegalArgumentException -> FailureReason.INVALID_TEST
            else -> FailureReason.OTHER
        }
    }

    /** Convenience for the writer: the wire spelling, or null. */
    @JvmStatic
    fun wireValue(error: Throwable?): String? = classify(error)?.wireValue
}

/*
 * ⚠️ This driver emits a SUBSET of the vocabulary, and that is a fact about
 * the driver rather than about the apps it runs.
 *
 * TIMEOUT and ELEMENT_NOT_FOUND are never produced here. Both surface as
 * AssertionError — `AssertionExecutor` raises "Element '<id>' not found after
 * <n>ms" — so the exception carries no way to tell a wait that expired from
 * an expectation that failed. Distinguishing them means giving those sites
 * typed exceptions, which is a change to 28 throw sites and is not this one.
 *
 * So: android reporting no `timeout` rows does NOT mean no test timed out.
 * A consumer counting reasons across platforms has to read this as "not
 * distinguished here", the same way an absent field reads as unknown rather
 * than as zero. The alternative — classifying by matching the message text —
 * is the exact practice this field exists to end, and it would be no more
 * durable inside the driver than outside it.
 */

package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `failureReason` (results.schema.json): the machine-readable half of a
 * failed row, beside the prose in `error`.
 *
 * Prose moves between releases — a consumer aggregation matched on a sentence
 * that another driver deleted, and would have reported zero occurrences of a
 * thing that had only been reworded.
 */
class FailureReasonTest {

    @Test
    fun `each throwable this driver raises maps to a stage`() {
        assertEquals(FailureReason.ASSERTION, FailureClassifier.classify(AssertionError("nope")))
        assertEquals(FailureReason.MOCK, FailureClassifier.classify(MockClientException("500")))
        assertEquals(
            FailureReason.INVALID_TEST,
            FailureClassifier.classify(CaseNotFoundException("c", "f.json"))
        )
        assertEquals(
            FailureReason.INVALID_TEST,
            FailureClassifier.classify(IllegalArgumentException("missing 'id'"))
        )
        assertEquals(FailureReason.LAUNCH, FailureClassifier.classify(LaunchConfigException("no activity")))
    }

    @Test
    fun `LaunchConfigException is not swallowed by its IllegalStateException parent`() {
        // It extends IllegalStateException, so branch order decides this. A
        // reordering would silently reclassify every launch failure as
        // unclassified, and nothing else in the suite would notice.
        assertEquals(FailureReason.LAUNCH, FailureClassifier.classify(LaunchConfigException("x")))
        assertEquals(FailureReason.OTHER, FailureClassifier.classify(IllegalStateException("x")))
    }

    @Test
    fun `no throwable is null rather than other`() {
        // null means "nothing to classify"; OTHER means "a failure we could
        // not name". Collapsing them would turn unknown into unclassified.
        assertNull(FailureClassifier.classify(null))
        assertEquals(FailureReason.OTHER, FailureClassifier.classify(Exception("surprise")))
    }

    @Test
    fun `wire spellings are the schema spellings`() {
        assertEquals("element-not-found", FailureReason.ELEMENT_NOT_FOUND.wireValue)
        assertEquals("invalid-test", FailureReason.INVALID_TEST.wireValue)
        assertEquals(
            setOf("element-not-found", "timeout", "assertion", "invalid-test",
                  "mock", "setup", "teardown", "launch", "action", "other"),
            FailureReason.values().map { it.wireValue }.toSet()
        )
    }

    @Test
    fun `the writer emits it only on failed rows`() {
        // The classifier being right does not mean the writer prints it.
        val suite = TestSuiteResult(
            suiteName = "s",
            totalDurationMs = 0,
            results = listOf(
                TestResult("t", "failed one", passed = false, error = "boom",
                           failureReason = "assertion"),
                TestResult("t", "passed one", passed = true),
                TestResult("t", "skipped one", passed = true, skipped = true,
                           skipReason = "platform", failureReason = "assertion"),
            )
        )
        val rows = ResultsWriter.toJson(
            listOf(suite), platform = "android", generatedAt = "2026-09-04T00:00:00Z"
        )["suites"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray

        assertEquals("assertion", rows[0].jsonObject["failureReason"]?.jsonPrimitive?.content)
        assertNull(rows[1].jsonObject["failureReason"])
        // Set on the model but not a failure: the writer must still withhold
        // it, because the validator rejects it on a skipped row.
        assertNull(rows[2].jsonObject["failureReason"])
    }
}

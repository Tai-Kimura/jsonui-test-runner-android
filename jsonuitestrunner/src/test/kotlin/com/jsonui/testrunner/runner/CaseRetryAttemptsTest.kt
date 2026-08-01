package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * attempts/flaky accounting (results.schema.json): the CaseRetry loop is the
 * runner's retry unit (the runner itself needs instrumentation, the loop
 * does not), and ResultsWriter is the emission point.
 */
class CaseRetryAttemptsTest {

    // MARK: CaseRetry loop

    @Test
    fun `passes on the second attempt - two attempts total`() {
        var calls = 0
        val (result, attempts) = CaseRetry.run(
            retries = 2,
            isPass = { it }
        ) {
            calls++
            calls >= 2
        }
        assertTrue(result)
        assertEquals(2, attempts)
        assertEquals(2, calls)
    }

    @Test
    fun `exhausts retries - attempts equals retries plus one`() {
        var calls = 0
        val (result, attempts) = CaseRetry.run(
            retries = 2,
            isPass = { it }
        ) {
            calls++
            false
        }
        assertFalse(result)
        assertEquals(3, attempts)
        assertEquals(3, calls)
    }

    @Test
    fun `zero retries runs exactly once`() {
        var calls = 0
        val (result, attempts) = CaseRetry.run(
            retries = 0,
            isPass = { it }
        ) {
            calls++
            false
        }
        assertFalse(result)
        assertEquals(1, attempts)
        assertEquals(1, calls)
    }

    @Test
    fun `negative retries clamp to a single run`() {
        var calls = 0
        val (_, attempts) = CaseRetry.run(retries = -5, isPass = { it }) {
            calls++
            false
        }
        assertEquals(1, attempts)
        assertEquals(1, calls)
    }

    @Test
    fun `onRetry reports the attempt number about to run`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        CaseRetry.run(
            retries = 2,
            isPass = { it },
            onRetry = { n, max -> seen.add(n to max) }
        ) { false }
        assertEquals(listOf(2 to 3, 3 to 3), seen)
    }

    // MARK: ResultsWriter emission

    private fun row(result: TestResult): JsonObject {
        val suite = TestSuiteResult("S", listOf(result), totalDurationMs = 1)
        val json = ResultsWriter.toJson(listOf(suite), platform = "android", generatedAt = "2026-08-02T00:00:00Z")
        return json["suites"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
    }

    @Test
    fun `first-run pass emits attempts 1 and no flaky`() {
        val entry = row(TestResult("T", "c", passed = true, attempts = 1))
        assertEquals(1, entry["attempts"]!!.jsonPrimitive.int)
        assertNull(entry["flaky"])
    }

    @Test
    fun `retried pass emits attempts and flaky true`() {
        val entry = row(TestResult("T", "c", passed = true, attempts = 2))
        assertEquals(2, entry["attempts"]!!.jsonPrimitive.int)
        assertTrue(entry["flaky"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `exhausted failure emits attempts but never flaky`() {
        val entry = row(TestResult("T", "c", passed = false, error = "x", attempts = 3))
        assertEquals(3, entry["attempts"]!!.jsonPrimitive.int)
        assertNull(entry["flaky"])
    }

    @Test
    fun `skipped rows carry neither attempts nor flaky`() {
        val entry = row(TestResult("T", "c", passed = true, skipped = true))
        assertNull(entry["attempts"])
        assertNull(entry["flaky"])
    }
}

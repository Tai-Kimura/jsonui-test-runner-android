package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for skip-reason precedence (resolveCaseSkip) and its results
 * emission (ResultsWriter skipReason, results.schema.json version 1).
 */
class SkipReasonTest {

    // MARK: resolveCaseSkip precedence

    @Test
    fun noGatesMeansNoSkip() {
        assertNull(resolveCaseSkip(skipFlag = false, platformMet = true, responsiveMet = null))
        assertNull(resolveCaseSkip(skipFlag = false, platformMet = true, responsiveMet = true))
    }

    @Test
    fun explicitSkipFlagHasNoReason() {
        assertEquals(
            CaseSkip(reason = null),
            resolveCaseSkip(skipFlag = true, platformMet = true, responsiveMet = null)
        )
        // skip flag wins over gate mismatches, still without a gate reason
        assertEquals(
            CaseSkip(reason = null),
            resolveCaseSkip(skipFlag = true, platformMet = false, responsiveMet = false)
        )
    }

    @Test
    fun platformMismatchYieldsPlatformReason() {
        assertEquals(
            CaseSkip(reason = "platform"),
            resolveCaseSkip(skipFlag = false, platformMet = false, responsiveMet = null)
        )
    }

    @Test
    fun responsiveMismatchYieldsResponsiveReason() {
        assertEquals(
            CaseSkip(reason = "responsive"),
            resolveCaseSkip(skipFlag = false, platformMet = true, responsiveMet = false)
        )
    }

    @Test
    fun platformWinsWhenBothGatesUnmet() {
        // Deterministic precedence: the static platform gate wins over the
        // window-size-dependent responsive gate when both are unmet.
        assertEquals(
            CaseSkip(reason = "platform"),
            resolveCaseSkip(skipFlag = false, platformMet = false, responsiveMet = false)
        )
    }

    // MARK: ResultsWriter emission

    @Test
    fun resultsWriterEmitsSkipReasonOnlyWhenPresent() {
        val suite = TestSuiteResult(
            suiteName = "login_test",
            results = listOf(
                TestResult("login_test", "ios only", passed = true, skipped = true, skipReason = "platform"),
                TestResult("login_test", "tablet layout", passed = true, skipped = true, skipReason = "responsive"),
                TestResult("login_test", "flaky", passed = true, skipped = true),
                TestResult("login_test", "happy path", passed = true)
            ),
            totalDurationMs = 42
        )

        val json = ResultsWriter.toJson(listOf(suite), platform = "android", generatedAt = "2026-07-10T00:00:00Z")

        // Contract stays version 1 with an optional skipReason field
        assertEquals("jsonui-test-results", json["format"]?.jsonPrimitive?.content)
        assertEquals(1, json["version"]?.jsonPrimitive?.content?.toInt())

        val results = json["suites"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray
        val byCase = results.associateBy { it.jsonObject["caseName"]!!.jsonPrimitive.content }

        assertEquals("platform", byCase["ios only"]!!.jsonObject["skipReason"]?.jsonPrimitive?.content)
        assertEquals("skipped", byCase["ios only"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("responsive", byCase["tablet layout"]!!.jsonObject["skipReason"]?.jsonPrimitive?.content)
        // Plain `skip: true` and non-skipped results carry no skipReason key
        assertFalse(byCase["flaky"]!!.jsonObject.containsKey("skipReason"))
        assertEquals("skipped", byCase["flaky"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        assertFalse(byCase["happy path"]!!.jsonObject.containsKey("skipReason"))
        assertEquals("passed", byCase["happy path"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun skippedSuiteRowsStayVisibleInResults() {
        // A test-level platform skip must emit one skipped row per case
        // (never results: []) — "no silent truncation".
        val suite = TestSuiteResult(
            suiteName = "web_only_test",
            results = listOf(
                TestResult("web_only_test", "case a", passed = true, skipped = true, skipReason = "platform"),
                TestResult("web_only_test", "case b", passed = true, skipped = true, skipReason = "platform")
            ),
            totalDurationMs = 0
        )

        val json = ResultsWriter.toJson(listOf(suite), platform = "android", generatedAt = "2026-07-10T00:00:00Z")
        val results = json["suites"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray

        assertEquals(2, results.size)
        assertTrue(results.all { it.jsonObject["status"]?.jsonPrimitive?.content == "skipped" })
        assertTrue(results.all { it.jsonObject["skipReason"]?.jsonPrimitive?.content == "platform" })
    }
}

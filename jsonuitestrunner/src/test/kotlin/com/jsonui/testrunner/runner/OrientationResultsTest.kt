package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The results JSON carries BOTH halves of the orientation pair, or neither.
 *
 * A row carrying only the request would read as agreement — "it asked for
 * landscape" with nothing saying what it got — which is the shape the pair
 * exists to prevent. This driver is where that mattered: until 1.12.0
 * `"portrait"` mapped to `setOrientationNatural()`, so a landscape-natural
 * tablet reported a passing run that had asked for portrait and executed in
 * landscape, and no field anywhere recorded the difference.
 *
 * MEASURED GAP, so that the coverage here is not read as more than it is:
 * the runner's own half (resolving file > run default, applying the rotation,
 * and reading the device back) needs a `UiDevice` and therefore
 * instrumentation — no JVM arm in this repo can reach it. What a JVM build
 * can check is the emission, which is this file, and the mapping, which is
 * OrientationCommandTest.
 */
class OrientationResultsTest {

    private fun emit(result: TestResult) =
        ResultsWriter.toJson(
            listOf(TestSuiteResult("suite", listOf(result), 1L)),
            platform = "android",
            generatedAt = "2026-09-08T00:00:00Z"
        ).jsonObject["suites"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject

    private fun ran(
        declared: String? = null,
        observed: String? = null,
        passed: Boolean = true
    ) = TestResult(
        testName = "t", caseName = "c", passed = passed,
        declaredOrientation = declared, observedOrientation = observed, durationMs = 1L
    )

    @Test
    fun bothHalvesAreEmittedOnACaseThatRan() {
        val row = emit(ran(declared = "landscape", observed = "landscape"))
        assertEquals("landscape", row["declaredOrientation"]!!.jsonPrimitive.content)
        assertEquals("landscape", row["observedOrientation"]!!.jsonPrimitive.content)
    }

    @Test
    fun aDisagreementSurvivesEmission() {
        // THE arm. This is the row the pair exists to make possible: a case
        // that passed every assertion in an orientation it did not ask for.
        val row = emit(ran(declared = "portrait", observed = "landscape"))
        assertEquals("portrait", row["declaredOrientation"]!!.jsonPrimitive.content)
        assertEquals("landscape", row["observedOrientation"]!!.jsonPrimitive.content)
    }

    @Test
    fun theObservedHalfIsEmittedEvenWithNothingDeclared() {
        // Which orientation a run happened in is worth recording whether or
        // not anybody asked for one — that absence is the state the ticket is
        // about.
        val row = emit(ran(observed = "portrait"))
        assertFalse(row.containsKey("declaredOrientation"))
        assertEquals("portrait", row["observedOrientation"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailedCaseIsStampedToo() {
        val row = emit(ran(declared = "landscape", observed = "portrait", passed = false))
        assertEquals("landscape", row["declaredOrientation"]!!.jsonPrimitive.content)
        assertEquals("portrait", row["observedOrientation"]!!.jsonPrimitive.content)
    }

    @Test
    fun neitherIsEmittedOnASkippedRow() {
        // Same argument as `attempts`: a case that never ran has no
        // orientation it ran in, and a reading taken at skip time would look
        // like one.
        val row = emit(
            TestResult(
                testName = "t", caseName = "c", passed = true, skipped = true,
                skipReason = "platform",
                declaredOrientation = "landscape", observedOrientation = "portrait",
                durationMs = 0L
            )
        )
        assertTrue(row.containsKey("skipReason"))
        assertFalse(row.containsKey("declaredOrientation"))
        assertFalse(row.containsKey("observedOrientation"))
    }

    @Test
    fun aRowWithNeitherHalfCarriesNoOrientationKeys() {
        val row = emit(ran())
        assertFalse(row.containsKey("declaredOrientation"))
        assertFalse(row.containsKey("observedOrientation"))
    }
}

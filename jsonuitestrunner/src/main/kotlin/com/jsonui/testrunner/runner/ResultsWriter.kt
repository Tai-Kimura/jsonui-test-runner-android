package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Serializes run results to the standardized results JSON (schemas/results.schema.json).
 */
object ResultsWriter {

    /** Write suite results to [file] in the jsonui-test-results format. */
    fun write(suites: List<TestSuiteResult>, file: File, platform: String = "android", generatedAt: String) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(suites, platform, generatedAt).toString())
    }

    fun toJson(suites: List<TestSuiteResult>, platform: String, generatedAt: String): JsonObject {
        return buildJsonObject {
            put("format", "jsonui-test-results")
            put("version", 1)
            put("platform", platform)
            put("generatedAt", generatedAt)
            put("suites", buildJsonArray {
                for (suite in suites) {
                    add(suiteToJson(suite))
                }
            })
        }
    }

    private fun suiteToJson(suite: TestSuiteResult): JsonObject {
        return buildJsonObject {
            put("suiteName", suite.suiteName)
            put("totalDurationMs", suite.totalDurationMs)
            put("results", buildJsonArray {
                for (result in suite.results) {
                    add(buildJsonObject {
                        put("testName", result.testName)
                        put("caseName", result.caseName)
                        put("status", when {
                            result.skipped -> "skipped"
                            result.passed -> "passed"
                            else -> "failed"
                        })
                        result.error?.let { put("error", it) }
                        // Distinct gate-skip reasons (platform vs responsive) keep
                        // responsive tests from becoming write-only green skips.
                        result.skipReason?.let { put("skipReason", it) }
                        if (result.warnings.isNotEmpty()) {
                            put("warnings", buildJsonArray {
                                for (warning in result.warnings) add(JsonPrimitive(warning))
                            })
                        }
                        put("durationMs", result.durationMs)
                    })
                }
            })
        }
    }
}

package com.jsonui.testrunner.runner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jsonui.testrunner.JsonUITest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * resultsPath unset → a real run through the real runner leaves its results
 * under /data/local/tmp/jsonui-test-results/<package>/, read back through the
 * shell uid (the reader jui viz / adb pull use), one file named by this run.
 * resultsPath set → no default file for that run.
 *
 * Execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.jsonui.testrunner.runner.DefaultResultsOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class DefaultResultsOnDeviceTest {

    private val assetPath = "jsonui-tests/screens/default_results_probe.test.json"
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pkg = instrumentation.targetContext.packageName
    private val folder = DefaultResults.deviceFolder(pkg)

    private fun runProbe(resultsPath: java.io.File?): Pair<String, List<String>> {
        val loaded = JsonUITest.loadFromAssets(instrumentation.context, assetPath)
        val runner = JsonUITestRunner(
            TestRunnerConfig(
                defaultTimeout = 500L,
                screenshotOnFailure = false,
                verifyScreenTransitions = false,
                resultsPath = resultsPath
            )
        )
        val suite = runner.run(loaded)
        val statuses = suite.results.map { ProgressRecords.status(it) }
        return runner.progress.currentRun to statuses
    }

    private fun ls(): List<String> =
        Shell.exec(instrumentation.uiAutomation, "ls $folder").lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun resultsPathUnsetWritesTheRunToTheDeviceFolder() {
        val (run, statuses) = runProbe(null)
        assertEquals(listOf("passed", "skipped"), statuses)
        val mine = ls().filter { it.contains("-$run-") }
        assertEquals("one file for run $run in $folder: ${ls()}", 1, mine.size)
        val name = mine.single()
        assertTrue(name, DefaultResults.NAME_REGEX.matches(name))
        val text = Shell.exec(instrumentation.uiAutomation, "cat $folder/$name")
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("jsonui-test-results", json["format"]!!.jsonPrimitive.content)
        assertEquals(run, json["run"]!!.jsonPrimitive.content)
        assertEquals(pkg, json["app"]!!.jsonPrimitive.content)
        val suite = json["suites"]!!.jsonArray.single().jsonObject
        assertEquals(assetPath, suite["file"]!!.jsonPrimitive.content)
        assertEquals("DefaultResultsProbe", suite["suiteName"]!!.jsonPrimitive.content)
        assertEquals(statuses, suite["results"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content })
        // No staging file left behind, in either area.
        assertTrue(ls().none { it.startsWith(".") })
        val own = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "jsonui-test-results")
        assertTrue((own.list() ?: emptyArray()).joinToString(), (own.list() ?: emptyArray()).isEmpty())
    }

    @Test
    fun resultsPathSetWritesNoDefaultFile() {
        val out = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "probe-results.json")
        try {
            val (run, _) = runProbe(out)
            assertTrue(ls().none { it.contains("-$run-") })
            val json = Json.parseToJsonElement(out.readText()).jsonObject
            assertEquals(listOf("format", "version", "platform", "generatedAt", "suites"), json.keys.toList())
        } finally {
            out.delete()
        }
    }
}

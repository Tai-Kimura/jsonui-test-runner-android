package com.jsonui.testrunner.runner

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.actions.ActionExecutor
import com.jsonui.testrunner.assertions.AssertionExecutor
import com.jsonui.testrunner.models.FlowTest
import com.jsonui.testrunner.models.FlowTestStep
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestStep
import com.jsonui.testrunner.models.TestSuiteResult

/**
 * Configuration for the test runner
 */
data class TestRunnerConfig(
    val defaultTimeout: Long = 5000L,
    val screenshotOnFailure: Boolean = true,
    val platform: String = "android",
    val verbose: Boolean = false
)

/**
 * Main test runner for JsonUI tests
 */
class JsonUITestRunner(
    private val config: TestRunnerConfig = TestRunnerConfig()
) {
    private val device: UiDevice = UiDevice.getInstance(
        InstrumentationRegistry.getInstrumentation()
    )

    private val actionExecutor = ActionExecutor(device, config.defaultTimeout)
    private val assertionExecutor = AssertionExecutor(device, config.defaultTimeout)

    /** Test loader for file reference resolution */
    var testLoader: TestLoader? = null

    /**
     * Run a loaded test
     */
    fun run(test: LoadedTest): TestSuiteResult {
        return when (test) {
            is LoadedTest.Screen -> runScreenTest(test.test, test.filePath)
            is LoadedTest.Flow -> runFlowTest(test.test, test.filePath)
        }
    }

    /**
     * Run a screen test
     */
    fun runScreenTest(test: ScreenTest, testPath: String = ""): TestSuiteResult {
        val results = mutableListOf<TestResult>()
        val startTime = System.currentTimeMillis()

        // Wait for UI to be ready (app may need time to render)
        // Compose UI needs extra time for semantics tree to be built
        log("Waiting for UI to be ready...")
        Thread.sleep(2000)
        device.waitForIdle(config.defaultTimeout)
        Thread.sleep(500) // Additional wait for Compose semantics

        // Debug: dump UI hierarchy at start
        log("Dumping UI hierarchy at test start...")
        try {
            val file = java.io.File.createTempFile("ui_dump_start", ".xml")
            device.dumpWindowHierarchy(file)
            val content = file.readText()
            log("UI Hierarchy XML (first 8000 chars):")
            log(content.take(8000))
            file.delete()
        } catch (e: Exception) {
            log("Failed to dump hierarchy: ${e.message}")
        }

        // Check platform compatibility
        test.platform?.let { platform ->
            if (!platform.includes(config.platform)) {
                log("Skipping test - platform mismatch")
                return TestSuiteResult(
                    suiteName = test.metadata.name,
                    results = emptyList(),
                    totalDurationMs = 0
                )
            }
        }

        // Run setup
        test.setup?.let { setup ->
            log("Running setup...")
            try {
                executeSteps(setup)
            } catch (e: Exception) {
                log("Setup failed: ${e.message}")
                throw e
            }
        }

        // Run test cases
        for (testCase in test.cases) {
            val result = runTestCase(test.metadata.name, testCase)
            results.add(result)
        }

        // Run teardown
        test.teardown?.let { teardown ->
            log("Running teardown...")
            try {
                executeSteps(teardown)
            } catch (e: Exception) {
                log("Teardown failed: ${e.message}")
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime

        return TestSuiteResult(
            suiteName = test.metadata.name,
            results = results,
            totalDurationMs = totalDuration
        )
    }

    /**
     * Run a flow test
     */
    fun runFlowTest(test: FlowTest, testPath: String = ""): TestSuiteResult {
        val startTime = System.currentTimeMillis()

        // Check platform compatibility
        test.platform?.let { platform ->
            if (!platform.includes(config.platform)) {
                log("Skipping flow test - platform mismatch")
                return TestSuiteResult(
                    suiteName = test.metadata.name,
                    results = emptyList(),
                    totalDurationMs = 0
                )
            }
        }

        val result = try {
            // Run setup
            test.setup?.let { setup ->
                log("Running flow setup...")
                executeFlowSteps(setup)
            }

            // Run flow steps
            log("Running flow steps...")
            executeFlowSteps(test.steps)

            // Run teardown
            test.teardown?.let { teardown ->
                log("Running flow teardown...")
                executeFlowSteps(teardown)
            }

            TestResult(
                testName = test.metadata.name,
                caseName = "flow",
                passed = true,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            log("Flow test failed: ${e.message}")
            TestResult(
                testName = test.metadata.name,
                caseName = "flow",
                passed = false,
                error = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        return TestSuiteResult(
            suiteName = test.metadata.name,
            results = listOf(result),
            totalDurationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun runTestCase(testName: String, testCase: TestCase): TestResult {
        val startTime = System.currentTimeMillis()

        // Check if skipped
        if (testCase.skip == true) {
            log("Skipping case: ${testCase.name}")
            return TestResult(
                testName = testName,
                caseName = testCase.name,
                passed = true,
                durationMs = 0
            )
        }

        // Check platform compatibility
        testCase.platform?.let { platform ->
            if (!platform.includes(config.platform)) {
                log("Skipping case ${testCase.name} - platform mismatch")
                return TestResult(
                    testName = testName,
                    caseName = testCase.name,
                    passed = true,
                    durationMs = 0
                )
            }
        }

        log("Running case: ${testCase.name}")

        return try {
            executeSteps(testCase.steps)
            TestResult(
                testName = testName,
                caseName = testCase.name,
                passed = true,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            log("Case ${testCase.name} failed: ${e.message}")
            if (config.screenshotOnFailure) {
                takeScreenshot("failure_${testName}_${testCase.name}")
            }
            TestResult(
                testName = testName,
                caseName = testCase.name,
                passed = false,
                error = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun executeSteps(steps: List<TestStep>) {
        for ((index, step) in steps.withIndex()) {
            log("  Step ${index + 1}: ${stepDescription(step)}")
            executeStep(step)
        }
    }

    private fun executeFlowSteps(steps: List<FlowTestStep>) {
        for ((index, step) in steps.withIndex()) {
            if (step.isFileReference) {
                log("  Flow step ${index + 1}: file=${step.file}")
            } else {
                log("  Flow step ${index + 1}: screen=${step.screen}")
            }
            executeFlowStep(step)
        }
    }

    private fun executeStep(step: TestStep) {
        when {
            step.isAction -> actionExecutor.execute(step)
            step.isAssertion -> assertionExecutor.execute(step)
            else -> throw IllegalArgumentException("Step must have either 'action' or 'assert'")
        }
    }

    private fun executeFlowStep(step: FlowTestStep) {
        // Handle file reference steps
        if (step.isFileReference) {
            executeFileReferenceStep(step)
            return
        }

        // Handle block steps (grouped inline actions)
        if (step.isBlockStep) {
            executeBlockStep(step)
            return
        }

        // Handle inline steps - convert FlowTestStep to TestStep and execute
        val testStep = TestStep(
            action = step.action,
            assert = step.assert,
            id = step.id,
            ids = step.ids,
            value = step.value,
            direction = step.direction,
            duration = step.duration,
            timeout = step.timeout,
            ms = step.ms,
            name = step.name,
            equals = step.equals,
            contains = step.contains,
            path = step.path,
            amount = step.amount
        )
        executeStep(testStep)
    }

    private fun executeBlockStep(step: FlowTestStep) {
        val blockSteps = step.steps ?: return

        log("    Executing block: ${step.block}")

        // Execute each step in the block
        for (innerStep in blockSteps) {
            // Block steps can only contain action/assert steps (no nested blocks or file references)
            val testStep = TestStep(
                action = innerStep.action,
                assert = innerStep.assert,
                id = innerStep.id,
                ids = innerStep.ids,
                value = innerStep.value,
                direction = innerStep.direction,
                duration = innerStep.duration,
                timeout = innerStep.timeout,
                ms = innerStep.ms,
                name = innerStep.name,
                equals = innerStep.equals,
                contains = innerStep.contains,
                path = innerStep.path,
                amount = innerStep.amount
            )
            executeStep(testStep)
        }
    }

    private fun executeFileReferenceStep(step: FlowTestStep) {
        val loader = testLoader
            ?: throw IllegalStateException("TestLoader not set for file reference resolution: ${step.file}")

        val testCases = loader.resolveFileReferenceCases(step)

        for (testCase in testCases) {
            // Skip if marked to skip
            if (testCase.skip == true) {
                log("    Skipping case: ${testCase.name}")
                continue
            }

            // Check platform compatibility
            testCase.platform?.let { platform ->
                if (!platform.includes(config.platform)) {
                    log("    Skipping case ${testCase.name} - platform mismatch")
                    return@let
                }
            }

            log("    Running referenced case: ${testCase.name}")

            // Execute each step in the test case
            for (testStep in testCase.steps) {
                executeStep(testStep)
            }
        }
    }

    private fun stepDescription(step: TestStep): String {
        return when {
            step.action != null -> "action=${step.action}, id=${step.id ?: step.ids?.joinToString(",") ?: "-"}"
            step.assert != null -> "assert=${step.assert}, id=${step.id ?: "-"}"
            else -> "unknown step"
        }
    }

    private fun takeScreenshot(name: String) {
        try {
            val file = java.io.File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "$name.png"
            )
            device.takeScreenshot(file)
            log("Screenshot saved: ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to take screenshot: ${e.message}")
        }
    }

    private fun log(message: String) {
        if (config.verbose) {
            println("[JsonUITestRunner] $message")
        }
    }
}

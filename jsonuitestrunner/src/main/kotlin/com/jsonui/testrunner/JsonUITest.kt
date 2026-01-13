package com.jsonui.testrunner

import android.content.Context
import com.jsonui.testrunner.runner.JsonUITestRunner
import com.jsonui.testrunner.runner.LoadedTest
import com.jsonui.testrunner.runner.TestLoader
import com.jsonui.testrunner.runner.TestRunnerConfig

/**
 * Public API entry point for JsonUI Test Runner
 */
object JsonUITest {

    private val loader = TestLoader()

    /**
     * Load a test from a file path
     */
    fun load(path: String): LoadedTest {
        return loader.load(path)
    }

    /**
     * Load a test from assets
     */
    fun loadFromAssets(context: Context, assetPath: String): LoadedTest {
        return loader.loadFromAssets(context, assetPath)
    }

    /**
     * Load a test from raw resources
     */
    fun loadFromRaw(context: Context, rawResId: Int, name: String): LoadedTest {
        return loader.loadFromRaw(context, rawResId, name)
    }

    /**
     * Load a test from JSON string
     */
    fun loadFromString(jsonString: String, name: String = "inline"): LoadedTest {
        return loader.loadFromString(jsonString, name)
    }

    /**
     * Load all tests from a directory
     */
    fun loadAll(directory: String): List<LoadedTest> {
        return loader.loadAll(directory)
    }

    /**
     * Load all tests from assets directory
     */
    fun loadAllFromAssets(context: Context, assetsPath: String): List<LoadedTest> {
        return loader.loadAllFromAssets(context, assetsPath)
    }

    /**
     * Create a test runner with default configuration
     */
    fun createRunner(): JsonUITestRunner {
        return JsonUITestRunner()
    }

    /**
     * Create a test runner with custom configuration
     */
    fun createRunner(config: TestRunnerConfig): JsonUITestRunner {
        return JsonUITestRunner(config)
    }

    /**
     * Create a test runner builder
     */
    fun runnerBuilder(): RunnerBuilder {
        return RunnerBuilder()
    }

    /**
     * Builder for creating test runners
     */
    class RunnerBuilder {
        private var defaultTimeout: Long = 5000L
        private var screenshotOnFailure: Boolean = true
        private var verbose: Boolean = false

        fun defaultTimeout(timeout: Long) = apply { this.defaultTimeout = timeout }
        fun screenshotOnFailure(enabled: Boolean) = apply { this.screenshotOnFailure = enabled }
        fun verbose(enabled: Boolean) = apply { this.verbose = enabled }

        fun build(): JsonUITestRunner {
            return JsonUITestRunner(
                TestRunnerConfig(
                    defaultTimeout = defaultTimeout,
                    screenshotOnFailure = screenshotOnFailure,
                    platform = "android",
                    verbose = verbose
                )
            )
        }
    }
}

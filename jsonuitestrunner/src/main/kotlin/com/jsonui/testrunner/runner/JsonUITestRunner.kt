package com.jsonui.testrunner.runner

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.actions.ActionExecutor
import com.jsonui.testrunner.assertions.AssertionExecutor
import com.jsonui.testrunner.models.FlowTest
import com.jsonui.testrunner.models.FlowTestStep
import com.jsonui.testrunner.models.LaunchConfig
import com.jsonui.testrunner.models.ResponsiveCondition
import com.jsonui.testrunner.models.ResponsiveThresholds
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.StepCondition
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestStep
import com.jsonui.testrunner.models.TestSuiteResult
import com.jsonui.testrunner.models.WindowDimensions
import com.jsonui.testrunner.models.matchesResponsive

/**
 * Configuration for the test runner
 */
data class TestRunnerConfig(
    val defaultTimeout: Long = 5000L,
    val screenshotOnFailure: Boolean = true,
    val platform: String = "android",
    val verbose: Boolean = false,
    /**
     * Directory where screenshots (both `screenshot` action steps and
     * failure screenshots) are saved. Defaults to the instrumented app's
     * filesDir when null.
     */
    val screenshotDir: java.io.File? = null,
    /** Baseline directory for the `screenshot` assertion (default: external files dir) */
    val baselineDir: java.io.File? = null,
    /** When true, screenshot baselines are always overwritten and the assertion passes */
    val updateBaselines: Boolean = false,
    /** When set, results are written to this file as standardized results JSON */
    val resultsPath: java.io.File? = null,
    /** Mock server base URL (e.g. http://10.0.2.2:8790). Required to use `mocks` / `setMocks`. */
    val mockServerUrl: String? = null,
    /** Admin token printed by `jsonui-test mock serve`. Required with mockServerUrl. */
    val mockToken: String? = null,
    /**
     * Named-bucket width thresholds (dp) for `responsive` gating. Defaults
     * mirror the Android renderer's break rules (kjui: medium ≥ 600dp,
     * regular ≥ 840dp). Override only for projects that also override the
     * renderer's breakpoints; bucket names themselves are not configurable.
     */
    val responsive: ResponsiveThresholds = ResponsiveThresholds()
)

/** Safety cap for `repeat` with a `while` condition and no `times` */
private const val REPEAT_WHILE_CAP = 100

/** Cross-platform permission names → Android runtime permissions */
private val ANDROID_PERMISSION_MAP = mapOf(
    "camera" to "android.permission.CAMERA",
    "microphone" to "android.permission.RECORD_AUDIO",
    "location" to "android.permission.ACCESS_FINE_LOCATION",
    "notifications" to "android.permission.POST_NOTIFICATIONS",
    "photos" to "android.permission.READ_MEDIA_IMAGES",
    "storage" to "android.permission.READ_MEDIA_IMAGES",
    "contacts" to "android.permission.READ_CONTACTS",
    "calendar" to "android.permission.READ_CALENDAR",
    "bluetooth" to "android.permission.BLUETOOTH_CONNECT"
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

    /** Runtime variables (readText results), shared with the action executor */
    private val variables = mutableMapOf<String, String>()

    /** Warnings collected during the current case (drained per-case) */
    private var currentWarnings = mutableListOf<String>()

    /** State provider for `state` assertions/conditions; inject before running. */
    var stateProvider: ViewModelStateProvider? = null
        set(value) {
            field = value
            assertionExecutor.stateProvider = value
        }

    private val actionExecutor = ActionExecutor(device, config.defaultTimeout).apply {
        // Route `screenshot` action steps through the same (config-aware)
        // capture path as failure screenshots.
        screenshotHandler = { name -> takeScreenshot(name) }
        variableStore = variables
        warningHandler = { message -> currentWarnings.add(message) }
    }
    private val assertionExecutor = AssertionExecutor(device, config.defaultTimeout).apply {
        baselineDir = config.baselineDir
        updateBaselines = config.updateBaselines
        warningHandler = { message -> currentWarnings.add(message) }
    }

    /** Test loader for file reference resolution */
    var testLoader: TestLoader? = null

    /** Mock server admin client; null unless mockServerUrl + mockToken are configured. */
    private val mockClient: MockClient? =
        if (config.mockServerUrl != null && config.mockToken != null)
            MockClient(config.mockServerUrl, config.mockToken)
        else null

    private fun requireMockClient(feature: String): MockClient =
        mockClient ?: throw IllegalStateException(
            "'$feature' requires a mock server: set mockServerUrl + mockToken in TestRunnerConfig " +
                "(from 'jsonui-test mock serve')."
        )

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

        // Check platform compatibility. Emit a skipped row per case (not
        // results: []) so file-level skips stay visible in the report —
        // "no silent truncation".
        test.platform?.let { platform ->
            if (!platform.includes(config.platform)) {
                log("Skipping test - platform mismatch")
                val suiteResult = TestSuiteResult(
                    suiteName = test.metadata.name,
                    results = test.cases.map { testCase ->
                        TestResult(
                            testName = test.metadata.name,
                            caseName = testCase.name,
                            passed = true,
                            skipped = true,
                            skipReason = "platform",
                            durationMs = 0
                        )
                    },
                    totalDurationMs = 0
                )
                writeResultsIfNeeded(suiteResult)
                return suiteResult
            }
        }

        // Apply the file-level mock scenario set BEFORE the app re-fetches, then
        // relaunch so the screen renders under the selected scenarios. Scenario
        // switching is per-file for screen tests; there is no per-case re-open (§8.1).
        test.mocks?.let { mocks ->
            try {
                requireMockClient("mocks").scenarioSet(mocks)
                relaunchApp()
            } catch (e: Exception) {
                val failed = test.cases.map {
                    TestResult(test.metadata.name, it.name, passed = false, error = e.message, durationMs = 0)
                }
                return TestSuiteResult(test.metadata.name, failed, System.currentTimeMillis() - startTime)
            }
        }

        // Apply launch configuration before running cases
        test.launch?.let { applyLaunch(it) }

        // Run setup once. If it throws, every case is recorded as failed but
        // teardown still runs (§7 teardown guarantee).
        var setupError: String? = null
        test.setup?.let { setup ->
            log("Running setup...")
            try {
                val warnings = mutableListOf<String>()
                executeSteps(setup, warnings)
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                setupError = e.message ?: e.toString()
                log("Setup failed: $setupError")
            }
        }

        // Run test cases
        for (testCase in test.cases) {
            val skipFlag = testCase.skip == true
            val platformMet = testCase.platform?.includes(config.platform) != false
            // The responsive gate is only resolved when the cheaper static gates
            // pass; skip-reason precedence (platform wins when both gates are
            // unmet) is encoded in resolveCaseSkip.
            val responsiveMet = if (!skipFlag && platformMet) {
                testCase.responsive?.let { currentSizeMatches(it) }
            } else {
                null
            }
            val caseSkip = resolveCaseSkip(skipFlag, platformMet, responsiveMet)
            if (caseSkip != null) {
                log("Skipping case ${testCase.name}${caseSkip.reason?.let { " - $it mismatch" } ?: ""}")
                results.add(TestResult(
                    testName = test.metadata.name,
                    caseName = testCase.name,
                    passed = true,
                    skipped = true,
                    skipReason = caseSkip.reason,
                    durationMs = 0
                ))
                continue
            }
            if (setupError != null) {
                results.add(TestResult(
                    testName = test.metadata.name,
                    caseName = testCase.name,
                    passed = false,
                    error = "setup failed: $setupError",
                    durationMs = 0
                ))
                continue
            }
            results.add(runTestCase(test.metadata.name, testCase))
        }

        // Teardown (guaranteed). A teardown failure is recorded as an extra failed result.
        test.teardown?.let { teardown ->
            log("Running teardown...")
            try {
                val warnings = mutableListOf<String>()
                executeSteps(teardown, warnings)
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                log("Teardown failed: ${e.message}")
                results.add(TestResult(
                    testName = test.metadata.name,
                    caseName = "teardown",
                    passed = false,
                    error = e.message,
                    durationMs = 0
                ))
            }
        }

        // Reset mock scenarios so state does not leak into the next test file.
        if (test.mocks != null) {
            runCatching { mockClient?.reset() }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val suiteResult = TestSuiteResult(
            suiteName = test.metadata.name,
            results = results,
            totalDurationMs = totalDuration
        )
        writeResultsIfNeeded(suiteResult)
        return suiteResult
    }

    /**
     * Run a flow test
     */
    fun runFlowTest(test: FlowTest, testPath: String = ""): TestSuiteResult {
        val startTime = System.currentTimeMillis()

        // Check platform compatibility. Emit a skipped row (not results: []) so
        // the flow-level skip stays visible in the report — "no silent truncation".
        test.platform?.let { platform ->
            if (!platform.includes(config.platform)) {
                log("Skipping flow test - platform mismatch")
                val suiteResult = TestSuiteResult(
                    suiteName = test.metadata.name,
                    results = listOf(TestResult(
                        testName = test.metadata.name,
                        caseName = "flow",
                        passed = true,
                        skipped = true,
                        skipReason = "platform",
                        durationMs = 0
                    )),
                    totalDurationMs = 0
                )
                writeResultsIfNeeded(suiteResult)
                return suiteResult
            }
        }

        // Apply launch configuration before running
        test.launch?.let { applyLaunch(it) }

        val results = mutableListOf<TestResult>()
        currentWarnings = mutableListOf()
        var flowError: String? = null

        try {
            // Run setup
            test.setup?.let { setup ->
                log("Running flow setup...")
                executeFlowSteps(setup, currentWarnings)
            }

            // Run flow steps
            log("Running flow steps...")
            executeFlowSteps(test.steps, currentWarnings)
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            flowError = e.message ?: e.toString()
            log("Flow test failed: $flowError")
        }

        results.add(TestResult(
            testName = test.metadata.name,
            caseName = "flow",
            passed = flowError == null,
            error = flowError,
            warnings = currentWarnings.toList(),
            durationMs = System.currentTimeMillis() - startTime
        ))

        // Teardown (guaranteed), runs even when the flow body failed
        test.teardown?.let { teardown ->
            log("Running flow teardown...")
            try {
                executeFlowSteps(teardown, mutableListOf())
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                results.add(TestResult(
                    testName = test.metadata.name,
                    caseName = "teardown",
                    passed = false,
                    error = e.message,
                    durationMs = 0
                ))
            }
        }

        // Reset mock scenarios so a flow's setMocks state does not leak to the next test.
        runCatching { mockClient?.reset() }

        val suiteResult = TestSuiteResult(
            suiteName = test.metadata.name,
            results = results,
            totalDurationMs = System.currentTimeMillis() - startTime
        )
        writeResultsIfNeeded(suiteResult)
        return suiteResult
    }

    /**
     * A test harness must *report* failures, not crash on them. Assertions throw
     * `AssertionError`, which is a `java.lang.Error` — NOT an `Exception` — so the
     * per-case / retry / setup / teardown catches below use `Throwable` to keep
     * per-case isolation and result recording intact (parity with iOS, which has a
     * single `Error` hierarchy). Only genuinely-fatal JVM errors are rethrown so we
     * never swallow OOM / StackOverflow.
     */
    private fun rethrowIfFatal(t: Throwable) {
        if (t is VirtualMachineError) throw t
    }

    private fun runTestCase(testName: String, testCase: TestCase): TestResult {
        val startTime = System.currentTimeMillis()
        log("Running case: ${testCase.name}")
        currentWarnings = mutableListOf()

        // Apply load-time args substitution if test case has args
        val processedCase = testLoader?.applyArgsSubstitution(testCase)
            ?: applyArgsSubstitutionLocally(testCase)

        return try {
            executeSteps(processedCase.steps, currentWarnings)
            TestResult(
                testName = testName,
                caseName = testCase.name,
                passed = true,
                warnings = currentWarnings.toList(),
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            log("Case ${testCase.name} failed: ${e.message}")
            if (config.screenshotOnFailure) {
                takeScreenshot("failure_${testName}_${testCase.name}")
            }
            TestResult(
                testName = testName,
                caseName = testCase.name,
                passed = false,
                error = e.message,
                warnings = currentWarnings.toList(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun executeSteps(steps: List<TestStep>, warnings: MutableList<String>) {
        for ((index, step) in steps.withIndex()) {
            log("  Step ${index + 1}: ${stepDescription(step)}")
            executeStepGuarded(step, warnings)
        }
    }

    private fun executeFlowSteps(steps: List<FlowTestStep>, warnings: MutableList<String>) {
        for ((index, step) in steps.withIndex()) {
            if (step.isFileReference) {
                log("  Flow step ${index + 1}: file=${step.file}")
            } else if (step.isBlockStep) {
                log("  Flow step ${index + 1}: block=${step.block}")
            } else {
                log("  Flow step ${index + 1}: screen=${step.screen}")
            }
            executeFlowStep(step, warnings)
        }
    }

    /**
     * Execute a single step honoring `when` (skip), `optional` (failure→warning),
     * runtime `@{name}` substitution, and control steps (repeat/retry).
     */
    private fun executeStepGuarded(rawStep: TestStep, warnings: MutableList<String>) {
        // Resolve runtime variables (@{name}) at execution time
        val step = if (variables.isEmpty()) rawStep else substituteArgsInStep(rawStep, variables.toMap())

        // Evaluate `when` pre-condition
        step.whenCondition?.let { condition ->
            if (!evaluateCondition(condition, warnings)) {
                log("    Skipped (when not satisfied): ${step.label ?: stepDescription(step)}")
                return
            }
        }

        try {
            executeStep(step, warnings)
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            if (step.optional == true) {
                val label = step.label ?: step.action ?: step.assert ?: "step"
                warnings.add("optional step failed ($label): ${e.message}")
                log("    Optional step failed, continuing: ${e.message}")
                return
            }
            throw e
        }
    }

    private fun executeStep(step: TestStep, warnings: MutableList<String>) {
        // Control steps
        when (step.action) {
            "repeat" -> { executeRepeat(step, warnings); return }
            "retry" -> { executeRetry(step, warnings); return }
            "setMocks" -> {
                // Switch scenarios mid-flow; the next navigation re-fetches under them.
                requireMockClient("setMocks").scenarioSet(step.mocks ?: emptyMap())
                return
            }
        }

        when {
            step.isAction -> actionExecutor.execute(step)
            step.isAssertion -> assertionExecutor.execute(step)
            else -> throw IllegalArgumentException("Step must have either 'action' or 'assert'")
        }
    }

    private fun executeRepeat(step: TestStep, warnings: MutableList<String>) {
        val steps = step.steps ?: emptyList()
        val times = step.times
        val whileCondition = step.whileCondition

        if (times != null && whileCondition != null) {
            for (i in 0 until times) {
                if (!evaluateCondition(whileCondition, warnings)) return
                executeSteps(steps, warnings)
            }
            return
        }
        if (times != null) {
            repeat(times) { executeSteps(steps, warnings) }
            return
        }
        // while only: safety cap
        if (whileCondition != null) {
            for (i in 0 until REPEAT_WHILE_CAP) {
                if (!evaluateCondition(whileCondition, warnings)) return
                executeSteps(steps, warnings)
            }
            if (evaluateCondition(whileCondition, warnings)) {
                throw AssertionError("repeat exceeded $REPEAT_WHILE_CAP iterations (possible infinite loop)")
            }
        }
    }

    private fun executeRetry(step: TestStep, warnings: MutableList<String>) {
        val steps = step.steps ?: emptyList()
        val maxRetries = (step.maxRetries ?: 1).coerceIn(0, 3)
        var lastError: Throwable? = null

        for (attempt in 0..maxRetries) {
            try {
                val attemptWarnings = mutableListOf<String>()
                executeSteps(steps, attemptWarnings)
                warnings.addAll(attemptWarnings)
                return
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                lastError = e
                log("    Retry attempt ${attempt + 1}/${maxRetries + 1} failed")
            }
        }
        throw lastError ?: AssertionError("retry exhausted with no error captured")
    }

    /**
     * Evaluate a `when` / `while` condition. Multiple keys are ANDed.
     *
     * Fail-safe: a condition containing keys this driver cannot evaluate
     * (captured at decode time in [StepCondition.unknownKeys], i.e. written
     * against a newer schema than this driver) is UNMET — the guarded step is
     * skipped, never run-anyway, never a hard error.
     */
    private fun evaluateCondition(condition: StepCondition, warnings: MutableList<String>): Boolean {
        if (condition.unknownKeys.isNotEmpty()) {
            val message = "condition key(s) [${condition.unknownKeys.joinToString(", ")}] " +
                "not supported by this driver - condition treated as unmet (fail-safe skip)"
            log("    Warning: $message")
            if (message !in warnings) warnings.add(message)
            return false
        }
        condition.platform?.let { if (!it.includes(config.platform)) return false }
        condition.responsive?.let { if (!currentSizeMatches(it)) return false }
        condition.visible?.let { if (device.findObject(By.res(it)) == null) return false }
        condition.notVisible?.let { if (device.findObject(By.res(it)) != null) return false }
        condition.state?.let {
            if (!assertionExecutor.stateMatches(it.path, it.equals)) return false
        }
        return true
    }

    // MARK: - Responsive Resolution

    /**
     * True when the current window size satisfies a `responsive` condition.
     * The live size is read on every evaluation so a mid-test `setOrientation`
     * (or a multi-window resize) is picked up immediately.
     */
    private fun currentSizeMatches(condition: ResponsiveCondition): Boolean {
        val size = currentWindowSizeDp()
        val matched = matchesResponsive(condition, size, config.responsive)
        log("    responsive gate: window=${size.width}x${size.height}dp -> ${if (matched) "met" else "unmet"} ($condition)")
        return matched
    }

    /**
     * Current app-window size in dp — the `LocalWindowInfo.containerSize`
     * equivalent the Android renderer (kjui compose codegen) resolves
     * responsive layout from. Derived from the ACTUAL window the app occupies:
     * the app package's root-window bounds in the accessibility tree
     * (px ÷ density, truncated like the renderer's `toDp().value.toInt()`),
     * so the driver agrees with the renderer in multi-window / foldable
     * postures. Deliberately NOT `resources.configuration.screenWidthDp`,
     * which kjui migrated off as deprecated and which disagrees with the
     * window near the 600/840dp boundaries in multi-window. Falls back to the
     * full display size when the app window cannot be located.
     */
    private fun currentWindowSizeDp(): WindowDimensions {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val bounds = runCatching {
            device.findObject(By.pkg(context.packageName))?.visibleBounds
        }.getOrNull()
        val (widthPx, heightPx) = if (bounds != null && !bounds.isEmpty) {
            bounds.width() to bounds.height()
        } else {
            device.displayWidth to device.displayHeight
        }
        return WindowDimensions((widthPx / density).toInt(), (heightPx / density).toInt())
    }

    private fun executeFlowStep(step: FlowTestStep, warnings: MutableList<String>) {
        // Step-level `when` for file / block / inline steps
        step.whenCondition?.let { condition ->
            if (!evaluateCondition(condition, warnings)) {
                log("    Skipped flow step (when not satisfied)")
                return
            }
        }

        // Handle file reference steps
        if (step.isFileReference) {
            executeFileReferenceStep(step, warnings)
            return
        }

        // Handle block steps (grouped inline actions)
        if (step.isBlockStep) {
            executeBlockStep(step, warnings)
            return
        }

        // Handle inline steps - convert FlowTestStep to TestStep and execute
        if (step.action != null || step.assert != null) {
            executeStepGuarded(step.toTestStep(), warnings)
        }
    }

    private fun executeBlockStep(step: FlowTestStep, warnings: MutableList<String>) {
        val blockSteps = step.steps ?: return
        log("    Executing block: ${step.block}")
        for (innerStep in blockSteps) {
            executeStepGuarded(innerStep.toTestStep(), warnings)
        }
    }

    private fun executeFileReferenceStep(step: FlowTestStep, warnings: MutableList<String>) {
        val loader = testLoader
            ?: throw IllegalStateException("TestLoader not set for file reference resolution: ${step.file}")

        val testCases = loader.resolveFileReferenceCases(step)

        for (testCase in testCases) {
            // Skip if marked to skip
            if (testCase.skip == true) {
                log("    Skipping case: ${testCase.name}")
                continue
            }

            // Check platform compatibility (before responsive — same precedence
            // as the screen-case filter)
            if (testCase.platform?.includes(config.platform) == false) {
                log("    Skipping case ${testCase.name} - platform mismatch")
                continue
            }

            // Check responsive compatibility against the current window size
            val responsiveGate = testCase.responsive
            if (responsiveGate != null && !currentSizeMatches(responsiveGate)) {
                log("    Skipping case ${testCase.name} - responsive mismatch")
                continue
            }

            log("    Running referenced case: ${testCase.name}")
            executeSteps(testCase.steps, warnings)
        }
    }

    /** Convert an inline / block-child flow step into a TestStep for execution. */
    private fun FlowTestStep.toTestStep(): TestStep = TestStep(
        action = action,
        assert = assert,
        id = id,
        ids = ids,
        text = text,
        value = value,
        direction = direction,
        duration = duration,
        timeout = timeout,
        ms = ms,
        name = name,
        equals = equals,
        contains = contains,
        path = path,
        amount = amount,
        button = button,
        label = label,
        index = index,
        retryTapIfNoChange = retryTapIfNoChange,
        container = container,
        variable = variable,
        times = times,
        orientation = orientation,
        whileCondition = whileCondition,
        steps = steps?.map { it.toTestStep() },
        maxRetries = maxRetries,
        latitude = latitude,
        longitude = longitude,
        paths = paths,
        cropId = cropId,
        threshold = threshold,
        mocks = mocks,
        whenCondition = whenCondition,
        optional = optional
    )

    // MARK: - Launch Configuration

    /**
     * Apply a launch configuration before a test runs. clearState → `pm clear`,
     * permissions → `pm grant`/`pm revoke`, arguments → stored for the app-side
     * contract (JSONUI_TEST_ARGS). Best-effort: shell failures are logged.
     */
    private fun applyLaunch(launch: LaunchConfig) {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        if (launch.clearState == true) {
            log("Launch: clearing state (pm clear $packageName)")
            runCatching { device.executeShellCommand("pm clear $packageName") }
        }

        launch.permissions?.forEach { (name, value) ->
            val androidPermission = ANDROID_PERMISSION_MAP[name] ?: return@forEach
            when (value) {
                "allow" -> runCatching { device.executeShellCommand("pm grant $packageName $androidPermission") }
                "deny" -> runCatching { device.executeShellCommand("pm revoke $packageName $androidPermission") }
                "unset" -> { /* leave at system default */ }
            }
        }

        launch.arguments?.let { args ->
            // Store as JSON for the app-side contract; the app reads JSONUI_TEST_ARGS.
            val json = kotlinx.serialization.json.JsonObject(args).toString()
            log("Launch arguments: $json")
        }
    }

    /**
     * Relaunch the app under test so a freshly-set mock scenario is fetched.
     * Uses the package launcher intent with CLEAR_TASK to reset the back stack.
     */
    private fun relaunchApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            log("relaunchApp: no launch intent for ${context.packageName}")
            return
        }
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
        device.waitForIdle(config.defaultTimeout)
        Thread.sleep(500) // let Compose semantics settle
    }

    // MARK: - Results Output

    private fun writeResultsIfNeeded(suite: TestSuiteResult) {
        val path = config.resultsPath ?: return
        runCatching {
            ResultsWriter.write(
                suites = listOf(suite),
                file = path,
                platform = config.platform,
                generatedAt = java.time.Instant.now().toString()
            )
        }.onFailure { log("Failed to write results: ${it.message}") }
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
            val dir = config.screenshotDir
                ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            dir.mkdirs()
            val file = java.io.File(dir, "$name.png")
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

    // MARK: - Args Substitution (Local fallback when testLoader is not available)

    /**
     * Apply args substitution locally when testLoader is not set
     */
    private fun applyArgsSubstitutionLocally(testCase: TestCase): TestCase {
        val args = testCase.args
        if (args.isNullOrEmpty()) {
            return testCase
        }

        // Convert JsonElement args to Map<String, Any>
        val argsMap = mutableMapOf<String, Any>()
        args.forEach { (key, value) ->
            argsMap[key] = jsonElementToValue(value)
        }

        // Apply substitution to steps
        val substitutedSteps = testCase.steps.map { substituteArgsInStep(it, argsMap) }

        return testCase.copy(steps = substitutedSteps)
    }

    /**
     * Substitute @{varName} placeholders in a TestStep
     */
    private fun substituteArgsInStep(step: TestStep, args: Map<String, Any>): TestStep {
        return step.copy(
            id = substituteArgsInString(step.id, args),
            ids = step.ids?.map { substituteArgsInString(it, args) ?: it },
            text = substituteArgsInString(step.text, args),
            value = substituteArgsInString(step.value, args),
            contains = substituteArgsInString(step.contains, args),
            button = substituteArgsInString(step.button, args),
            label = substituteArgsInString(step.label, args),
            name = substituteArgsInString(step.name, args),
            path = substituteArgsInString(step.path, args),
            container = substituteArgsInString(step.container, args),
            cropId = substituteArgsInString(step.cropId, args),
            equals = substituteArgsInJsonElement(step.equals, args)
        )
    }

    /**
     * Substitute @{varName} placeholders in a string
     */
    private fun substituteArgsInString(string: String?, args: Map<String, Any>): String? {
        // Kotlin 2.x drops the smart cast on the lambda-captured `var result`,
        // so bind a non-null local explicitly (compiles under K1 and K2).
        val input: String = string ?: return null

        var result: String = input
        val pattern = """@\{([^}]+)\}""".toRegex()

        pattern.findAll(input).toList().reversed().forEach { match ->
            val varName = match.groupValues[1]
            args[varName]?.let { value ->
                result = result.replaceRange(match.range, valueToString(value))
            }
        }

        return result
    }

    /**
     * Substitute @{varName} placeholders in JsonElement (only for string values)
     */
    private fun substituteArgsInJsonElement(element: kotlinx.serialization.json.JsonElement?, args: Map<String, Any>): kotlinx.serialization.json.JsonElement? {
        if (element == null) return null
        if (element is kotlinx.serialization.json.JsonPrimitive && element.isString) {
            val substituted = substituteArgsInString(element.content, args)
            return kotlinx.serialization.json.JsonPrimitive(substituted)
        }
        return element
    }

    /**
     * Convert JsonElement to primitive value
     */
    private fun jsonElementToValue(element: kotlinx.serialization.json.JsonElement): Any {
        return when {
            element is kotlinx.serialization.json.JsonPrimitive -> {
                element.booleanOrNull ?: element.intOrNull ?: element.doubleOrNull ?: element.contentOrNull ?: ""
            }
            else -> element.toString()
        }
    }

    /**
     * Convert Any to String for substitution
     */
    private fun valueToString(value: Any): String {
        return when (value) {
            is String -> value
            is Int -> value.toString()
            is Double -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private val kotlinx.serialization.json.JsonPrimitive.booleanOrNull: Boolean?
        get() = if (this.isString) null else this.content.toBooleanStrictOrNull()

    private val kotlinx.serialization.json.JsonPrimitive.intOrNull: Int?
        get() = this.content.toIntOrNull()

    private val kotlinx.serialization.json.JsonPrimitive.doubleOrNull: Double?
        get() = this.content.toDoubleOrNull()

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (this.isString) this.content else null
}

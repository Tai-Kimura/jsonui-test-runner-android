package com.jsonui.testrunner.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// MARK: - Screen Test

@Serializable
data class ScreenTest(
    val type: String,
    val source: TestSource,
    val metadata: TestMetadata,
    val platform: PlatformTarget? = null,
    val initialState: InitialState? = null,
    val setup: List<TestStep>? = null,
    val teardown: List<TestStep>? = null,
    val cases: List<TestCase>
)

@Serializable
data class TestSource(
    val layout: String,
    val spec: String? = null
)

@Serializable
data class TestMetadata(
    val name: String,
    val description: String? = null,
    val generatedAt: String? = null,
    val generatedBy: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class InitialState(
    val viewModel: Map<String, JsonElement>? = null
)

@Serializable
data class TestCase(
    val name: String,
    val description: String? = null,
    val skip: Boolean? = null,
    val platform: PlatformTarget? = null,
    val initialState: InitialState? = null,
    val steps: List<TestStep>
)

// MARK: - Flow Test

@Serializable
data class FlowTest(
    val type: String,
    val sources: List<FlowTestSource>,
    val metadata: TestMetadata,
    val platform: PlatformTarget? = null,
    val initialState: FlowInitialState? = null,
    val setup: List<FlowTestStep>? = null,
    val teardown: List<FlowTestStep>? = null,
    val steps: List<FlowTestStep>,
    val checkpoints: List<Checkpoint>? = null
)

@Serializable
data class FlowTestSource(
    val layout: String,
    val spec: String? = null,
    val alias: String? = null
)

@Serializable
data class FlowInitialState(
    val screen: String? = null,
    val viewModels: Map<String, Map<String, JsonElement>>? = null
)

@Serializable
data class FlowTestStep(
    val screen: String,
    val action: String? = null,
    val assert: String? = null,
    val id: String? = null,
    val ids: List<String>? = null,
    val text: String? = null,
    val value: String? = null,
    val direction: String? = null,
    val duration: Int? = null,
    val timeout: Int? = null,
    val ms: Int? = null,
    val name: String? = null,
    val equals: JsonElement? = null,
    val contains: String? = null,
    val path: String? = null,
    val amount: Int? = null
)

@Serializable
data class Checkpoint(
    val name: String,
    val afterStep: Int,
    val screenshot: Boolean? = null
)

// MARK: - Test Step (for Screen Tests)

@Serializable
data class TestStep(
    val action: String? = null,
    val assert: String? = null,
    val id: String? = null,
    val ids: List<String>? = null,
    val text: String? = null,
    val value: String? = null,
    val direction: String? = null,
    val duration: Int? = null,
    val timeout: Int? = null,
    val ms: Int? = null,
    val name: String? = null,
    val equals: JsonElement? = null,
    val contains: String? = null,
    val path: String? = null,
    val amount: Int? = null
) {
    val isAction: Boolean get() = action != null
    val isAssertion: Boolean get() = assert != null
}

// MARK: - Platform Target

@Serializable(with = PlatformTargetSerializer::class)
sealed class PlatformTarget {
    data class Single(val value: String) : PlatformTarget()
    data class Multiple(val values: List<String>) : PlatformTarget()

    fun includes(platform: String): Boolean {
        return when (this) {
            is Single -> value == platform || value == "all"
            is Multiple -> values.contains(platform)
        }
    }
}

// MARK: - Test Result

data class TestResult(
    val testName: String,
    val caseName: String,
    val passed: Boolean,
    val error: String? = null,
    val durationMs: Long = 0
)

data class TestSuiteResult(
    val suiteName: String,
    val results: List<TestResult>,
    val totalDurationMs: Long = 0
) {
    val passedCount: Int get() = results.count { it.passed }
    val failedCount: Int get() = results.count { !it.passed }
    val allPassed: Boolean get() = results.all { it.passed }
}

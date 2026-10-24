package com.jsonui.testrunner.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement

// MARK: - Screen Test

@Serializable
data class ScreenTest(
    val type: String,
    val source: TestSource,
    val metadata: TestMetadata,
    val platform: PlatformTarget? = null,
    /**
     * Orientation this file runs in, applied once when the run starts.
     * Overrides the run default from `jsonui-test-run.json`; a `setOrientation`
     * step still wins for the rotation it performs, so the order is
     * step > this field > run default.
     */
    val orientation: String? = null,
    val launch: LaunchConfig? = null,
    /** API mock scenario set applied (and the app relaunched) before the cases run */
    val mocks: Map<String, String>? = null,
    val initialState: InitialState? = null,
    /**
     * `SHARED` (absent = shared): setup once, every case in the app state the
     * previous case left (scroll position, navigation), teardown once.
     * `REOPEN`: each case starts from a relaunched app, with setup before and
     * teardown after it. An unknown value fails the load instead of running
     * as shared.
     */
    val caseIsolation: CaseIsolation? = null,
    val setup: List<TestStep>? = null,
    val teardown: List<TestStep>? = null,
    val cases: List<TestCase>
)

/** Per-file case isolation — see [ScreenTest.caseIsolation]. */
@Serializable
enum class CaseIsolation {
    @SerialName("shared") SHARED,
    @SerialName("reopen") REOPEN
}

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
    /**
     * Case-level responsive gate (named bucket string or constraint object),
     * evaluated against the current window size in dp — parallel to the
     * case-level `platform` gate. Unmet → case skipped with
     * skipReason "responsive".
     */
    val responsive: ResponsiveCondition? = null,
    val initialState: InitialState? = null,
    val steps: List<TestStep>,
    /** Default argument values for @{varName} substitution */
    val args: Map<String, JsonElement>? = null
)

// MARK: - Flow Test

@Serializable
data class FlowTest(
    val type: String,
    val sources: List<FlowTestSource>? = null,  // Now optional (not needed when using file references)
    val metadata: TestMetadata,
    val platform: PlatformTarget? = null,
    /** See [ScreenTest.orientation] — same field, same precedence. */
    val orientation: String? = null,
    val launch: LaunchConfig? = null,
    val initialState: FlowInitialState? = null,
    // File-level mock scenarios (operationId -> scenario) applied before the
    // first launch, so startup fetches run under the selected scenarios.
    // Parity with ScreenTest.mocks; step-level setMocks handles mid-flow switches.
    val mocks: Map<String, String>? = null,
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
    /**
     * Screen id -> that screen's values, delivered in the flow's launch and
     * confirmed when the flow first reaches the screen (spec §10.2, §10.5).
     * `screen` is no longer part of the schema (validate:
     * flow-initial-state-screen-removed); the loader ignores it.
     */
    val viewModels: Map<String, Map<String, JsonElement>>? = null
)

@Serializable
data class FlowTestStep(
    // For inline steps
    val screen: String? = null,
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
    /** A written `null` is [kotlinx.serialization.json.JsonNull]; absent is null (see [KeepNullJsonElement]). */
    @Serializable(with = KeepNullJsonElement::class)
    val equals: JsonElement? = null,
    val contains: String? = null,
    val path: String? = null,
    val amount: Int? = null,
    val button: String? = null,
    val label: String? = null,
    val index: Int? = null,
    val retryTapIfNoChange: Boolean? = null,
    /**
     * Scope for an id-targeting step: the id of a container (normally a
     * collection cell `<collectionId>_item_<n>`) that the step's `id` (or
     * every id of `ids`, or `cropId`, or tapItem / selectTab's derived item
     * id) is looked for inside. Absent: the whole screen, as always.
     */
    val within: String? = null,
    val container: String? = null,
    val variable: String? = null,
    val times: Int? = null,
    /** Target orientation for the setOrientation action ("portrait" | "landscape") */
    val orientation: String? = null,
    @SerialName("while")
    val whileCondition: StepCondition? = null,
    val maxRetries: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val paths: List<String>? = null,
    val cropId: String? = null,
    val threshold: Double? = null,
    /** Scenario map for the setMocks action (operationId -> scenario) */
    val mocks: Map<String, String>? = null,
    @SerialName("when")
    val whenCondition: StepCondition? = null,
    val optional: Boolean? = null,
    // For file reference steps
    val file: String? = null,
    @SerialName("case")
    val caseName: String? = null,
    val cases: List<String>? = null,
    /** Arguments to override screen test default args (for file reference steps) */
    val args: Map<String, JsonElement>? = null,
    // For block steps (grouped inline actions) and control steps (repeat/retry)
    val block: String? = null,
    val description: String? = null,
    val descriptionFile: String? = null,
    val steps: List<FlowTestStep>? = null
) {
    /** Whether this is a file reference step */
    val isFileReference: Boolean get() = file != null

    /** Whether this is a block step */
    val isBlockStep: Boolean get() = block != null

    /** Whether this is an inline action/assertion step */
    val isInlineStep: Boolean get() = screen != null && (action != null || assert != null)
}

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
    /** A written `null` is [kotlinx.serialization.json.JsonNull]; absent is null (see [KeepNullJsonElement]). */
    @Serializable(with = KeepNullJsonElement::class)
    val equals: JsonElement? = null,
    val contains: String? = null,
    val path: String? = null,
    val amount: Int? = null,
    val button: String? = null,
    val label: String? = null,
    val index: Int? = null,
    val retryTapIfNoChange: Boolean? = null,
    /**
     * Scope for an id-targeting step: the id of a container (normally a
     * collection cell `<collectionId>_item_<n>`) that the step's `id` (or
     * every id of `ids`, or `cropId`, or tapItem / selectTab's derived item
     * id) is looked for inside. Absent: the whole screen, as always.
     */
    val within: String? = null,
    val container: String? = null,
    val variable: String? = null,
    val times: Int? = null,
    /** Target orientation for the setOrientation action ("portrait" | "landscape") */
    val orientation: String? = null,
    @SerialName("while")
    val whileCondition: StepCondition? = null,
    val steps: List<TestStep>? = null,
    val maxRetries: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val paths: List<String>? = null,
    val cropId: String? = null,
    val threshold: Double? = null,
    /** Scenario map for the setMocks action (operationId -> scenario) */
    val mocks: Map<String, String>? = null,
    @SerialName("when")
    val whenCondition: StepCondition? = null,
    val optional: Boolean? = null
) {
    val isAction: Boolean get() = action != null
    val isAssertion: Boolean get() = assert != null
}

// MARK: - Step Condition (used by `when` and `repeat.while`)

/**
 * Condition object evaluated before a step (`when`) or before each `repeat`
 * iteration (`while`). Multiple keys are ANDed.
 *
 * Decoded via [StepConditionSerializer] so that keys outside the known set
 * are captured in [unknownKeys] instead of being silently dropped by
 * `ignoreUnknownKeys` (which would make the step run-anyway). A condition
 * with unknown keys is fail-safe UNMET → the step is skipped.
 */
@Serializable(with = StepConditionSerializer::class)
data class StepCondition(
    /** Instant check: element is currently visible (no polling) */
    val visible: String? = null,
    /** Instant check: element is currently absent or invisible (no polling) */
    val notVisible: String? = null,
    /** Instant check: element is not drawn (absent, gone or invisible), wherever it is (no polling) */
    val hidden: String? = null,
    /**
     * Scope for [visible] / [notVisible] / [hidden]: their id is looked for
     * inside the element with this id (a collection cell). A container that
     * is not there makes `visible` unmet and `notVisible` / `hidden` met.
     */
    val within: String? = null,
    /** Current platform matches (same rules as the step-level `platform` field) */
    val platform: PlatformTarget? = null,
    /** ViewModel state matches (requires a ViewModelStateProvider) */
    val state: StateCondition? = null,
    /** Current window size matches (named bucket or constraint object, in dp) */
    val responsive: ResponsiveCondition? = null,
    /**
     * Raw JSON keys outside the known set
     * (visible|notVisible|hidden|within|platform|state|responsive), i.e. written against a
     * newer schema than this driver. Non-empty → condition is treated as
     * unmet (skip), never run-anyway, never a hard error.
     */
    val unknownKeys: List<String> = emptyList()
)

@Serializable
data class StateCondition(
    val path: String,
    val equals: JsonElement
)

// MARK: - Launch Configuration

/**
 * App launch configuration applied before the app under test starts.
 * Android mapping: clearState -> in-process wipe of files/shared_prefs/
 * databases/cache/code_cache (persisted state only — process memory survives
 * the relaunch; `pm clear` would kill the instrumentation's own process),
 * permissions -> allow is `pm grant`; deny is an ASSERT of the current state
 * (satisfied when already denied, loud per-file failure when granted — an
 * in-run revoke kills the instrumented process, and grants persist across
 * update-installs, so a denied baseline comes from a pre-instrumentation
 * step); unset leaves inherited state untouched,
 * arguments -> JSONUI_TEST_ARGS string extra (JSON) on the relaunch intent.
 */
@Serializable
data class LaunchConfig(
    val clearState: Boolean? = null,
    /** Permission name (camera, microphone, location, ...) -> "allow" | "deny" | "unset" */
    val permissions: Map<String, String>? = null,
    /** Launch arguments passed to the app as JSON */
    val arguments: Map<String, JsonElement>? = null
)

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
    val durationMs: Long = 0,
    /** True when the case was skipped (skip flag, platform or responsive mismatch) */
    val skipped: Boolean = false,
    /**
     * Why the case was skipped ("platform" | "responsive" — results.schema.json
     * skipReason); null for plain `skip: true` skips and non-skipped results.
     */
    val skipReason: String? = null,
    /**
     * Machine-readable counterpart to [error], which is prose and changes
     * between releases (results.schema.json failureReason). Only meaningful
     * when the case failed; null elsewhere, and null on a failure whose cause
     * could not be classified at all — absent reads as unknown, never as
     * "no reason".
     */
    val failureReason: String? = null,
    /** Warnings recorded during the case (optional-step failures, baseline created, ...) */
    val warnings: List<String> = emptyList(),
    /**
     * How many times the case ran in total, retries included (1 = settled on
     * the first run). Null on skipped rows — a case that never ran has no
     * attempt count (results.schema.json attempts).
     */
    val attempts: Int? = null,
    /**
     * The orientation this case ASKED for, after resolving the whole chain:
     * a `setOrientation` step in the case, else the file's top-level
     * `orientation`, else the run default for this device's tier. Null when
     * nothing declared one.
     */
    val declaredOrientation: String? = null,
    /**
     * The orientation the case ACTUALLY ran in, read from the device rather
     * than derived from [declaredOrientation].
     *
     * Two fields because they can disagree, and on THIS driver they did:
     * until 1.12.0 `"portrait"` mapped to `setOrientationNatural()`, so a
     * tablet whose natural orientation is landscape stayed landscape while
     * every assertion passed. That is fixed, but the pair is what makes the
     * next such inversion visible instead of silent — a derived value could
     * not disagree, which is precisely the disagreement being measured.
     */
    val observedOrientation: String? = null
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

/**
 * `equals: null` is a value — `state` compares against null (spec §10.4: a
 * key holding null) — but kotlinx.serialization decodes a JSON null into a
 * nullable property as Kotlin null, which reads as "no equals" ("state
 * requires 'equals'"). This serializer is nullable itself, so it receives
 * the null token and keeps it as [kotlinx.serialization.json.JsonNull]; an
 * absent key still takes the property's default, null.
 */
object KeepNullJsonElement : kotlinx.serialization.KSerializer<JsonElement?> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        JsonElement.serializer().nullable.descriptor

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): JsonElement? =
        (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement()

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: JsonElement?) {
        if (value == null) encoder.encodeNull()
        else encoder.encodeSerializableValue(JsonElement.serializer(), value)
    }
}

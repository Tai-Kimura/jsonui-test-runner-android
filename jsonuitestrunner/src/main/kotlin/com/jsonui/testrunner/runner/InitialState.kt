package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.CaseIsolation
import com.jsonui.testrunner.models.FlowTest
import com.jsonui.testrunner.models.FlowTestStep
import com.jsonui.testrunner.models.InitialState
import com.jsonui.testrunner.models.ScreenTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A screen's canonical id (jsonui-cli `shared/core/screen_identity.json`):
 * the layout file's basename without `.json`, verbatim, a size-class variant
 * removed (`home@regular` -> `home`).
 */
object ScreenIdentity {
    private val VARIANT_CLASSES = setOf("compact", "medium", "regular")

    fun screenId(layoutPath: String): String {
        var base = layoutPath.substringAfterLast('/')
        if (base.endsWith(".json")) base = base.removeSuffix(".json")
        val at = base.lastIndexOf('@')
        if (at >= 0 && base.substring(at + 1) in VARIANT_CLASSES) base = base.substring(0, at)
        return base
    }
}

/**
 * Which launches a test file makes and what each carries (spec §10.2),
 * decided before anything runs. Pure, so the shared vector
 * `specs/vectors/initial-state-payload.json` drives it directly on the JVM,
 * and the runner follows it rather than deciding the same things again.
 */
data class InitialStatePlan(
    val launches: List<Launch> = emptyList(),
    val caseFailures: List<CaseFailure> = emptyList(),
    val warnings: List<Warning> = emptyList()
) {
    /** `before` is "file" (once, before the first case) or "case:<name>" (reopen, before that case). */
    data class Launch(val before: String, val screens: Map<String, Map<String, JsonElement>>?)

    data class CaseFailure(val caseName: String, val code: String)

    data class Warning(val code: String, val file: String, val caseName: String?)

    /** The launch that precedes the file's first case, if any. */
    val fileLaunch: Launch? get() = launches.firstOrNull { it.before == BEFORE_FILE }

    /** The launch that precedes [caseName] under reopen, if any. */
    fun caseLaunch(caseName: String): Launch? = launches.firstOrNull { it.before == "case:$caseName" }

    companion object {
        const val BEFORE_FILE = "file"
        const val CASE_NEEDS_REOPEN = "case-initial-state-needs-reopen"
        const val REFERENCED_IGNORED = "referenced-initial-state-ignored"

        /** The values of an initialState, null when there are none: an empty object is the same as none. */
        internal fun values(state: InitialState?): Map<String, JsonElement>? =
            state?.viewModel?.takeIf { it.isNotEmpty() }

        /**
         * The file's values with the case's over them, key by key. Shallow: a
         * case's value replaces the file's whole (objects too), and a case's
         * null is a value that replaces it.
         */
        internal fun merged(file: Map<String, JsonElement>?, case: Map<String, JsonElement>?): Map<String, JsonElement>? {
            val merged = LinkedHashMap(file.orEmpty())
            case.orEmpty().forEach { (key, value) -> merged[key] = value }
            return merged.takeIf { it.isNotEmpty() }
        }

        fun plan(screenTest: ScreenTest): InitialStatePlan {
            val screenId = ScreenIdentity.screenId(screenTest.source.layout)
            val fileValues = values(screenTest.initialState)

            if (screenTest.caseIsolation == CaseIsolation.REOPEN) {
                // Every case that runs opens afresh; a skipped one opens nothing.
                return InitialStatePlan(launches = screenTest.cases.filter { it.skip != true }.map { case ->
                    Launch("case:${case.name}", merged(fileValues, values(case.initialState))?.let { mapOf(screenId to it) })
                })
            }

            val launches = if (screenTest.launch != null || screenTest.mocks != null || fileValues != null) {
                listOf(Launch(BEFORE_FILE, fileValues?.let { mapOf(screenId to it) }))
            } else emptyList()
            // Under shared the screen opens once, before the first case: a
            // case's own initialState has no launch to ride.
            val caseFailures = screenTest.cases
                .filter { it.skip != true && values(it.initialState) != null }
                .map { CaseFailure(it.name, CASE_NEEDS_REOPEN) }
            return InitialStatePlan(launches, caseFailures)
        }

        /**
         * [referenced] resolves a file step's `file` to its screen test (null
         * when it cannot be loaded — the step itself reports that when it runs).
         */
        fun plan(flowTest: FlowTest, referenced: (String) -> ScreenTest?): InitialStatePlan {
            val screens = flowTest.initialState?.viewModels.orEmpty()
                .filterValues { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
            val launches = if (flowTest.launch != null || flowTest.mocks != null || screens != null) {
                listOf(Launch(BEFORE_FILE, screens))
            } else emptyList()
            val warnings = mutableListOf<Warning>()
            for (step in flowTest.steps.filter { it.isFileReference }) {
                val file = step.file ?: continue
                val screenTest = referenced(file) ?: continue
                warnings += referencedWarnings(step, file, screenTest)
            }
            return InitialStatePlan(launches, warnings = warnings)
        }

        /** A flow runs a referenced file / case without its initialState: the flow's one launch has happened. */
        private fun referencedWarnings(step: FlowTestStep, file: String, screenTest: ScreenTest): List<Warning> {
            val warnings = mutableListOf<Warning>()
            if (values(screenTest.initialState) != null) warnings += Warning(REFERENCED_IGNORED, file, null)
            val names = step.caseName?.let { listOf(it) }
                ?: step.cases?.takeIf { it.isNotEmpty() }
                ?: screenTest.cases.map { it.name }
            for (name in names) {
                val case = screenTest.cases.firstOrNull { it.name == name } ?: continue
                if (values(case.initialState) != null) warnings += Warning(REFERENCED_IGNORED, file, name)
            }
            return warnings
        }

        /** The message a warning prints. */
        fun message(warning: Warning): String {
            val target = warning.caseName?.let { "case '$it' of '${warning.file}'" } ?: "'${warning.file}'"
            return "initialState of $target is not applied inside a flow: the flow's launch has already happened " +
                "(${warning.code}). Put the values in the flow's initialState.viewModels."
        }

        /** The message a case failure carries. */
        fun message(failure: CaseFailure): String =
            "case '${failure.caseName}' has its own initialState, which cannot be delivered under caseIsolation " +
                "\"shared\": the screen is opened once, before the first case (${failure.code}). " +
                "Set caseIsolation to \"reopen\", or move the values to the file's initialState."
    }
}

/** The `JSONUI_TEST_INITIAL_STATE` value (spec §10.2). */
object InitialStatePayload {
    const val EXTRA = "JSONUI_TEST_INITIAL_STATE"

    fun encode(screens: Map<String, Map<String, JsonElement>>, launchId: String): String =
        JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "launchId" to JsonPrimitive(launchId),
                "screens" to JsonObject(screens.mapValues { (_, values) -> JsonObject(values) })
            )
        ).toString()
}

/** Thrown when the app did not confirm a payload (spec §10.5): a `setup` failure. */
class InitialStateNotConfirmedException(message: String) : IllegalStateException(message)

package com.jsonui.testrunner.runner

import android.content.Context
import com.jsonui.testrunner.models.FlowTest
import com.jsonui.testrunner.models.FlowTestStep
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestStep
import com.jsonui.testrunner.models.TestMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.InputStream

/**
 * Errors that can occur during test loading
 */
class CaseNotFoundException(caseName: String, file: String) : Exception("Test case '$caseName' not found in file: $file")
class NotAScreenTestException(file: String) : Exception("File reference must point to a screen test: $file")

/**
 * Represents a loaded test file
 */
sealed class LoadedTest {
    abstract val metadata: TestMetadata
    abstract val filePath: String

    data class Screen(
        val test: ScreenTest,
        override val filePath: String
    ) : LoadedTest() {
        override val metadata: TestMetadata get() = test.metadata
    }

    data class Flow(
        val test: FlowTest,
        override val filePath: String
    ) : LoadedTest() {
        override val metadata: TestMetadata get() = test.metadata
    }
}

/**
 * Loads test files from various sources
 */
class TestLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Base path for resolving relative file references */
    var basePath: String? = null
        private set

    /** Set base path from a file path */
    fun setBasePath(filePath: String) {
        basePath = File(filePath).parent
    }

    /**
     * Load a test from a file path
     */
    fun load(path: String): LoadedTest {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("Test file not found: $path")
        }
        // Store base path for file reference resolution
        basePath = file.parent
        return parseTest(file.readText(), path)
    }

    /**
     * Load a test from assets
     */
    fun loadFromAssets(context: Context, assetPath: String): LoadedTest {
        val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        return parseTest(content, assetPath)
    }

    /**
     * Load a test from raw resources
     */
    fun loadFromRaw(context: Context, rawResId: Int, name: String): LoadedTest {
        val content = context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }
        return parseTest(content, name)
    }

    /**
     * Load a test from an input stream
     */
    fun loadFromStream(inputStream: InputStream, name: String): LoadedTest {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseTest(content, name)
    }

    /**
     * Load a test from JSON string
     */
    fun loadFromString(jsonString: String, name: String = "inline"): LoadedTest {
        return parseTest(jsonString, name)
    }

    /**
     * Load all tests from a directory
     */
    fun loadAll(directory: String): List<LoadedTest> {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            throw IllegalArgumentException("Directory not found: $directory")
        }

        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".test.json") }
            .map { load(it.absolutePath) }
            .toList()
    }

    /**
     * Load all tests from assets directory
     */
    fun loadAllFromAssets(context: Context, assetsPath: String): List<LoadedTest> {
        val assetManager = context.assets
        val files = assetManager.list(assetsPath) ?: emptyArray()

        return files
            .filter { it.endsWith(".test.json") }
            .map { loadFromAssets(context, "$assetsPath/$it") }
    }

    private fun parseTest(content: String, path: String): LoadedTest {
        // First, determine the test type
        val typeMatch = """"type"\s*:\s*"(\w+)"""".toRegex().find(content)
        val type = typeMatch?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Test file must have a 'type' field")

        return when (type) {
            "screen" -> {
                val test = json.decodeFromString<ScreenTest>(content)
                LoadedTest.Screen(test, path)
            }
            "flow" -> {
                val test = json.decodeFromString<FlowTest>(content)
                LoadedTest.Flow(test, path)
            }
            else -> throw IllegalArgumentException("Unknown test type: $type")
        }
    }

    // MARK: - File Reference Resolution

    /**
     * Resolve a file reference to a ScreenTest
     */
    fun resolveFileReference(fileRef: String): ScreenTest {
        val url = resolveFileReferenceURL(fileRef)
        val loadedTest = load(url)

        return when (loadedTest) {
            is LoadedTest.Screen -> loadedTest.test
            is LoadedTest.Flow -> throw NotAScreenTestException(fileRef)
        }
    }

    /**
     * Resolve a file reference to test cases with args substitution
     */
    fun resolveFileReferenceCases(step: FlowTestStep): List<TestCase> {
        val fileRef = step.file ?: return emptyList()

        val screenTest = resolveFileReference(fileRef)
        val flowArgs = step.args ?: emptyMap()

        // If specific case is requested
        step.caseName?.let { caseName ->
            val testCase = screenTest.cases.find { it.name == caseName }
                ?: throw CaseNotFoundException(caseName, fileRef)
            return listOf(applyArgsSubstitution(testCase, flowArgs))
        }

        // If specific cases are requested
        step.cases?.takeIf { it.isNotEmpty() }?.let { caseNames ->
            return caseNames.map { caseName ->
                val testCase = screenTest.cases.find { it.name == caseName }
                    ?: throw CaseNotFoundException(caseName, fileRef)
                applyArgsSubstitution(testCase, flowArgs)
            }
        }

        // Return all cases if no specific case requested
        return screenTest.cases.map { applyArgsSubstitution(it, flowArgs) }
    }

    /**
     * Apply args substitution to a test case.
     * Merges screen default args with flow override args, then substitutes @{varName} placeholders.
     */
    fun applyArgsSubstitution(testCase: TestCase, flowArgs: Map<String, JsonElement> = emptyMap()): TestCase {
        // Merge screen default args with flow override args
        val mergedArgs = mutableMapOf<String, Any>()
        testCase.args?.forEach { (key, value) ->
            mergedArgs[key] = jsonElementToValue(value)
        }
        flowArgs.forEach { (key, value) ->
            mergedArgs[key] = jsonElementToValue(value)
        }

        // If no args, return original test case
        if (mergedArgs.isEmpty()) {
            return testCase
        }

        // Apply substitution to steps
        val substitutedSteps = testCase.steps.map { substituteArgsInStep(it, mergedArgs) }

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
            equals = substituteArgsInJsonElement(step.equals, args)
        )
    }

    /**
     * Substitute @{varName} placeholders in a string
     */
    private fun substituteArgsInString(string: String?, args: Map<String, Any>): String? {
        if (string == null) return null

        var result = string
        val pattern = """@\{([^}]+)\}""".toRegex()

        pattern.findAll(string).toList().reversed().forEach { match ->
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
    private fun substituteArgsInJsonElement(element: JsonElement?, args: Map<String, Any>): JsonElement? {
        if (element == null) return null
        if (element is JsonPrimitive && element.isString) {
            val substituted = substituteArgsInString(element.content, args)
            return JsonPrimitive(substituted)
        }
        return element
    }

    /**
     * Convert JsonElement to primitive value
     */
    private fun jsonElementToValue(element: JsonElement): Any {
        return when {
            element is JsonPrimitive -> {
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

    /**
     * Resolve a file reference path to an absolute path
     */
    private fun resolveFileReferenceURL(fileRef: String): String {
        val base = basePath ?: throw IllegalArgumentException("Base path not set for file reference resolution")

        // Parent of basePath (e.g., tests/ when basePath is tests/flows/)
        val parentBase = File(base).parent

        // Try different locations and file extensions
        // Priority: ../screens/ (sibling directory) first, then same directory
        val candidates = listOf(
            File(parentBase, "screens/$fileRef/$fileRef.test.json"),
            File(parentBase, "screens/$fileRef/$fileRef.json"),
            File(parentBase, "screens/$fileRef.test.json"),
            File(parentBase, "screens/$fileRef.json"),
            File(base, "$fileRef.test.json"),
            File(base, "$fileRef.json"),
            File(base, fileRef)
        )

        for (candidate in candidates) {
            if (candidate.exists()) {
                return candidate.absolutePath
            }
        }

        throw IllegalArgumentException("Test file not found: $fileRef")
    }
}

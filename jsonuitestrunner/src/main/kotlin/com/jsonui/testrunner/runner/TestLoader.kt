package com.jsonui.testrunner.runner

import android.content.Context
import com.jsonui.testrunner.models.FlowTest
import com.jsonui.testrunner.models.ScreenTest
import com.jsonui.testrunner.models.TestMetadata
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

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

    /**
     * Load a test from a file path
     */
    fun load(path: String): LoadedTest {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("Test file not found: $path")
        }
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
}

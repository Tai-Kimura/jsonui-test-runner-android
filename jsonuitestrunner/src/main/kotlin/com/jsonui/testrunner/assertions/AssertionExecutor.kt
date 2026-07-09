package com.jsonui.testrunner.assertions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.jsonui.testrunner.models.TestStep
import com.jsonui.testrunner.runner.ViewModelStateProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Executes test assertions using UI Automator
 * Uses resource-id for element matching (Compose testTag exposed via testTagsAsResourceId)
 *
 * All element assertions auto-wait: the condition is polled every 100ms until
 * it holds or the timeout (step `timeout` or the runner's defaultTimeout)
 * elapses; the failure message includes the last observed actual value.
 */
class AssertionExecutor(
    private val device: UiDevice,
    private val defaultTimeout: Long = 5000L
) {

    /** State provider for `state` assertions (injected by the runner) */
    var stateProvider: ViewModelStateProvider? = null

    /**
     * Baseline directory for `screenshot` assertions. Baselines live at
     * <baselineDir>/android/<name>.png. Defaults to the instrumented app's
     * external files dir (falling back to filesDir) when null.
     */
    var baselineDir: File? = null

    /** When true, `screenshot` assertions always overwrite the baseline and pass */
    var updateBaselines: Boolean = false

    /** Sink for non-fatal warnings (e.g. "baseline created"), set by the runner */
    var warningHandler: ((String) -> Unit)? = null

    /**
     * Execute an assertion step
     */
    fun execute(step: TestStep) {
        val assertion = step.assert ?: throw IllegalArgumentException("Step has no assert")
        val timeout = step.timeout?.toLong() ?: defaultTimeout

        when (assertion) {
            "visible" -> assertVisible(step, timeout)
            "notVisible" -> assertNotVisible(step, timeout)
            "enabled" -> assertEnabled(step, timeout)
            "disabled" -> assertDisabled(step, timeout)
            "text" -> assertText(step, timeout)
            "count" -> assertCount(step, timeout)
            "state" -> assertState(step, timeout)
            "screenshot" -> assertScreenshot(step, timeout)
            else -> throw IllegalArgumentException("Unknown assertion: $assertion")
        }
    }

    private fun assertVisible(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("visible requires 'id'")
        pollUntil(timeout, id) {
            findElement(id)
                ?: throw AssertionError("Element '$id' not found by resource-id within ${timeout}ms")
        }
    }

    private fun assertNotVisible(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("notVisible requires 'id'")
        // Passes as soon as the element is gone; polled from t=0 (no fixed pre-wait)
        pollUntil(timeout, id) {
            if (findElement(id) != null) {
                throw AssertionError("Element '$id' should not be visible but it is still visible after ${timeout}ms")
            }
        }
    }

    private fun assertEnabled(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("enabled requires 'id'")
        pollUntil(timeout, id) {
            val element = findElement(id)
                ?: throw AssertionError("Element '$id' not found by resource-id within ${timeout}ms")
            if (!element.isEnabled) {
                throw AssertionError("Element '$id' should be enabled but it is disabled")
            }
        }
    }

    private fun assertDisabled(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("disabled requires 'id'")
        pollUntil(timeout, id) {
            val element = findElement(id)
                ?: throw AssertionError("Element '$id' not found by resource-id within ${timeout}ms")
            if (element.isEnabled) {
                throw AssertionError("Element '$id' should be disabled but it is enabled")
            }
        }
    }

    private fun assertText(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("text requires 'id'")
        // State-driven UIs (Compose input -> handler -> state -> recompose)
        // update bound text asynchronously; a single read races the
        // recomposition (testrunner-android-asserttext-single-sample-race).
        pollUntil(timeout, id) {
            assertTextOnce(step, id)
        }
    }

    private fun assertTextOnce(step: TestStep, id: String) {
        val element = findElement(id)
            ?: throw AssertionError("Element '$id' not found by resource-id within a step of polling")

        // For Compose TextField, the editable value is on an EditText-classed
        // a11y node — either the tagged element itself or a descendant.
        val editText = if (element.className == "android.widget.EditText") element
            else element.findObject(By.clazz("android.widget.EditText"))

        val actualText: String = if (editText != null) {
            // Editable field: the INPUT VALUE is authoritative. An empty field
            // reads "" — NOT its placeholder/hint (a separate decoration node).
            // This mirrors iOS `XCUIElement.value` and is required for
            // cross-platform parity: without it the descendant-text aggregation
            // below would scoop up the placeholder for an empty/cleared field
            // (test-empty-textfield-text-ios-empty-android-placeholder).
            editText.text ?: ""
        } else {
            // Non-editable (Button, labeled CheckBox/Radio rows, ...) expose
            // their label on child text nodes, not on the testTag'd container,
            // while iOS (XCUITest label) and web (textContent) include descendant
            // text. Mirror that: aggregate descendant text when the node has none.
            var t = element.text ?: ""
            if (t.isEmpty()) {
                t = element.findObjects(By.clazz("android.widget.TextView"))
                    .mapNotNull { it.text }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
            t
        }

        when {
            step.equals != null -> {
                val expectedText = when (val value = step.equals) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
                if (actualText != expectedText) {
                    throw AssertionError("Expected text '$expectedText' but got '$actualText' for element '$id'")
                }
            }
            step.contains != null -> {
                if (!actualText.contains(step.contains)) {
                    throw AssertionError("Expected text containing '${step.contains}' but got '$actualText' for element '$id'")
                }
            }
            else -> throw IllegalArgumentException("text requires 'equals' or 'contains'")
        }
    }

    private fun assertCount(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("count requires 'id'")
        val expected = step.equals?.let {
            when (it) {
                is JsonPrimitive -> it.intOrNull
                else -> null
            }
        } ?: throw IllegalArgumentException("count requires 'equals' with integer value")

        // Poll until the count matches. `equals: 0` passes on absence, so we
        // must NOT pre-wait for at least one element here.
        pollUntil(timeout, id) {
            val actualCount = device.findObjects(By.res(id)).size
            if (actualCount != expected) {
                throw AssertionError("Expected $expected elements with id '$id', but found $actualCount")
            }
        }
    }

    private fun assertState(step: TestStep, timeout: Long) {
        val path = step.path ?: throw IllegalArgumentException("state requires 'path'")
        val expected = step.equals ?: throw IllegalArgumentException("state requires 'equals'")
        val provider = requireStateProvider()

        pollUntil(timeout, null) {
            val actual = provider.getValue(path)
            if (!stateValueMatches(actual, expected)) {
                throw AssertionError("Expected state '$path' to equal '$expected' but got '$actual'")
            }
        }
    }

    // MARK: - State helpers (shared with `when` / `repeat.while` conditions)

    /**
     * Instant check whether the state at `path` matches `expected`.
     * Throws when no state provider is configured (spec: a `state` condition
     * without a provider is an error, not a silent skip).
     */
    fun stateMatches(path: String, expected: JsonElement): Boolean {
        return stateValueMatches(requireStateProvider().getValue(path), expected)
    }

    private fun requireStateProvider(): ViewModelStateProvider {
        return stateProvider
            ?: throw IllegalStateException("'state' requires a ViewModelStateProvider but none is configured on the runner")
    }

    private fun stateValueMatches(actual: Any?, expected: JsonElement): Boolean {
        return when {
            expected is JsonNull -> actual == null
            expected is JsonPrimitive -> when {
                expected.isString -> actual?.toString() == expected.content
                expected.booleanOrNull != null -> (actual as? Boolean) == expected.booleanOrNull
                expected.doubleOrNull != null -> {
                    val actualNumber = (actual as? Number)?.toDouble()
                        ?: actual?.toString()?.toDoubleOrNull()
                    actualNumber == expected.doubleOrNull
                }
                else -> actual?.toString() == expected.content
            }
            else -> actual?.toString() == expected.toString()
        }
    }

    // MARK: - Screenshot assertion (visual regression)

    private fun assertScreenshot(step: TestStep, timeout: Long) {
        val name = step.name ?: throw IllegalArgumentException("screenshot requires 'name'")
        val threshold = step.threshold ?: 98.0

        var capture: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Failed to capture screenshot for '$name'")

        // Crop to the element's visible bounds when cropId is set
        step.cropId?.let { cropId ->
            val element = waitForElement(cropId, timeout)
            val bounds = element.visibleBounds
            val left = bounds.left.coerceIn(0, capture.width - 1)
            val top = bounds.top.coerceIn(0, capture.height - 1)
            val width = bounds.width().coerceIn(1, capture.width - left)
            val height = bounds.height().coerceIn(1, capture.height - top)
            capture = Bitmap.createBitmap(capture, left, top, width, height)
        }

        val dir = File(resolveBaselineDir(), "android")
        val baselineFile = File(dir, "$name.png")

        if (updateBaselines) {
            saveBitmap(capture, baselineFile)
            return
        }

        if (!baselineFile.exists()) {
            // Missing baseline: save the capture and pass with a warning
            saveBitmap(capture, baselineFile)
            warningHandler?.invoke("baseline created: ${baselineFile.absolutePath}")
            return
        }

        val baseline = BitmapFactory.decodeFile(baselineFile.absolutePath)
            ?: throw AssertionError("Failed to decode baseline image: ${baselineFile.absolutePath}")

        if (baseline.width != capture.width || baseline.height != capture.height) {
            throw AssertionError(
                "Screenshot '$name' size mismatch: baseline is ${baseline.width}x${baseline.height} " +
                    "but capture is ${capture.width}x${capture.height}"
            )
        }

        val similarity = computeSimilarity(baseline, capture)
        if (similarity < threshold) {
            throw AssertionError(
                "Screenshot '$name' similarity ${"%.2f".format(similarity)}% is below threshold ${threshold}%"
            )
        }
    }

    /**
     * Similarity = 100 x (matching pixels / total pixels); a pixel matches
     * when each RGBA channel differs by <= 16/255.
     */
    private fun computeSimilarity(baseline: Bitmap, capture: Bitmap): Double {
        val width = baseline.width
        val height = baseline.height
        val total = width * height
        if (total == 0) return 100.0

        val baselinePixels = IntArray(total)
        val capturePixels = IntArray(total)
        baseline.getPixels(baselinePixels, 0, width, 0, 0, width, height)
        capture.getPixels(capturePixels, 0, width, 0, 0, width, height)

        var matching = 0
        for (i in 0 until total) {
            val a = baselinePixels[i]
            val b = capturePixels[i]
            val alphaDiff = kotlin.math.abs(((a shr 24) and 0xFF) - ((b shr 24) and 0xFF))
            val redDiff = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val greenDiff = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val blueDiff = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
            if (alphaDiff <= 16 && redDiff <= 16 && greenDiff <= 16 && blueDiff <= 16) {
                matching++
            }
        }

        return 100.0 * matching / total
    }

    private fun resolveBaselineDir(): File {
        baselineDir?.let { return it }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // Helper functions

    /**
     * Poll a check every 100ms until it passes or the timeout elapses.
     * The last failure (with the current actual value) is rethrown at timeout.
     */
    private fun pollUntil(timeout: Long, searchedId: String?, check: () -> Unit) {
        val startTime = System.currentTimeMillis()
        var debugLogged = false

        while (true) {
            try {
                check()
                return
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() - startTime >= timeout) throw e

                // Debug: dump hierarchy once after 2 seconds of element polling
                if (searchedId != null && !debugLogged && System.currentTimeMillis() - startTime > 2000) {
                    debugLogged = true
                    dumpHierarchy(searchedId)
                }

                Thread.sleep(100)
            }
        }
    }

    private fun dumpHierarchy(id: String) {
        println("[AssertionExecutor] Still polling for '$id' by resource-id - dumping UI hierarchy...")
        try {
            val file = java.io.File.createTempFile("ui_dump", ".xml")
            device.dumpWindowHierarchy(file)
            val content = file.readText()
            println("[AssertionExecutor] UI Hierarchy (first 5000 chars):")
            println(content.take(5000))
            file.delete()
        } catch (e: Exception) {
            println("[AssertionExecutor] Failed to dump hierarchy: ${e.message}")
        }
    }

    /**
     * Find element by id using resource-id (testTag)
     */
    private fun findElement(id: String): UiObject2? {
        // Primary: find by resource-id (Compose testTag with testTagsAsResourceId = true)
        return device.findObject(By.res(id))
    }

    /**
     * Wait for element to appear by id
     */
    private fun waitForElement(id: String, timeout: Long): UiObject2 {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            // Find by resource-id (Compose testTag with testTagsAsResourceId = true)
            val element = device.findObject(By.res(id))
            if (element != null) return element

            Thread.sleep(100)
        }

        throw AssertionError("Element '$id' not found by resource-id within ${timeout}ms")
    }
}

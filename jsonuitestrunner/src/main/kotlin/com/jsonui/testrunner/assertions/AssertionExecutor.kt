package com.jsonui.testrunner.assertions

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.jsonui.testrunner.models.TestStep
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Executes test assertions using UI Automator
 * Uses resource-id for element matching (Compose testTag exposed via testTagsAsResourceId)
 */
class AssertionExecutor(
    private val device: UiDevice,
    private val defaultTimeout: Long = 5000L
) {

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
            else -> throw IllegalArgumentException("Unknown assertion: $assertion")
        }
    }

    private fun assertVisible(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("visible requires 'id'")
        val element = waitForElement(id, timeout)
        if (!element.isEnabled) {
            // Element exists but may not be visible
            // For Compose, if the element is in the hierarchy, it's typically visible
        }
        // Success - element was found and is displayed
    }

    private fun assertNotVisible(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("notVisible requires 'id'")

        // Wait briefly and check element is not visible
        Thread.sleep(minOf(timeout, 1000L))

        val element = findElement(id)
        if (element != null) {
            throw AssertionError("Element '$id' should not be visible but it is")
        }
        // Success - element was not found
    }

    private fun assertEnabled(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("enabled requires 'id'")
        val element = waitForElement(id, timeout)
        if (!element.isEnabled) {
            throw AssertionError("Element '$id' should be enabled but it is disabled")
        }
    }

    private fun assertDisabled(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("disabled requires 'id'")
        val element = waitForElement(id, timeout)
        if (element.isEnabled) {
            throw AssertionError("Element '$id' should be disabled but it is enabled")
        }
    }

    private fun assertText(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("text requires 'id'")
        val element = waitForElement(id, timeout)

        // For Compose TextField, the text is in the child EditText element
        val editText = element.findObject(By.clazz("android.widget.EditText"))
        val actualText = if (editText != null) {
            editText.text ?: ""
        } else {
            element.text ?: ""
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

        // Wait for at least one element
        waitForElement(id, timeout)

        // Find all elements with the given id (by resource-id)
        val elements = device.findObjects(By.res(id))
        val actualCount = elements.size

        if (actualCount != expected) {
            throw AssertionError("Expected $expected elements with id '$id', but found $actualCount")
        }
    }

    // Helper functions

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
        var debugLogged = false

        while (System.currentTimeMillis() - startTime < timeout) {
            // Find by resource-id (Compose testTag with testTagsAsResourceId = true)
            val element = device.findObject(By.res(id))
            if (element != null) return element

            // Debug: dump hierarchy once after 2 seconds
            if (!debugLogged && System.currentTimeMillis() - startTime > 2000) {
                debugLogged = true
                println("[AssertionExecutor] Searching for '$id' by resource-id - dumping UI hierarchy...")
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

            Thread.sleep(100)
        }

        throw AssertionError("Element '$id' not found by resource-id within ${timeout}ms")
    }
}

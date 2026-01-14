package com.jsonui.testrunner.actions

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.jsonui.testrunner.models.TestStep

/**
 * Executes test actions using UI Automator
 * Uses resource-id for element matching (Compose testTag exposed via testTagsAsResourceId)
 */
class ActionExecutor(
    private val device: UiDevice,
    private val defaultTimeout: Long = 5000L
) {

    /**
     * Execute an action step
     */
    fun execute(step: TestStep) {
        val action = step.action ?: throw IllegalArgumentException("Step has no action")
        val timeout = step.timeout?.toLong() ?: defaultTimeout

        when (action) {
            "tap" -> executeTap(step, timeout)
            "doubleTap" -> executeDoubleTap(step, timeout)
            "longPress" -> executeLongPress(step, timeout)
            "input" -> executeInput(step, timeout)
            "clear" -> executeClear(step, timeout)
            "scroll" -> executeScroll(step, timeout)
            "swipe" -> executeSwipe(step, timeout)
            "waitFor" -> executeWaitFor(step, timeout)
            "waitForAny" -> executeWaitForAny(step, timeout)
            "wait" -> executeWait(step)
            "back" -> executeBack()
            "screenshot" -> executeScreenshot(step)
            "alertTap" -> executeAlertTap(step, timeout)
            else -> throw IllegalArgumentException("Unknown action: $action")
        }
    }

    private fun executeTap(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("tap requires 'id'")
        val element = waitForElement(id, timeout)

        // If text is specified, tap on the specific text portion within the element
        val targetText = step.text
        if (targetText != null) {
            tapTextPortion(element, targetText)
        } else {
            element.click()
        }
    }

    private fun executeDoubleTap(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("doubleTap requires 'id'")
        val element = waitForElement(id, timeout)
        // UIAutomator doesn't have direct double-click, so we click twice
        element.click()
        Thread.sleep(50)
        element.click()
    }

    private fun executeLongPress(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("longPress requires 'id'")
        val element = waitForElement(id, timeout)
        element.longClick()
    }

    private fun executeInput(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("input requires 'id'")
        val value = step.value ?: throw IllegalArgumentException("input requires 'value'")
        val element = waitForElement(id, timeout)
        element.click() // Focus the field
        Thread.sleep(300) // Wait for keyboard to appear

        // For Compose TextField, try to find the actual EditText child
        val editText = element.findObject(By.clazz("android.widget.EditText"))
        if (editText != null) {
            editText.text = value
        } else {
            // Fallback: try setting text directly, then use keyboard input if that fails
            val success = try {
                element.text = value
                true
            } catch (e: Exception) {
                false
            }

            if (!success || element.text != value) {
                // Use keyboard input as fallback
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)
                // Clear existing text
                val currentText = element.text ?: ""
                repeat(currentText.length) {
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_DEL)
                }
                // Type new text character by character using shell
                for (char in value) {
                    when {
                        char == '@' -> {
                            device.executeShellCommand("input text '@'")
                        }
                        char == '.' -> {
                            device.executeShellCommand("input text '.'")
                        }
                        char.isLetterOrDigit() -> {
                            device.executeShellCommand("input text '$char'")
                        }
                        else -> {
                            device.executeShellCommand("input text '$char'")
                        }
                    }
                    Thread.sleep(10)
                }
            }
        }
    }

    private fun executeClear(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("clear requires 'id'")
        val element = waitForElement(id, timeout)
        element.click() // Focus the field first
        Thread.sleep(200)

        // For Compose TextField, try to find the actual EditText child
        val editText = element.findObject(By.clazz("android.widget.EditText"))
        if (editText != null) {
            editText.clear()
        } else {
            // Fallback to clearing the element directly
            element.clear()
        }
    }

    private fun executeScroll(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("scroll requires 'id'")
        val direction = step.direction ?: throw IllegalArgumentException("scroll requires 'direction'")

        waitForElement(id, timeout)

        val (startX, startY, endX, endY) = getSwipeCoordinates(direction)
        device.swipe(startX, startY, endX, endY, 20)
    }

    private fun executeSwipe(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("swipe requires 'id'")
        val direction = step.direction ?: throw IllegalArgumentException("swipe requires 'direction'")

        val element = waitForElement(id, timeout)
        val bounds = element.visibleBounds

        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val swipeDistance = minOf(bounds.width(), bounds.height()) / 2

        when (direction) {
            "up" -> device.swipe(centerX, centerY + swipeDistance, centerX, centerY - swipeDistance, 10)
            "down" -> device.swipe(centerX, centerY - swipeDistance, centerX, centerY + swipeDistance, 10)
            "left" -> device.swipe(centerX + swipeDistance, centerY, centerX - swipeDistance, centerY, 10)
            "right" -> device.swipe(centerX - swipeDistance, centerY, centerX + swipeDistance, centerY, 10)
            else -> throw IllegalArgumentException("Invalid direction: $direction")
        }
    }

    private fun executeWaitFor(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("waitFor requires 'id'")
        waitForElement(id, timeout)
    }

    private fun executeWaitForAny(step: TestStep, timeout: Long) {
        val ids = step.ids ?: throw IllegalArgumentException("waitForAny requires 'ids'")
        if (ids.isEmpty()) {
            throw IllegalArgumentException("waitForAny requires non-empty 'ids'")
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            for (id in ids) {
                // Find by resource-id (Compose testTag)
                val element = device.findObject(By.res(id))
                if (element != null) {
                    return
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError("None of elements [${ids.joinToString(", ")}] appeared within ${timeout}ms")
    }

    private fun executeWait(step: TestStep) {
        val ms = step.ms ?: throw IllegalArgumentException("wait requires 'ms'")
        Thread.sleep(ms.toLong())
    }

    private fun executeBack() {
        device.pressBack()
    }

    private fun executeScreenshot(step: TestStep) {
        val name = step.name ?: "screenshot_${System.currentTimeMillis()}"
        // Screenshot will be handled by the test runner
        // This is a placeholder - actual implementation depends on test framework setup
    }

    private fun executeAlertTap(step: TestStep, timeout: Long) {
        val buttonText = step.button ?: throw IllegalArgumentException("alertTap requires 'button'")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            // Try to find button by text in any dialog
            val button = device.findObject(By.text(buttonText))
            if (button != null) {
                button.click()
                return
            }

            // Try standard Android dialog button IDs
            val positiveButton = device.findObject(By.res("android:id/button1"))
            if (positiveButton != null && positiveButton.text == buttonText) {
                positiveButton.click()
                return
            }

            val negativeButton = device.findObject(By.res("android:id/button2"))
            if (negativeButton != null && negativeButton.text == buttonText) {
                negativeButton.click()
                return
            }

            val neutralButton = device.findObject(By.res("android:id/button3"))
            if (neutralButton != null && neutralButton.text == buttonText) {
                neutralButton.click()
                return
            }

            Thread.sleep(100)
        }

        throw AssertionError("Alert button '$buttonText' not found within ${timeout}ms")
    }

    // Helper functions

    /**
     * Wait for element to appear by id (using resource-id)
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

    private fun getSwipeCoordinates(direction: String): SwipeCoordinates {
        val displayWidth = device.displayWidth
        val displayHeight = device.displayHeight
        val centerX = displayWidth / 2
        val centerY = displayHeight / 2

        return when (direction) {
            "up" -> SwipeCoordinates(centerX, centerY + 300, centerX, centerY - 300)
            "down" -> SwipeCoordinates(centerX, centerY - 300, centerX, centerY + 300)
            "left" -> SwipeCoordinates(centerX + 300, centerY, centerX - 300, centerY)
            "right" -> SwipeCoordinates(centerX - 300, centerY, centerX + 300, centerY)
            else -> throw IllegalArgumentException("Invalid direction: $direction")
        }
    }

    private data class SwipeCoordinates(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    )

    /**
     * Tap on a specific text portion within an element
     * Calculates the approximate position of the target text and taps there
     */
    private fun tapTextPortion(element: UiObject2, targetText: String) {
        val fullText = element.text ?: throw IllegalArgumentException("Element has no text")
        val startIndex = fullText.indexOf(targetText)

        if (startIndex == -1) {
            throw IllegalArgumentException("Text '$targetText' not found in element text '$fullText'")
        }

        val endIndex = startIndex + targetText.length
        val totalLength = fullText.length

        if (totalLength == 0) {
            element.click()
            return
        }

        // Calculate the center position of the target text (as a ratio of the element width)
        val startRatio = startIndex.toFloat() / totalLength.toFloat()
        val endRatio = endIndex.toFloat() / totalLength.toFloat()
        val centerRatio = (startRatio + endRatio) / 2f

        // Calculate the tap coordinate
        val bounds = element.visibleBounds
        val tapX = bounds.left + (bounds.width() * centerRatio).toInt()
        val tapY = bounds.centerY()

        // Tap at the calculated position
        device.click(tapX, tapY)
    }
}

package com.jsonui.testrunner.actions

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.jsonui.testrunner.models.TestStep
import java.io.File

/**
 * Executes test actions using UI Automator
 * Uses resource-id for element matching (Compose testTag exposed via testTagsAsResourceId)
 */
class ActionExecutor(
    private val device: UiDevice,
    private val defaultTimeout: Long = 5000L
) {

    /**
     * Pluggable sink for the `screenshot` action. When set, the embedding
     * harness receives the screenshot name and owns capture + storage.
     * When null, the default implementation saves a PNG via
     * UiDevice.takeScreenshot into the instrumented app's filesDir.
     */
    var screenshotHandler: ((name: String) -> Unit)? = null

    /**
     * Runtime variable store shared with the runner. `readText` writes the
     * element text here; later steps reference it via @{name} placeholders.
     */
    var variableStore: MutableMap<String, String>? = null

    /** Sink for non-fatal warnings (e.g. no-op action stubs), set by the runner */
    var warningHandler: ((String) -> Unit)? = null

    /**
     * Directory for resolving relative `addMedia` paths. Defaults to the
     * instrumented app's external files dir (falling back to filesDir).
     * Limitation: the media files must already exist on the device — push
     * fixtures (e.g. via `adb push` or asset extraction) before the run.
     */
    var mediaFixturesDir: File? = null

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
            "typeText" -> executeTypeText(step)
            "clear" -> executeClear(step, timeout)
            "scroll" -> executeScroll(step, timeout)
            "scrollUntilVisible" -> executeScrollUntilVisible(step)
            "swipe" -> executeSwipe(step, timeout)
            "waitFor" -> executeWaitFor(step, timeout)
            "waitForAny" -> executeWaitForAny(step, timeout)
            "wait" -> executeWait(step)
            "back" -> executeBack()
            "hideKeyboard" -> executeHideKeyboard()
            "screenshot" -> executeScreenshot(step)
            "alertTap" -> executeAlertTap(step, timeout)
            "selectOption" -> executeSelectOption(step, timeout)
            "tapItem" -> executeTapItem(step, timeout)
            "selectTab" -> executeSelectTab(step, timeout)
            "readText" -> executeReadText(step, timeout)
            "setLocation" -> executeSetLocation(step)
            "addMedia" -> executeAddMedia(step)
            // setViewport is web-only by design (an Android window cannot be
            // freely resized), so it is permanently a no-op+warn here; width-
            // dependent asserts must self-gate with a matching `when.responsive`
            // (evaluated against the device's fixed size).
            "setViewport" -> executeNoOpStub(
                "setViewport",
                "the viewport cannot be resized on Android; dependent asserts should self-gate with when.responsive"
            )
            // emitHook drives a browser-side hook (window.__jsonuiTestHooks);
            // there is no equivalent injection channel into a native app, so it
            // is permanently a no-op+warn here. Gate dependent steps/asserts
            // with when.platform: web.
            "emitHook" -> executeNoOpStub(
                "emitHook",
                "browser-side hooks do not exist on Android; gate dependent steps with when.platform"
            )
            "setOrientation" -> executeSetOrientation(step)
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

        // Ghost-tap mitigation: when the UI did not change after the tap,
        // tap once more (retryTapIfNoChange semantics).
        if (step.retryTapIfNoChange == true) {
            val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            val changed = device.waitForWindowUpdate(packageName, 500L)
            if (!changed) {
                // Re-find the element; the first tap may have invalidated it
                val retryElement = waitForElement(id, timeout)
                if (targetText != null) {
                    tapTextPortion(retryElement, targetText)
                } else {
                    retryElement.click()
                }
            }
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

    /**
     * Type into whatever currently holds keyboard focus — no element id.
     * For fields that are focused but not directly targetable: an alpha(0)
     * Compose node is excluded from the semantics tree entirely, so By.res(id)
     * can never match (the standard invisible code-entry TextField pattern).
     * Focus is established app-side (auto-focus or a prior tap on a visible
     * container); this routes the characters to the focused editable via
     * Instrumentation key events.
     */
    private fun executeHideKeyboard() {
        // No-op when no keyboard is up — the action is a precondition
        // normalizer, not an assertion.
        if (!isKeyboardShown()) return

        // Back closes the soft keyboard when one is open (and only then —
        // the isKeyboardShown gate keeps this from navigating back).
        device.pressBack()
        device.waitForIdle()

        if (isKeyboardShown()) {
            throw IllegalStateException("hideKeyboard: keyboard still visible after back press")
        }
    }

    private fun isKeyboardShown(): Boolean {
        val out = device.executeShellCommand("dumpsys input_method")
        return Regex("mInputShown=true").containsMatchIn(out)
    }

    private fun executeTypeText(step: TestStep) {
        val value = step.value ?: throw IllegalArgumentException("typeText requires 'value'")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Type CHARACTER BY CHARACTER, settling between keys. A whole-string
        // sendStringSync burst corrupts Compose fields with a reactive two-way
        // text binding (kjui generates one per TextField: a LaunchedEffect
        // rewrites the field from the model on every change) — mid-burst the
        // field and model are transiently out of sync and the rewrite clobbers
        // characters that have not propagated yet, dropping/reordering input
        // (test-android-typetext-burst-injection-corrupts-reactive-textfield).
        // Espresso's typeText paces per character for the same reason.
        // sendStringSync must run off the main thread (it is) and synchronously
        // injects the key events into the focused window.
        for (ch in value) {
            instrumentation.sendStringSync(ch.toString())
            instrumentation.waitForIdleSync()
            Thread.sleep(TYPE_CHAR_DELAY_MS)
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

        // Scroll WITHIN the target element's bounds (parity with `swipe` /
        // `scrollUntilVisible` and with iOS, which scrolls the element's frame),
        // not at fixed screen center. The `id` locates *where* to scroll, not
        // merely that it exists. Fixed screen coordinates are a last resort only
        // when bounds are unavailable.
        val element = waitForElement(id, timeout)
        val bounds = element.visibleBounds
        if (!bounds.isEmpty) {
            scrollWithinBounds(bounds, direction)
        } else {
            val (startX, startY, endX, endY) = getSwipeCoordinates(direction)
            device.swipe(startX, startY, endX, endY, 20)
        }
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

        val handler = screenshotHandler
        if (handler != null) {
            handler(name)
            return
        }

        // Default: save a PNG like the iOS / web drivers do
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        device.takeScreenshot(File(dir, "$name.png"))
    }

    private fun executeAlertTap(step: TestStep, timeout: Long) {
        val buttonText = step.button ?: throw IllegalArgumentException("alertTap requires 'button'")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            // Prefer a CLICKABLE node with the label. A bare By.text match
            // walks the hierarchy depth-first and hits a dialog *title* that
            // shares the button's text (title "Sign Out" + button "Sign Out")
            // before the button — the click is a no-op and the dialog stays
            // open. iOS parity: its alertTap only queries .buttons[label].
            //
            // Same-node constraint (View Button: text lives on the clickable
            // node itself).
            val clickableButton = device.findObject(By.text(buttonText).clickable(true))
            if (clickableButton != null) {
                clickableButton.click()
                return
            }

            // Compose TextButton: in the accessibility tree UiAutomator sees,
            // the clickable node and the Text are SEPARATE nodes (semantics
            // merging is TalkBack semantics — text+clickable never co-occur on
            // one node here), so the same-node query above matches nothing.
            // Match a clickable whose shallow descendant carries the label;
            // maxDepth keeps a big clickable container with the title deep
            // inside from stealing the match.
            val composeButton = device.findObject(
                By.clickable(true).hasDescendant(By.text(buttonText), 3)
            )
            if (composeButton != null) {
                composeButton.click()
                return
            }

            // Try standard Android dialog button IDs (View-based AlertDialog)
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

        // Legacy fallback: some custom dialogs expose a tappable label without
        // the clickable flag. Only try the bare text match AFTER the clickable/
        // res-id poll expires — inside the loop it would hit the title first
        // on the very first pass, which is exactly the bug.
        val fallback = device.findObject(By.text(buttonText))
        if (fallback != null) {
            fallback.click()
            return
        }

        throw AssertionError("Alert button '$buttonText' not found within ${timeout}ms")
    }

    private fun executeTapItem(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("tapItem requires 'id'")
        val index = step.index ?: throw IllegalArgumentException("tapItem requires 'index'")

        // Find the item using the generated testTag pattern: {collectionId}_item_{index}
        val itemId = "${id}_item_${index}"
        val element = waitForElement(itemId, timeout)
        element.click()
    }

    private fun executeSelectTab(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("selectTab requires 'id'")
        val index = step.index ?: throw IllegalArgumentException("selectTab requires 'index'")

        // Find the tab using the generated testTag pattern: {tabViewId}_tab_{index}
        val tabId = "${id}_tab_${index}"
        val element = waitForElement(tabId, timeout)
        element.click()
    }

    private fun executeSelectOption(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("selectOption requires 'id'")

        // Step 1: Tap the SelectBox/DateSelectBox to open the bottom sheet
        val selectBox = waitForElement(id, timeout)
        selectBox.click()
        Thread.sleep(300) // Wait for bottom sheet animation

        // Step 2: Check if it's a DateSelectBox (wheel picker) or regular SelectBox
        val optionList = device.findObject(By.res("kjui_x7q_optionList"))
        val doneButton = device.findObject(By.res("kjui_x7q_done"))

        if (optionList != null) {
            // Regular SelectBox with option list
            selectFromOptionList(step, timeout)
        } else if (doneButton != null) {
            // DateSelectBox with wheel picker
            selectFromDatePicker(step, timeout)
        } else {
            throw AssertionError("Neither option list nor date picker appeared within ${timeout}ms")
        }
    }

    private fun selectFromOptionList(step: TestStep, timeout: Long) {
        // Select the option by index, label, or value
        when {
            step.index != null -> {
                // Select by index (preferred for cross-platform consistency)
                val optionElement = waitForElement("kjui_x7q_option_${step.index}", timeout)
                optionElement.click()
            }
            step.label != null || step.value != null -> {
                // Fallback: select by text (label or value).
                // Explicit non-null binding: Kotlin 2.x no longer smart-casts
                // `step.label ?: step.value` to String here.
                val text: String = step.label ?: step.value
                    ?: throw IllegalArgumentException("selectOption requires 'label' or 'value'")
                val startTime = System.currentTimeMillis()
                var found = false

                while (System.currentTimeMillis() - startTime < timeout && !found) {
                    val option = device.findObject(By.text(text))
                    if (option != null) {
                        option.click()
                        found = true
                    } else {
                        Thread.sleep(100)
                    }
                }

                if (!found) {
                    throw AssertionError("Option '$text' not found within ${timeout}ms")
                }
            }
            else -> throw IllegalArgumentException("selectOption requires 'index', 'label', or 'value'")
        }
        // Note: KotlinJsonUI SelectBox auto-closes on selection, no need to tap Done button
    }

    private fun selectFromDatePicker(step: TestStep, timeout: Long) {
        val value = step.value ?: throw IllegalArgumentException("selectOption for DateSelectBox requires 'value' with ISO format (e.g., '2024-01-15', '14:30', or '2024-01-15T14:30')")

        // Parse ISO format and select appropriate wheel values
        when {
            value.contains("T") -> {
                // DateTime format: "2024-01-15T14:30"
                val parts = value.split("T")
                if (parts.size == 2) {
                    selectDateComponents(parts[0], timeout)
                    selectTimeComponents(parts[1], timeout)
                }
            }
            value.contains(":") -> {
                // Time format: "14:30"
                selectTimeComponents(value, timeout)
            }
            value.contains("-") -> {
                // Date format: "2024-01-15"
                selectDateComponents(value, timeout)
            }
            else -> throw IllegalArgumentException("Invalid date/time format: $value. Use ISO format (e.g., '2024-01-15', '14:30', or '2024-01-15T14:30')")
        }

        // Tap Done button to confirm selection
        val doneButton = waitForElement("kjui_x7q_done", timeout)
        doneButton.click()
    }

    private fun selectDateComponents(dateString: String, timeout: Long) {
        // Parse "2024-01-15" format
        val parts = dateString.split("-")
        if (parts.size != 3) {
            throw IllegalArgumentException("Invalid date format: $dateString. Expected YYYY-MM-DD")
        }

        val year = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid year: ${parts[0]}")
        val month = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid month: ${parts[1]}")
        val day = parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid day: ${parts[2]}")

        // Click on year wheel item
        val yearElement = device.findObject(By.res("kjui_x7q_year_$year"))
        if (yearElement != null) {
            yearElement.click()
            Thread.sleep(100)
        }

        // Click on month wheel item (0-indexed internally)
        val monthElement = device.findObject(By.res("kjui_x7q_month_${month - 1}"))
        if (monthElement != null) {
            monthElement.click()
            Thread.sleep(100)
        }

        // Click on day wheel item
        val dayElement = device.findObject(By.res("kjui_x7q_day_$day"))
        if (dayElement != null) {
            dayElement.click()
            Thread.sleep(100)
        }
    }

    private fun selectTimeComponents(timeString: String, timeout: Long) {
        // Parse "14:30" format
        val parts = timeString.split(":")
        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid time format: $timeString. Expected HH:mm")
        }

        val hour = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid hour: ${parts[0]}")
        val minute = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid minute: ${parts[1]}")

        // Click on hour wheel item
        val hourElement = device.findObject(By.res("kjui_x7q_hour_$hour"))
        if (hourElement != null) {
            hourElement.click()
            Thread.sleep(100)
        }

        // Click on minute wheel item
        val minuteElement = device.findObject(By.res("kjui_x7q_minute_$minute"))
        if (minuteElement != null) {
            minuteElement.click()
            Thread.sleep(100)
        }
    }

    private fun executeScrollUntilVisible(step: TestStep) {
        val id = step.id ?: throw IllegalArgumentException("scrollUntilVisible requires 'id'")
        val direction = step.direction ?: "down"
        val timeout = step.timeout?.toLong() ?: 20000L

        if (device.findObject(By.res(id)) != null) return

        // Resolve the scrollable container: explicit id, else the app-under-test
        // window bounds so fallback swipes stay ON the app surface (a fixed
        // screen-center swipe can drift onto the status bar / notification shade
        // over repeated scrolls and hide the app). getSwipeCoordinates is the
        // final fallback only when even the app bounds are unavailable.
        val containerBounds: Rect? = step.container?.let { containerId ->
            device.findObject(By.res(containerId))?.visibleBounds
        } ?: appSurfaceBounds()

        val startTime = System.currentTimeMillis()
        var previousSnapshot: String? = null
        var unchangedCount = 0

        while (System.currentTimeMillis() - startTime < timeout) {
            if (containerBounds != null && !containerBounds.isEmpty) {
                scrollWithinBounds(containerBounds, direction)
            } else {
                val (sx, sy, ex, ey) = getSwipeCoordinates(direction)
                device.swipe(sx, sy, ex, ey, 20)
            }

            // Let the fling settle before looking: an immediate findObject reads
            // the mid-scroll (or not-yet-updated) a11y tree. The bounded wait
            // both settles and polls for the target.
            device.waitForIdle(1000)
            if (device.wait(Until.hasObject(By.res(id)), 800)) return

            // End-reached detection: two consecutive scrolls with no change.
            // Never silently disabled — error states become distinct sentinel
            // strings that still participate in the comparison (a persistently
            // unreadable tree ends in the end-of-scroll diagnostic below
            // instead of burning the full timeout).
            val snapshot = scrollSnapshot()
            if (previousSnapshot != null && snapshot == previousSnapshot) {
                unchangedCount++
                if (unchangedCount >= 1) {
                    // End of scroll (or the a11y tree froze): a fling that lands
                    // on the overscroll clamp can leave the Compose semantics
                    // tree one swipe stale with no resync trigger — the target
                    // is rendered on screen but absent from the frozen tree
                    // (measured on a bottom-flush target: the dumped hierarchy
                    // trailed the real scroll offset by exactly one swipe).
                    // A small reverse drag forces a scroll event and a fresh
                    // semantics pass before the final verdict.
                    nudgeBackward(containerBounds, direction)
                    device.waitForIdle(1000)
                    if (device.wait(Until.hasObject(By.res(id)), 1500)) return
                    throw AssertionError("Element '$id' not found after scrolling to the end")
                }
            } else {
                unchangedCount = 0
            }
            previousSnapshot = snapshot
        }

        // Last look before declaring timeout — the loop may have exited right
        // after a swipe whose settle revealed the target.
        if (device.findObject(By.res(id)) != null) return
        throw AssertionError("Element '$id' did not become visible within ${timeout}ms of scrolling")
    }

    /**
     * Change-detection snapshot: hash of the full window hierarchy dump, so
     * any node's bounds/text change (i.e. actual scroll progress anywhere in
     * the tree) reads as "changed". Shallow alternatives fail both ways:
     * the app root's direct children are a full-screen container whose id and
     * bounds never change while scrolling (constant snapshot → end detection
     * fires immediately), and the pre-1.6.2 join of child ids/text was empty
     * on Compose windows (empty snapshot → detection silently disabled and
     * the full timeout burned). Error states return distinct sentinels that
     * still participate in the comparison.
     */
    private fun scrollSnapshot(): String = runCatching {
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        val digest = java.security.MessageDigest.getInstance("MD5").digest(out.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrElse { e -> "<error:${e.javaClass.simpleName}>" }

    /**
     * Small, slow reverse drag (not a fling) — enough to force a scroll event
     * and a fresh Compose semantics pass at the overscroll clamp without
     * materially losing the end position.
     */
    private fun nudgeBackward(bounds: Rect?, direction: String) {
        val b = bounds ?: appSurfaceBounds() ?: return
        val cx = b.centerX()
        val cy = b.centerY()
        val d = 60 // px each way; slow steps make it a drag, not a fling
        when (direction) {
            // Opposite finger motion of scrollWithinBounds for each direction.
            "up" -> device.swipe(cx, cy + d, cx, cy - d, 40)
            "down" -> device.swipe(cx, cy - d, cx, cy + d, 40)
            "left" -> device.swipe(cx + d, cy, cx - d, cy, 40)
            "right" -> device.swipe(cx - d, cy, cx + d, cy, 40)
        }
    }

    /**
     * The app-under-test window bounds. Used to keep fallback scroll gestures on
     * the app surface instead of the raw screen center (which can drift onto the
     * status bar / notification shade). Returns null if the window can't be found.
     */
    private fun appSurfaceBounds(): Rect? = runCatching {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        device.findObject(By.pkg(pkg))?.visibleBounds
    }.getOrNull()

    private fun scrollWithinBounds(bounds: Rect, direction: String) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val dy = (bounds.height() * 0.35).toInt()
        val dx = (bounds.width() * 0.35).toInt()
        when (direction) {
            // Content moves toward `direction`; the finger swipes the opposite way.
            "up" -> device.swipe(cx, cy - dy, cx, cy + dy, 20)
            "down" -> device.swipe(cx, cy + dy, cx, cy - dy, 20)
            "left" -> device.swipe(cx - dx, cy, cx + dx, cy, 20)
            "right" -> device.swipe(cx + dx, cy, cx - dx, cy, 20)
            else -> throw IllegalArgumentException("Invalid direction: $direction")
        }
    }

    private fun executeReadText(step: TestStep, timeout: Long) {
        val id = step.id ?: throw IllegalArgumentException("readText requires 'id'")
        val variable = step.variable ?: throw IllegalArgumentException("readText requires 'variable'")
        val element = waitForElement(id, timeout)
        val text = element.text ?: ""
        val store = variableStore
            ?: throw IllegalStateException("readText requires a variable store (set ActionExecutor.variableStore)")
        store[variable] = text
    }

    private fun executeSetLocation(step: TestStep) {
        val latitude = step.latitude ?: throw IllegalArgumentException("setLocation requires 'latitude'")
        val longitude = step.longitude ?: throw IllegalArgumentException("setLocation requires 'longitude'")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageName = context.packageName
        try {
            // Enable mock locations for the instrumented app
            device.executeShellCommand("appops set $packageName android:mock_location allow")

            val locationManager = InstrumentationRegistry.getInstrumentation()
                .context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = LocationManager.GPS_PROVIDER
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
            val mockLocation = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                this.accuracy = 1.0f
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(provider, mockLocation)
        } catch (e: Exception) {
            throw AssertionError("setLocation failed: ${e.message}. Ensure the app has mock-location permission.")
        }
    }

    private fun executeAddMedia(step: TestStep) {
        val paths = step.paths ?: throw IllegalArgumentException("addMedia requires 'paths'")
        if (paths.isEmpty()) throw IllegalArgumentException("addMedia 'paths' must be non-empty")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseDir = mediaFixturesDir
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir

        for (path in paths) {
            val file = if (File(path).isAbsolute) File(path) else File(baseDir, path)
            if (!file.exists()) {
                throw AssertionError("addMedia file not found: ${file.absolutePath}")
            }
            val mimeType = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                else -> throw IllegalArgumentException("addMedia unsupported file type: ${file.name}")
            }
            val isVideo = mimeType.startsWith("video/")
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw AssertionError("addMedia could not insert ${file.name} into MediaStore")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw AssertionError("addMedia could not open output stream for ${file.name}")
        }
    }

    /**
     * Rotate the device: landscape → setOrientationLeft, portrait →
     * setOrientationNatural. Assumes a portrait-natural device (phones /
     * portrait-default emulators); on a landscape-natural tablet "portrait"
     * restores the natural — landscape — orientation instead. Waits for idle
     * (plus a short settle) so the rotated layout is stable before the next
     * step; responsive conditions re-read the live window size afterwards, so
     * `landscape` / `*-landscape` buckets become exercisable.
     */
    private fun executeSetOrientation(step: TestStep) {
        val orientation = step.orientation
            ?: throw IllegalArgumentException("setOrientation requires 'orientation'")
        when (orientation) {
            "landscape" -> device.setOrientationLeft()
            "portrait" -> device.setOrientationNatural()
            else -> throw IllegalArgumentException(
                "Invalid orientation: $orientation (expected 'portrait' or 'landscape')"
            )
        }
        device.waitForIdle(defaultTimeout)
        Thread.sleep(500) // rotation animation + Compose semantics settle
    }

    /**
     * No-op stub for actions this driver deliberately does not (yet) execute.
     * Warns through the runner's warning sink (surfaces in TestResult.warnings)
     * and on stdout, mirroring the AssertionExecutor convention.
     */
    private fun executeNoOpStub(action: String, reason: String) {
        val message = "'$action' is a no-op on this driver: $reason"
        println("[ActionExecutor] Warning: $message")
        warningHandler?.invoke(message)
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
        // Preferred: a clickable descendant hit-target carrying the range text.
        // KotlinJsonUI PartialAttributesText (2.11.0+) emits one per clickable
        // range, sized to the real glyph rect — exact for centered/matchParent
        // and wrapped labels where the proportional estimate below misses
        // (test-partialattributes-subrange-tap-misses-on-centered-matchparent-label).
        val hitTarget = element.findObject(By.desc(targetText).clickable(true))
            ?: element.findObject(By.text(targetText).clickable(true))
        if (hitTarget != null) {
            hitTarget.click()
            return
        }

        // Fallback: proportional position (assumes left-aligned, single-line,
        // full-width text). The text may live on a descendant TextView rather
        // than the tagged node itself — use that node's bounds when it does.
        val textNode = if (element.text != null) element
            else element.findObjects(By.clazz("android.widget.TextView"))
                .firstOrNull { it.text?.contains(targetText) == true } ?: element
        val fullText = textNode.text ?: throw IllegalArgumentException("Element has no text")
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
        val bounds = textNode.visibleBounds
        val tapX = bounds.left + (bounds.width() * centerRatio).toInt()
        val tapY = bounds.centerY()

        // Tap at the calculated position
        device.click(tapX, tapY)
    }

    companion object {
        /**
         * Inter-character settle for typeText. waitForIdleSync alone covers
         * recomposition; the extra margin lets the field->model->field
         * round-trip of a reactive two-way binding converge before the next
         * key event.
         */
        private const val TYPE_CHAR_DELAY_MS = 50L
    }
}

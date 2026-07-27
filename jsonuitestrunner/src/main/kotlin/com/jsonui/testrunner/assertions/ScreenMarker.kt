package com.jsonui.testrunner.assertions

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

/**
 * Screen identity beacon emitted by `kjui build` into generated screen views.
 *
 * Tests never spell the marker — they name a screen and the driver resolves
 * it here. Canon: jsonui-cli `shared/core/screen_identity.json`.
 */
object ScreenMarker {
    const val PREFIX = "__screen_"

    fun tagFor(screenId: String): String = "$PREFIX$screenId"

    /**
     * Turns a failed screen assertion into one of the canonical failure
     * classes, so the message says what went wrong rather than just "not
     * found". The class names the likely CAUSE, not a severity — every one of
     * them fails the assertion just the same. A missing marker anywhere points
     * at the build (stale generated code or a stale library pin); the previous
     * screen still being the only one present points at the app or the test.
     */
    fun diagnosis(device: UiDevice, screenId: String): String {
        val present = presentMarkers(device)
        return when {
            present.isEmpty() ->
                "marker-absent: no screen marker anywhere. The app's generated code or its " +
                    "KotlinJsonUI pin is likely stale — rebuild with `jui build`. " +
                    "(Markers are debug-build only.)"
            else ->
                "previous-screen-only: '$screenId' is not displayed; displayed screens are $present"
        }
    }

    /** Screen ids whose markers are currently findable. */
    private fun presentMarkers(device: UiDevice): List<String> =
        device.findObjects(By.res(java.util.regex.Pattern.compile("\\Q$PREFIX\\E.*")))
            .mapNotNull { it.resourceName }
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .distinct()
}

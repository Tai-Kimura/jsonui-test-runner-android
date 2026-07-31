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
     * them fails the assertion just the same.
     *
     * A missing marker has TWO different causes, and the driver already holds
     * the datum that separates them: the foreground package. When another app
     * owns the screen (measured in the wild: Google Photos threw its backup
     * promo over a running lane), no marker can be visible and the generated
     * code is not the suspect — pointing at `jui build` there sends the
     * reader away from the cause. Only when the app under test IS foreground
     * does the stale-build hint apply.
     */
    fun diagnosis(device: UiDevice, screenId: String, appPackage: String? = null): String {
        val present = presentMarkers(device)
        if (present.isNotEmpty()) {
            return "previous-screen-only: '$screenId' is not displayed; displayed screens are $present"
        }
        val foreground = device.currentPackageName
        if (appPackage != null && foreground != null && foreground != appPackage) {
            return "marker-absent: the foreground app is $foreground, not $appPackage — " +
                "another app owns the screen (no screen marker anywhere; the generated " +
                "code is not the suspect)."
        }
        return "marker-absent: no screen marker anywhere" +
            (foreground?.let { " (foreground: $it)" } ?: "") +
            ". The app's generated code or its KotlinJsonUI pin is likely stale — " +
            "rebuild with `jui build`. (Markers are debug-build only.)"
    }

    /** Screen ids whose markers are currently findable. */
    private fun presentMarkers(device: UiDevice): List<String> =
        device.findObjects(By.res(java.util.regex.Pattern.compile("\\Q$PREFIX\\E.*")))
            .mapNotNull { it.resourceName }
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .distinct()
}

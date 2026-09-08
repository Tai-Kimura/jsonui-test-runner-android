package com.jsonui.testrunner.actions

/**
 * Which UiDevice call realises a requested orientation.
 *
 * The distinction this type exists to hold is NATURAL-RELATIVE versus
 * RESULT-ORIENTED, and it is the whole defect:
 *
 *   `setOrientationLeft()`    -> rotateWithCommand(1) unconditionally.
 *   `setOrientationNatural()` -> rotation 0 unconditionally.
 *
 * Both name a rotation relative to the device's natural orientation, and
 * "natural" is portrait only on portrait-natural hardware. On a tablet whose
 * natural orientation is landscape, BOTH arms invert: `"landscape"` turns the
 * device portrait and `"portrait"` leaves it landscape. Measured on the
 * conf_ci AVD (2560x1600, rotation 0): a screen recording fixes its canvas at
 * the size it started with, so a mid-run rotation shows up as letterboxing —
 * the tablet recording shows the `"landscape"` step producing a PORTRAIT frame
 * inside a landscape canvas, and the `"portrait"` step restoring full-width
 * landscape. The phone recording shows the opposite, which is the control.
 *
 * The replacements measure the display and rotate to the requested RESULT.
 * Read out of the 2.3.0 bytecode rather than assumed from the names, because
 * assuming from the name is how the current pair got here:
 *
 *   setOrientationPortrait(d):
 *     if (getDisplayHeight(d) >= getDisplayWidth(d)) freezeRotation(d)
 *     else rotateWithCommand(if (isNaturalOrientation(d)) 1 else 0, d)
 *   setOrientationLandscape(d):
 *     if (getDisplayWidth(d) >= getDisplayHeight(d)) freezeRotation(d)
 *     else rotateWithCommand(if (isNaturalOrientation(d)) 1 else 0, d)
 *   isNaturalOrientation(d): getDisplayRotation(d) == 0 || == 2
 *
 * So each already asks the display what it currently is, freezes when it is
 * already right, and otherwise picks the rotation that gets there — on any
 * natural orientation. Both use `>=`, so a square display freezes either way;
 * noted rather than handled, since no such device is in the matrix.
 *
 * Requires uiautomator 2.3.0, which this driver already depends on
 * (gradle/libs.versions.toml `uiautomator = "2.3.0"`).
 */
enum class OrientationCommand {
    /** `UiDevice.setOrientationPortrait()` — height >= width when it returns. */
    PORTRAIT,

    /** `UiDevice.setOrientationLandscape()` — width >= height when it returns. */
    LANDSCAPE;

    companion object {
        /**
         * The command for a schema `orientation` value, or null when the value
         * is not one this driver knows.
         *
         * Null rather than a default: an unrecognised orientation that quietly
         * became one of the two would leave the device in an orientation
         * nobody asked for, and every assertion in the case would still pass.
         * The caller turns null into a failure that names the value.
         */
        fun forOrientation(orientation: String): OrientationCommand? = when (orientation) {
            "portrait" -> PORTRAIT
            "landscape" -> LANDSCAPE
            else -> null
        }
    }
}

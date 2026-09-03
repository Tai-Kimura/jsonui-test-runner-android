package com.jsonui.testrunner.runner

import android.app.Instrumentation
import android.graphics.Rect

/**
 * The ONE resolver for "the window the app under test occupies".
 *
 * Root of the active accessibility window, guarded by package, NOT
 * `device.findObject(By.pkg(pkg))`. `By.pkg` matches an ARBITRARY first node
 * of the package — whichever the BFS reaches first — so what it returns
 * depends on a11y-tree order, not on anything the caller means:
 *  - responsive gating (1.6.5): measured on a Compose tablet window it
 *    returned a 24x24dp icon leaf, flipping a 1280x800dp device into
 *    `compact`.
 *  - scrollUntilVisible fallback (1.8.6): right after a bottom sheet closed
 *    it returned a ~148x63px header node at x=0, so the "app surface" swipe
 *    ran at x=74 — inside the gesture-navigation back zone — and each swipe
 *    popped a screen (`CoreBackPreview: startBackNavigation` in logcat).
 *
 * 1.6.5 fixed only the responsive branch, leaving a divergent copy of the
 * trap in the scroll path. Both now go through here; there is no other
 * `By.pkg` root acquisition in the driver (guarded by a unit test that greps
 * the sources).
 *
 * The package guard skips roots owned by another process (an IME window
 * being active) rather than measuring the wrong window; callers decide the
 * fallback (display size) themselves because it differs by use.
 */
object AppWindow {
    /**
     * Bounds in screen px of the app-under-test's active window root, or null
     * when the active window belongs to another package or the tree is not
     * readable. Never returns an empty rect.
     */
    fun rootBounds(instrumentation: Instrumentation): Rect? = runCatching {
        val pkg = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.rootInActiveWindow
            ?.takeIf { it.packageName == pkg }
            ?.let { root -> Rect().also { root.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
    }.getOrNull()
}

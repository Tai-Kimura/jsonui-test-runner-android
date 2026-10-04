package com.jsonui.testrunner.runner

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Method

/**
 * UiAutomation's node cache, dropped before a find looks again.
 *
 * The cache (the AccessibilityCache of this process's UiAutomation) is
 * refreshed only by the accessibility events the app sends. Compose sends
 * none while UiAutomator is the only service connected
 * (AndroidComposeViewAccessibilityDelegateCompat: `isEnabled` filters
 * UiAutomator out and `sendEvent` returns early), so after a change the
 * cache keeps the old nodes and every find reads them again: a consumer's
 * assertText polled the page from before a swipe for 8 s and failed 6 of 6
 * (ticket test-android-stale-retry-rereads-the-same-cached-node), while
 * clearing the cache every 500 ms from the test turned it green.
 *
 * So every wait that looks again after a stale or absent result drops the
 * cache first ([clearBeforeRetry]; NodeCacheCensusTest holds the driver to
 * it). `clearCache()` exists from API 34 and is called by reflection; below
 * that this does nothing and says so.
 */
object NodeCache {

    private val clearCache: Method? by lazy {
        runCatching { UiAutomation::class.java.getMethod("clearCache") }.getOrNull()
    }

    /** Drop the cache. False when the platform has no clearCache (below API 34) or it failed. */
    fun clear(
        automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    ): Boolean = clear {
        val method = clearCache ?: return@clear false
        method.invoke(automation)
        true
    }

    /** The test seam: [action] is the clear itself. Never throws. */
    internal fun clear(action: () -> Boolean): Boolean = runCatching(action).getOrDefault(false)

    /** For [Deadline.poll]'s `beforeRetry`: drop the cache before each look after the first. */
    val clearBeforeRetry: () -> Unit = { clear() }
}

package com.jsonui.testrunner.actions

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A full-width, full-height surface that records the finger stream the
 * `swipe` action sends it (SwipeEdgeOnDeviceTest): where the DOWN landed,
 * whether an UP ended the stream (a system Back gesture steals it and the
 * view sees a CANCEL or nothing), and how far it travelled. Views, not
 * Compose: the driver has no Compose dependency, and the question is the
 * system's, not the toolkit's — a DOWN inside the back-gesture edge zone is
 * taken by the system before any view sees a drag.
 *
 * It names itself `swipe_edge_probe` as the driver finds elements, by
 * resource-id (a Compose testTag under testTagsAsResourceId); a View without
 * an R id says it through its accessibility node.
 */
class SwipeEdgeProbeActivity : Activity() {
    companion object {
        const val PROBE_ID = "swipe_edge_probe"
        @Volatile var downX: Float = Float.NaN
        @Volatile var upDx: Float = Float.NaN
        @Volatile var cancelled: Boolean = false
        @Volatile var finishedByBack: Boolean = false
        @Volatile var resumed: Boolean = false

        fun reset() {
            downX = Float.NaN; upDx = Float.NaN; cancelled = false; finishedByBack = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surface = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downX = event.rawX
                    MotionEvent.ACTION_UP -> upDx = event.rawX - downX
                    MotionEvent.ACTION_CANCEL -> cancelled = true
                }
                return true
            }
        }
        surface.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.viewIdResourceName = PROBE_ID
            }
        }
        surface.isFocusable = true
        surface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setContentView(surface)
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { super.onPause(); resumed = false }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishedByBack = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

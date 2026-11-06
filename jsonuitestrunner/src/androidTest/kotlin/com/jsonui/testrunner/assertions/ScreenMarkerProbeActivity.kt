package com.jsonui.testrunner.assertions

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout

/**
 * A surface that shows screen markers when it is told to, for the arms on when
 * a screen arrives against the look that judges it (ScreenVerdictOnDeviceTest).
 * A View with no R id says its marker through its accessibility node.
 */
class ScreenMarkerProbeActivity : Activity() {
    companion object {
        @Volatile var current: ScreenMarkerProbeActivity? = null
    }

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        current = this
    }

    /** Replace what is shown with these screens' markers. */
    fun show(vararg screens: String) = runOnUiThread {
        root.removeAllViews()
        screens.forEach { screen ->
            val marker = View(this)
            marker.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = ScreenMarker.tagFor(screen)
                }
            }
            marker.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            marker.isFocusable = true
            root.addView(marker, LinearLayout.LayoutParams(200, 200))
        }
    }
}

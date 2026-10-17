package com.jsonui.testrunner.actions

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A form whose ScrollView carries the content on by [overshootPx] after the
 * first upward drag (ReapproachAfterOvershootOnDeviceTest): the shape of an
 * edge-clearance drag that overshoots, so the target it was moving leaves
 * through the TOP. Armed once, by the test, after it has placed the target;
 * every later gesture behaves normally.
 */
class OvershootProbeActivity : Activity() {
    companion object {
        const val SCROLL_ID = "overshoot_scroll"
        const val TARGET_ID = "overshoot_target"
        const val ROWS_ABOVE = 30
        const val ROWS_BELOW = 30
        const val ROW_DP = 64
        @Volatile var scrollView: ScrollView? = null
        @Volatile var target: View? = null
        @Volatile var overshootPx = 0
        @Volatile var overshoots = 0
        @Volatile var taps = 0
    }

    private fun named(view: View, id: String): View {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.viewIdResourceName = id
            }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overshootPx = 0; overshoots = 0; taps = 0
        val px = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }
        val row = { label: String -> TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW_DP))
        } }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(ROWS_ABOVE) { column.addView(row("row $it")) }
        val t = TextView(this).apply {
            text = "+ Add"
            setBackgroundColor(Color.LTGRAY)
            isClickable = true
            setOnClickListener { taps++ }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW_DP))
        }
        column.addView(named(t, TARGET_ID))
        target = t
        repeat(ROWS_BELOW) { column.addView(row("row ${ROWS_ABOVE + 1 + it}")) }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val sv = object : ScrollView(this) {
            private var downY = 0f
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val handled = super.onTouchEvent(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downY = ev.rawY
                    MotionEvent.ACTION_UP -> {
                        val n = overshootPx
                        if (n > 0 && downY - ev.rawY > slop) {
                            overshootPx = 0
                            post { scrollBy(0, n); overshoots++ }
                        }
                    }
                }
                return handled
            }
        }
        sv.addView(column)
        named(sv, SCROLL_ID)
        scrollView = sv
        setContentView(sv)
    }
}

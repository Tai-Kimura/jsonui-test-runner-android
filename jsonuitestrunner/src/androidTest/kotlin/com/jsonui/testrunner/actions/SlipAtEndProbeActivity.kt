package com.jsonui.testrunner.actions

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A form whose last row is the target, inside a ScrollView that settles back
 * [EXTRA_SLIP] px when a finger lifts at the end of its content
 * (UnstickAtEndOnDeviceTest). That is what a consumer's Compose list did on
 * 2026-10-18 under driver 1.17.2: the target at the end, flush within the
 * clearance, a drag that could not scroll, and the content 4px back once
 * the finger lifted (2054 → 2058). Views do not do this by themselves; the
 * slip is staged here so the rule's answer to it can be read on any device.
 */
class SlipAtEndProbeActivity : Activity() {
    companion object {
        const val SCROLL_ID = "slip_scroll"
        const val TARGET_ID = "slip_target"
        const val EXTRA_SLIP = "slip"
        const val ROWS = 30
        const val ROW_DP = 64
        const val BOTTOM_PADDING_DP = 36
        @Volatile var scrollView: ScrollView? = null
        @Volatile var taps = 0
        @Volatile var slips = 0
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
        taps = 0; slips = 0
        val slip = intent.getIntExtra(EXTRA_SLIP, 0)
        val px = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }
        // Room below the target, as the consumer's form had (its button's
        // bottom 94px above the container's, inside the 129px clearance): a
        // target flush with the edge is clipped there, and a slip would not
        // move the bottom the rule reads.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(BOTTOM_PADDING_DP))
        }
        repeat(ROWS) { i ->
            column.addView(TextView(this).apply {
                text = "row $i"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW_DP))
            })
        }
        column.addView(named(TextView(this).apply {
            text = "+ Add"
            setBackgroundColor(Color.LTGRAY)
            isClickable = true
            setOnClickListener { taps++ }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW_DP))
        }, TARGET_ID))
        val sv = object : ScrollView(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val handled = super.onTouchEvent(ev)
                val atEnd = scrollY >= getChildAt(0).height - (height - paddingTop - paddingBottom)
                if (slip > 0 && ev.actionMasked == MotionEvent.ACTION_UP && atEnd) {
                    post { scrollBy(0, -slip); slips++ }
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

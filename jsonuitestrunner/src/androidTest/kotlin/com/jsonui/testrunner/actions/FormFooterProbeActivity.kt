package com.jsonui.testrunner.actions

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A consumer's form shape (ticket android-driver-scroll-until-visible-stops-
 * at-two-different-offsets-for-the-same-target): a vertical ScrollView
 * (`form_scroll`) above a fixed footer (`save_button`, outside the scroll),
 * its content a column of field rows with `age_field` near the bottom, then
 * an "+ Add Row" button, and last `error_label` — the item a test asserts
 * after Save, which lies below the footer when the scroll stops short.
 * Started explicitly by FormFooterOnDeviceTest; registered in the androidTest
 * manifest, not a launcher target. Views, not Compose, as ScrollProbeActivity.
 */
class FormFooterProbeActivity : Activity() {
    companion object {
        @Volatile var scrollView: ScrollView? = null
        const val SCROLL_ID = "form_scroll"
        const val TARGET_ID = "age_field"
        const val BELOW_ID = "error_label"
        const val FOOTER_ID = "save_button"
        /** Field rows above age_field. */
        const val ROWS_ABOVE = 14
        const val ROW_DP = 64
        const val FOOTER_DP = 56
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
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(text: String, id: String?) = TextView(this).apply {
            this.text = text
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW_DP))
            if (id != null) named(this, id)
        }
        repeat(ROWS_ABOVE) { i -> column.addView(row("field $i", "field_$i")) }
        column.addView(row("Age", TARGET_ID))
        column.addView(named(Button(this).apply {
            text = "+ Add Row"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW_DP))
        }, "add_row_button"))
        column.addView(row("Enter an age between 0 and 100", BELOW_ID))
        val sv = ScrollView(this).apply {
            addView(column)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        named(sv, SCROLL_ID)
        scrollView = sv
        val footer = named(Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(FOOTER_DP))
        }, FOOTER_ID)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sv)
            addView(footer)
        })
    }
}

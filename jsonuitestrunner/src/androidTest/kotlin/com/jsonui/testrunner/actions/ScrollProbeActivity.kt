package com.jsonui.testrunner.actions

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A tall vertical ScrollView for measuring what a gesture DOES to a scroll
 * offset, in-process (UnstickMotionOnDeviceTest). Views, not Compose: the
 * driver has no Compose dependency, and the fling physics under test (a
 * spline decay from the release velocity) is the platform's, shared by
 * OverScroller and Compose's splineBasedDecay. Started explicitly by the
 * test; registered in the androidTest manifest, not a launcher target.
 */
class ScrollProbeActivity : Activity() {
    companion object {
        @Volatile var scrollView: ScrollView? = null
        const val ROWS = 80
        const val ROW_DP = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(ROWS) { i ->
            column.addView(TextView(this).apply {
                text = "row $i"
                contentDescription = "row_$i"
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (ROW_DP * density).toInt())
            })
        }
        val sv = ScrollView(this).apply { addView(column) }
        scrollView = sv
        setContentView(sv)
    }
}

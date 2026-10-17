package com.jsonui.testrunner.actions

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A full-screen surface whose colour flips on every tap, and whose
 * accessibility text names the colour it has just been given
 * (ScreenshotFrameOnDeviceTest). The text changes as soon as the tap is
 * handled; the pixels reach the display a frame or more later. A screenshot
 * that waited only on the text can catch the colour before — the driver's
 * screenshot has to show the colour the text says.
 */
class ScreenshotFrameProbeActivity : Activity() {
    companion object {
        const val PROBE_ID = "screenshot_frame_probe"
        const val EXTRA_CIRCLES = "circles"
        val COLORS = intArrayOf(Color.rgb(220, 30, 30), Color.rgb(30, 30, 220))
        @Volatile var index = 0
        @Volatile var resumed = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = 0
        // Heavy to rasterise (RenderThread), light to record (main thread):
        // the text is current long before the pixels are presented.
        val circles = intent.getIntExtra(EXTRA_CIRCLES, 0)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(1, 255, 255, 255) }
        val surface = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                for (i in 0 until circles) {
                    canvas.drawCircle((i * 37 % width).toFloat(), (i * 53 % height).toFloat(), 120f, paint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    index = 1 - index
                    setBackgroundColor(COLORS[index])
                    contentDescription = "color_$index"
                }
                return true
            }
        }
        surface.setBackgroundColor(COLORS[0])
        surface.contentDescription = "color_0"
        surface.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.viewIdResourceName = PROBE_ID
            }
        }
        surface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setContentView(surface)
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { super.onPause(); resumed = false }
}

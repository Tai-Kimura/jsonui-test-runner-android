package com.jsonui.testrunner.runner

import android.app.Activity
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Launcher-intent target for the on-device launch tests (see the androidTest
 * manifest). Records what a real consumer app would read: how many times it
 * was launched and the JSONUI_TEST_ARGS extra on its intent. Statics are safe
 * as the probe because the activity runs in the same process as the
 * instrumentation — which is also why `pm clear` was fatal here.
 */
class LaunchProbeActivity : Activity() {
    companion object {
        val launches = AtomicInteger(0)
        @Volatile var lastArgs: String? = null

        fun reset() {
            launches.set(0)
            lastArgs = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launches.incrementAndGet()
        lastArgs = intent.getStringExtra("JSONUI_TEST_ARGS")
    }
}

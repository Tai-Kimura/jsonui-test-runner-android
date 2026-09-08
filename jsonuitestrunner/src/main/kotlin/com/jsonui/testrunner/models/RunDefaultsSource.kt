package com.jsonui.testrunner.models

import androidx.test.platform.app.InstrumentationRegistry
import com.jsonui.testrunner.runner.RunDefaults
import com.jsonui.testrunner.runner.RunDefaultsLoader

/**
 * The run-defaults table this process will use, read once.
 *
 * 🚨 UNTIL 1.13.0 NOTHING CALLED `RunDefaultsLoader.load`. Measured on
 * 2026-09-09 in the shipped source of 1.13.0-alpha02:
 *
 *     production call sites of `RunDefaultsLoader.load`   0
 *     assignments to `runDefaults`                        0
 *     positive control: `record` IS assigned              JsonUITest.kt:96/:108
 *     fields `RunnerBuilder` passes to TestRunnerConfig    7 of 17
 *
 * So `config.runDefaults` was always null, `forTier(null, tier)` was always
 * null, and the whole declared-orientation feature was inert: the CLI wrote
 * the sidecar, the build packaged it into the test APK, and no code opened
 * it. A consumer face proved it from the outside — 15+ cases ran portrait
 * across nine minutes with all three tiers declared.
 *
 * ⚠️ THE READ LIVES HERE, NOT IN `RunnerBuilder`. A face that constructs
 * `TestRunnerConfig(...)` directly never touches the builder, and that is
 * what the reporting face does. Fixing only the builder would have left the
 * reporter exactly as broken while looking fixed.
 *
 * 🚨 `getInstrumentation().context`, NOT `.targetContext`. The sidecar is
 * synced into the TEST APK's assets (measured on the reporting face:
 * `app/src/androidTest/assets/tests/jsonui-test-run.json`, and in the merged
 * `devDebugAndroidTest` assets). `targetContext` is the app under test,
 * whose assets do not contain it — so that spelling would read nothing,
 * report ABSENT, and leave the feature just as dead while appearing wired.
 * That failure is silent, which is the shape this whole release is about.
 */
object RunDefaultsSource {

    /** Assets directory the CLI installs the bundle into. */
    const val ASSETS_PATH = "tests"

    @Volatile private var loaded = false
    @Volatile private var table: RunDefaults? = null

    @Synchronized
    fun forThisProcess(): RunDefaults? {
        if (loaded) return table
        loaded = true
        val result = runCatching {
            RunDefaultsLoader.load(
                InstrumentationRegistry.getInstrumentation().context, ASSETS_PATH)
        }.getOrElse {
            // No instrumentation (a plain JVM unit test constructing a
            // runner). Nothing to read and nothing to say: this is not a
            // face's run.
            return null
        }
        table = result.defaults
        if (result.defaults == null && result.reason != null) {
            RunNotices.once(
                "run-defaults-miss",
                "run defaults were not read (${result.miss}): ${result.reason}. " +
                    "Cases that declare no orientation keep the one the run " +
                    "started in."
            )
        }
        return table
    }

    @Synchronized
    fun resetForTest() {
        loaded = false
        table = null
    }
}

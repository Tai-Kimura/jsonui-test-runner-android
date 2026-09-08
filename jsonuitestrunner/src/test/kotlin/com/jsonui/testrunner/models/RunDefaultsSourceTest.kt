package com.jsonui.testrunner.models

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 🚨 The wiring that was missing for the whole life of the feature.
 *
 * Measured in 1.13.0-alpha02's shipped source before writing this:
 *
 *     production call sites of `RunDefaultsLoader.load`   0
 *     assignments to `runDefaults`                        0
 *     positive control: `record` IS assigned              JsonUITest.kt:96/:108
 *
 * ⚠️ These arms run on the JVM, with no instrumentation, so
 * `forThisProcess()` takes its own "no Context" path. That is deliberately
 * asserted rather than worked around: the class must not throw when a plain
 * unit test constructs a runner, and it must not claim a miss it cannot
 * observe. The parsing and tier selection are covered by
 * RunDefaultsLoader's own arms, which need no Context by design.
 */
class RunDefaultsSourceTest {

    @Before fun setUp() { RunDefaultsSource.resetForTest(); RunNotices.resetForTest() }
    @After fun tearDown() { RunDefaultsSource.resetForTest(); RunNotices.resetForTest() }

    @Test
    fun `without instrumentation it returns null and says nothing`() {
        assertNull(RunDefaultsSource.forThisProcess())
        assertTrue(
            "a JVM unit test is not a face's run — a NOTICE here would fire on " +
                "every developer's machine and teach them to ignore the line",
            RunNotices.saidKeys().isEmpty()
        )
    }

    @Test
    fun `it reads at most once per process`() {
        RunDefaultsSource.forThisProcess()
        RunDefaultsSource.forThisProcess()
        RunDefaultsSource.forThisProcess()

        assertTrue(RunNotices.saidKeys().isEmpty())
    }

    @Test
    fun `the assets path is the one the CLI installs into`() {
        // Measured on the reporting face:
        //   app/src/androidTest/assets/tests/jsonui-test-run.json
        //   app/build/intermediates/assets/devDebugAndroidTest/.../tests/...
        assertEquals("tests", RunDefaultsSource.ASSETS_PATH)
    }

    @Test
    fun `it reads the test APK's assets, not the app under test's`() {
        // 🚨 A source arm, and it is the only instrument available on the JVM
        // — but the distinction is worth pinning because getting it wrong is
        // SILENT: `targetContext` reads the app under test, whose assets do
        // not carry the sidecar, so the feature would stay dead while looking
        // wired. The spelling is split so this arm cannot match itself.
        val src = javaClass.classLoader!!
            .getResource("")!!.path
            .substringBefore("/build/")
            .let { java.io.File("$it/src/main/kotlin/com/jsonui/testrunner/models/RunDefaultsSource.kt") }
            .readText()
        val code = src.lines().filterNot {
            val t = it.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")

        assertTrue(
            "the read must use the instrumentation context",
            code.contains("getInstrumentation()." + "context")
        )
        assertTrue(
            "the read must NOT use targetContext — that is the app under test",
            !code.contains("target" + "Context")
        )
    }

    @Test
    fun `the runner consults the sidecar when the config declares nothing`() {
        // 🚨 THE CLAIM THAT WAS FALSE FOR THE FEATURE'S WHOLE LIFE. Only a
        // source read can make it on the JVM (the resolution needs a device),
        // so it is pinned as ONE CONTIGUOUS BLOCK rather than as separate
        // substrings: two `contains` checks both survive a mutation that
        // keeps every spelling and disables the fallback. The guard is a
        // third claim.
        val runner = javaClass.classLoader!!
            .getResource("")!!.path
            .substringBefore("/build/")
            .let {
                java.io.File(
                    "$it/src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt")
            }
            .readText()
        val block = "        val defaults = config.runDefaults ?: RunDefaultsSource" +
            ".forThisProcess()\n" +
            "        val wanted = declared\n" +
            "            ?: RunDefaultsLoader.forTier(defaults, tier)\n"

        assertTrue(
            "the runner no longer falls back to the sidecar when the config " +
                "declares no run defaults — the state this feature shipped in " +
                "from its first version until 1.13.0",
            runner.contains(block)
        )
    }
}

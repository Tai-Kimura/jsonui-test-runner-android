package com.jsonui.testrunner.runner

/**
 * What a read of the app's semantics tree can tell about the build it runs on.
 *
 * The test APK takes Compose from the app's process, and R8 removes from the
 * app whatever the app itself does not call: a member the driver calls throws
 * a [LinkageError]. That says nothing about the screen, and read as "no marker"
 * or "not found" it sends the reader the wrong way. Measured on AGP 9.5.0, Compose BOM
 * 2026.09.00, API 35: `RootForTest.getSemanticsOwner` and
 * `AnnotatedString.getText` gone from a non-debuggable build.
 *
 * The rules that keep them are the `jsonui-test-runner-android-keep` artifact.
 * This driver's own consumer rules cannot do it: they reach the test APK's R8
 * only, never the app's.
 */
object ComposeRead {
    const val KEEP_ARTIFACT = "io.github.tai-kimura:jsonui-test-runner-android-keep"

    /** Thrown where a wrong answer would otherwise pass silently (`hidden`). */
    class ApiGone(message: String) : AssertionError(message)

    @Volatile private var seen: String? = null

    /**
     * Whether the app has Compose. By name: R8 rewrites this string to the app's
     * new name when it processes the test APK against the app's mapping (measured
     * on a build that renames the class).
     */
    val classPresent: Boolean by lazy {
        runCatching { Class.forName("androidx.compose.ui.node.RootForTest", false, ComposeRead::class.java.classLoader) }.isSuccess
    }

    /** The words for the failure. [what] is what threw, or what is missing. */
    private fun message(what: String): String =
        "the Compose API the driver reads is gone from this build ($what): R8 removed it from the app. " +
            "Add `<buildType>Implementation(\"$KEEP_ARTIFACT:<driver version>\")` for the build type the tests run on " +
            "(KotlinJsonUI Docs/ui-tests-on-release-builds.md)."

    fun message(cause: LinkageError): String = message("${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(120)}")

    /** Records it for [note]: a lookup that only misses still has to say why. */
    fun saw(text: String): String {
        seen = text
        return text
    }

    /** What a failure of this run adds, or nothing. */
    fun note(): String = seen?.let { " ($it)" }.orEmpty()

    internal fun forget() {
        seen = null
    }
}

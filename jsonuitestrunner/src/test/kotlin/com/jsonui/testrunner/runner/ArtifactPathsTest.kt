package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ArtifactPathsTest {

    private val root = File("/sdcard/Android/data/com.example/files/jsonui-artifacts")

    @Test
    fun sanitizeReplacesUnsafeCharacters() {
        assertEquals("login_test", ArtifactPaths.sanitize("login test"))
        assertEquals("a_b_c", ArtifactPaths.sanitize("a/b\\c"))
        assertEquals("ok-name_1.2", ArtifactPaths.sanitize("ok-name_1.2"))
        assertEquals("____", ArtifactPaths.sanitize("日本語名"))
    }

    @Test
    fun sanitizeEmptyFallsBackToUnknown() {
        assertEquals("unknown", ArtifactPaths.sanitize(""))
    }

    @Test
    fun caseDirStructuresTestAndCase() {
        val dir = ArtifactPaths.caseDir(root, "registration_form", "submit success")
        assertEquals(File(root, "registration_form/submit_success"), dir)
    }

    @Test
    fun screenshotFileAddsPngExtension() {
        val file = ArtifactPaths.screenshotFile(root, "t", "c", "after login")
        assertEquals(File(root, "t/c/after_login.png"), file)
    }

    @Test
    fun recordingFileIsFixedName() {
        val file = ArtifactPaths.recordingFile(root, "t", "c")
        assertEquals(File(root, "t/c/recording.mp4"), file)
    }

    @Test
    fun mirrorRootIsScopedByPackage() {
        // The defect: one flat mirror root for every app on the device, so a
        // second app's `artifacts pull --clean` pulled and deleted this app's
        // runs. The package segment is what gives the CLI something to scope on.
        assertEquals(
            "/data/local/tmp/jsonui-artifacts/com.example.app",
            ArtifactPaths.mirrorRoot("com.example.app")
        )
        assertEquals(
            "/data/local/tmp/jsonui-artifacts/com.example.app.dev",
            ArtifactPaths.mirrorRoot("com.example.app.dev")
        )
    }

    @Test
    fun mirrorRootForTwoAppsNeverCollides() {
        // Control for the test above: two apps must land in two roots, or the
        // segment is decoration.
        val a = ArtifactPaths.mirrorRoot("com.example.client")
        val b = ArtifactPaths.mirrorRoot("com.example.bar")
        assert(a != b) { "$a == $b" }
        assert(a.startsWith(ArtifactPaths.MIRROR_BASE + "/") && b.startsWith(ArtifactPaths.MIRROR_BASE + "/"))
    }

    @Test
    fun mirrorRootSanitizesAHostilePackageName() {
        // The root is fed to `rm -rf $root/$suite` through the shell, so a
        // package name must not be able to traverse out of the base.
        assertEquals(
            "/data/local/tmp/jsonui-artifacts/.._.._etc",
            ArtifactPaths.mirrorRoot("../../etc")
        )
        assertEquals("/data/local/tmp/jsonui-artifacts/unknown", ArtifactPaths.mirrorRoot(""))
    }

    @Test
    fun pathTraversalNamesCannotEscapeRoot() {
        // '/' is sanitized to '_' so multi-segment names collapse into one segment
        assertEquals(".._.._etc", ArtifactPaths.sanitize("../../etc"))
        // all-dot names would still traverse — mapped to "unknown"
        assertEquals("unknown", ArtifactPaths.sanitize(".."))
        assertEquals("unknown", ArtifactPaths.sanitize("."))
        val dir = ArtifactPaths.caseDir(root, "../../etc", "..")
        assertEquals(File(root, ".._.._etc/unknown"), dir)
    }
}

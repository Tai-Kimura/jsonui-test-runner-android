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

package com.jsonui.testrunner.runner

import java.io.File

/**
 * Pure path logic for on-device test artifacts (JVM unit-testable).
 *
 * Artifacts are structured `<root>/<testName>/<caseName>/<file>` on the device
 * so `jsonui-test artifacts pull` can copy the tree as-is — the test/case
 * organization is decided here, not parsed back out of flat filenames.
 */
object ArtifactPaths {
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /**
     * Replace anything outside [A-Za-z0-9._-] so test/case names can't escape
     * the artifact tree (previously raw case names flowed into filenames).
     * All-dot results ("." / "..") would still traverse — mapped to "unknown".
     */
    fun sanitize(name: String): String {
        val cleaned = name.replace(UNSAFE, "_")
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "unknown"
        return cleaned
    }

    /** `<root>/<testName>/<caseName>` */
    fun caseDir(root: File, testName: String, caseName: String): File =
        File(File(root, sanitize(testName)), sanitize(caseName))

    /** `<root>/<testName>/<caseName>/<name>.png` */
    fun screenshotFile(root: File, testName: String, caseName: String, name: String): File =
        File(caseDir(root, testName, caseName), "${sanitize(name)}.png")

    /** `<root>/<testName>/<caseName>/recording.mp4` */
    fun recordingFile(root: File, testName: String, caseName: String): File =
        File(caseDir(root, testName, caseName), "recording.mp4")
}

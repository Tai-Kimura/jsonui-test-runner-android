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

    /**
     * `<root>/<testName>/<caseName>/hierarchy.xml` — the RAW window dump kept
     * on failure. The parsed set the probe reports is a summary, and a
     * summary cannot be re-asked: a consumer investigation stalled in
     * exactly that way (2026-09-04), holding counts but no dump, so
     * "is this id really a resource-id, and where does the missing subtree
     * start" could not be answered afterwards.
     */
    fun hierarchyDumpFile(root: File, testName: String, caseName: String): File =
        File(caseDir(root, testName, caseName), "hierarchy.xml")

    /** `<root>/<testName>/<caseName>/recording.mp4` */
    fun recordingFile(root: File, testName: String, caseName: String): File =
        File(caseDir(root, testName, caseName), "recording.mp4")

    /**
     * Where this app's artifacts are mirrored so they survive the post-run
     * uninstall: `/data/local/tmp/jsonui-artifacts/<package>`.
     *
     * The package segment is the whole point. The mirror lives OUTSIDE the
     * app-specific dir on purpose (that is what makes it survive uninstall),
     * and until 1.8.9 it was a single flat root shared by every app on the
     * device. On a shared emulator one app's `jsonui-test artifacts pull
     * --clean` then pulled — and deleted — the other app's artifacts, because
     * nothing in the path said whose they were. Consumer report 2026-09-04:
     * 74 directories / 71 MB of one app's runs removed by the other app's
     * clean, with `--serial` unable to help since serial narrows the device,
     * not the app.
     *
     * Sanitized like every other segment so a hostile package name cannot
     * traverse out of the root.
     */
    fun mirrorRoot(packageName: String): String =
        "$MIRROR_BASE/${sanitize(packageName)}"

    /** Flat root that pre-1.8.9 drivers mirrored into, kept for the CLI's legacy read path. */
    const val MIRROR_BASE = "/data/local/tmp/jsonui-artifacts"
}

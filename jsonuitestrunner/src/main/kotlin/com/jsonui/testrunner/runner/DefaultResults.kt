package com.jsonui.testrunner.runner

import android.app.UiAutomation
import com.jsonui.testrunner.models.RunNotices
import com.jsonui.testrunner.models.TestSuiteResult
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The default results location: when `resultsPath` is NOT set, every run
 * still leaves its results JSON on the device, one new file per run, so
 * `jui viz` can read past runs (it pulls the folder).
 *
 * Device side, fixed, no env: `/data/local/tmp/jsonui-test-results/<package>/`.
 * ⚠️ `JSONUI_TEST_RESULTS_DIR` has NO effect here — it is read on the host
 * side only (iOS simulator, web, viz). An instrumentation process cannot see
 * the host's environment, and this folder is fixed so viz always knows where
 * to pull from.
 *
 * This only ADDS a write. Pass/fail, waits, predicates, retries and progress
 * records are untouched, and when `resultsPath` IS set nothing here runs —
 * that file keeps its old shape byte for byte (no `run` / `app` / `file`:
 * an older `jsonui-test report` rejects unknown keys).
 *
 * Pure parts ([fileName], [NAME_REGEX], [toPrune], [json]) are JVM-tested;
 * the device I/O sits behind [DefaultResultsStore] so a test can make it fail.
 */
internal object DefaultResults {

    /** Device-side base; the app segment is [ArtifactPaths.sanitize]d package name. */
    const val DEVICE_BASE = "/data/local/tmp/jsonui-test-results"

    /** A result a newer one of the same test file replaced goes after this (by the time in its name). */
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Replaced results go, oldest first, until at most this many files remain per app folder. */
    const val MAX_FILES = 500

    /**
     * The ONLY names pruning may delete. A dot-prefixed tmp, a file someone
     * else put there, a sub-folder: none match, so none are touched. Group 1
     * is the file key; a name of the earlier shape (1.19.0, no key) still
     * matches, without it.
     */
    val NAME_REGEX = Regex(
        """^\d{8}T\d{6}\.\d{3}Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:-([0-9a-f]{8}))?\.json$"""
    )

    /**
     * The run's file key in a name: the first 8 hex digits of SHA-256 over the
     * UTF-8 of its test files (distinct, sorted, joined with a newline). With
     * it, pruning tells one test file's results from another's by name alone:
     * the device folder is listed through the shell, and reading 500 files
     * there would be slow. jsonui-test-runner specs/vectors/results-prune.json.
     */
    fun fileKey(files: List<String>): String {
        val key = files.distinct().sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    // SimpleDateFormat, not java.time: minSdk is 24 and java.time is 26+.
    private fun utc(pattern: String) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private const val NAME_TIME = "yyyyMMdd'T'HHmmss.SSS'Z'"

    /** `<UTC yyyyMMdd'T'HHmmss.SSS'Z'>-<run>-<fileKey8>.json` — name order == time order. */
    fun fileName(epochMs: Long, run: String, fileKey8: String): String =
        "${utc(NAME_TIME).format(Date(epochMs))}-$run-$fileKey8.json"

    /** The file's `generatedAt` — the same instant its name carries. */
    fun generatedAt(epochMs: Long): String =
        utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date(epochMs))

    /** Epoch ms in a matching name, or null when the name does not match. */
    fun timeOf(name: String): Long? {
        if (!NAME_REGEX.matches(name)) return null
        return runCatching { utc(NAME_TIME).apply { isLenient = false }.parse(name.substring(0, 20))?.time }
            .getOrNull()
    }

    /** `<base>/<sanitized package>` */
    fun deviceFolder(packageName: String): String =
        "$DEVICE_BASE/${ArtifactPaths.sanitize(packageName)}"

    /**
     * Which of [names] to delete, in one app folder, in name order. The newest
     * name of each file key is always kept: a test file not rerun keeps its
     * latest result however old, and however many other files ran since. The
     * rest — older names of the same key, and names of the earlier shape — go
     * when older than [maxAgeMs], then oldest first until at most [maxFiles]
     * names remain (a folder of more test files than that keeps them all).
     * Anything that does not match [NAME_REGEX] is never chosen.
     */
    fun toPrune(names: List<String>, nowMs: Long, maxFiles: Int = MAX_FILES, maxAgeMs: Long = MAX_AGE_MS): List<String> {
        val matching = names.filter { NAME_REGEX.matches(it) }.distinct().sorted()
        val kept = matching.groupBy { NAME_REGEX.matchEntire(it)!!.groupValues[1] }
            .filterKeys { it.isNotEmpty() }
            .values.map { it.last() }.toSet()
        val replaced = matching.filter { it !in kept }
        val doomed = replaced.filter { (timeOf(it) ?: return@filter false) < nowMs - maxAgeMs }.toMutableSet()
        var remaining = matching.size - doomed.size
        for (name in replaced) {
            if (remaining <= maxFiles) break
            if (doomed.add(name)) remaining -= 1
        }
        return matching.filter { it in doomed }
    }

    /** The results JSON for one run, plus the three keys only default-location files carry. */
    fun json(suite: TestSuiteResult, platform: String, epochMs: Long, run: String, app: String, file: String): String =
        ResultsWriter.toJson(
            listOf(suite), platform, generatedAt(epochMs),
            ResultsWriter.DefaultLocation(run = run, app = app, file = file)
        ).toString()

    /**
     * Write [suite] to the default location when [resultsPath] is null, then
     * prune. Nothing it does can fail the test: every failure is swallowed
     * and said ONCE per process per kind through [notice] (never `log()`,
     * which is behind `verbose`). Returns true when the file was written.
     */
    fun writeIfUnset(
        resultsPath: File?,
        suite: TestSuiteResult,
        platform: String,
        nowMs: Long,
        run: String,
        app: String,
        file: String,
        store: () -> DefaultResultsStore?,
        notice: (String, String) -> Unit = RunNotices::once
    ): Boolean {
        if (resultsPath != null) return false
        val target = guard(notice) { store() } ?: return false
        val name = fileName(nowMs, run, fileKey(listOf(file)))
        val written = guard(notice) {
            target.write(name, json(suite, platform, nowMs, run, app, file))
            true
        } ?: false
        if (!written) return false
        guard(notice) {
            for (old in toPrune(target.list(), nowMs)) {
                // One rm per file; one that fails does not stop the others.
                guard(notice) { target.delete(old) }
            }
        }
        return true
    }

    private inline fun <T> guard(noinline notice: (String, String) -> Unit, block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            // Same line the progress sink draws: never swallow OOM / StackOverflow.
            if (t is VirtualMachineError) throw t
            val kind = (t as? DefaultResultsFailure)?.kind ?: "error"
            runCatching {
                notice(
                    "default-results-$kind",
                    "the default results file could not be written ($kind: ${t.message}). " +
                        "The test result is unaffected; set TestRunnerConfig.resultsPath " +
                        "to write results somewhere you choose."
                )
            }
            null
        }
}

/** A default-results write that failed in a way worth naming ([kind] keys its notice). */
internal class DefaultResultsFailure(val kind: String, message: String) : Exception(message)

/**
 * Where default result files go: one app folder. The port a test replaces.
 * [write] must be atomic (a reader never sees half a file) and throws on
 * failure; [list] returns plain names in the folder (no recursion).
 */
internal interface DefaultResultsStore {
    val location: String
    fun write(name: String, text: String)
    fun list(): List<String>
    fun delete(name: String)
}

/**
 * The device store: the artifacts mirror's form (measured on API 35).
 * The instrumentation cannot write /data/local/tmp itself, the shell uid can,
 * and the shell uid can read the app's EXTERNAL files dir (not filesDir). So:
 *   1. write `<external>/jsonui-test-results/.<name>.tmp`, rename to `<name>`;
 *   2. `mkdir -p <dst>`, `cp <src> <dst>/.<name>.tmp`, `mv` to `<dst>/<name>`;
 *   3. delete the own-area file.
 * executeShellCommand does not go through a shell: no redirection, no
 * globbing, no `&&` — one plain command per call, then `ls` to check.
 */
internal class DeviceResultsStore(
    private val ownArea: File?,
    private val automation: UiAutomation,
    override val location: String
) : DefaultResultsStore {

    override fun write(name: String, text: String) {
        val base = ownArea ?: throw DefaultResultsFailure(
            "own-area", "getExternalFilesDir is null (filesDir is not readable by the shell uid)"
        )
        val dir = File(base, "jsonui-test-results")
        dir.mkdirs()
        val tmp = File(dir, ".$name.tmp")
        val src = File(dir, name)
        try {
            try {
                tmp.writeText(text)
                if (!tmp.renameTo(src)) throw DefaultResultsFailure("own-area", "rename in ${dir.absolutePath} failed")
            } catch (e: DefaultResultsFailure) {
                throw e
            } catch (e: Exception) {
                throw DefaultResultsFailure("own-area", "${dir.absolutePath}: ${e.message}")
            }
            Shell.exec(automation, "mkdir -p $location")
            if (Shell.exec(automation, "ls -d $location").trim() != location) {
                throw DefaultResultsFailure("device-folder", "$location could not be created")
            }
            Shell.exec(automation, "cp ${src.absolutePath} $location/.$name.tmp")
            Shell.exec(automation, "mv $location/.$name.tmp $location/$name")
            if (name !in list()) {
                runCatching { Shell.exec(automation, "rm -f $location/.$name.tmp") }
                throw DefaultResultsFailure("device-copy", "copy to $location/$name failed")
            }
        } finally {
            tmp.delete()
            src.delete()
        }
    }

    override fun list(): List<String> =
        Shell.exec(automation, "ls $location").lines().map { it.trim() }.filter { it.isNotEmpty() }

    override fun delete(name: String) {
        Shell.exec(automation, "rm $location/$name")
    }
}

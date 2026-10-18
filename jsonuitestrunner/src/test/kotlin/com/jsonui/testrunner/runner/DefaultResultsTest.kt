package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestResult
import com.jsonui.testrunner.models.TestSuiteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * resultsPath unset → the run's results still land in the default folder, one
 * new file per run, pruned by name only. The device store is replaced by a
 * folder store over a temp dir (never the real device, never HOME).
 */
class DefaultResultsTest {

    /** Host-side stand-in for the device folder: tmp + rename, plain list. */
    private class FolderStore(val dir: File) : DefaultResultsStore {
        override val location: String get() = dir.absolutePath
        override fun write(name: String, text: String) {
            dir.mkdirs()
            val tmp = File(dir, ".$name.tmp")
            tmp.writeText(text)
            check(tmp.renameTo(File(dir, name)))
        }
        override fun list(): List<String> = dir.list()?.toList() ?: emptyList()
        override fun delete(name: String) { File(dir, name).delete() }
    }

    private class FailingStore(val kind: String) : DefaultResultsStore {
        var writes = 0
        override val location = "/nowhere"
        override fun write(name: String, text: String) {
            writes++
            throw DefaultResultsFailure(kind, "forced")
        }
        override fun list(): List<String> = throw IllegalStateException("unreachable")
        override fun delete(name: String) = throw IllegalStateException("unreachable")
    }

    private lateinit var base: File
    private lateinit var appDir: File
    private val notices = mutableListOf<Pair<String, String>>()
    private val run1 = "3f2a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b"
    private val run2 = "9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4"
    private val now = 1_792_000_000_000L // 2026-10-14T17:46:40Z

    private val suite = TestSuiteResult(
        "LoginScreen",
        listOf(
            TestResult("LoginScreen", "ok", passed = true, durationMs = 5),
            TestResult("LoginScreen", "bad", passed = false, error = "x", durationMs = 7)
        ),
        12L
    )

    /** Every file the helper write() makes is a result of this test file. */
    private val login = DefaultResults.fileKey(listOf("screens/login.test.json"))

    @Before fun setUp() {
        base = Files.createTempDirectory("default-results").toFile()
        appDir = File(base, "com.example.app")
        notices.clear()
    }

    @After fun tearDown() { base.deleteRecursively() }

    private fun write(
        run: String = run1,
        at: Long = now,
        resultsPath: File? = null,
        store: DefaultResultsStore = FolderStore(appDir)
    ) = DefaultResults.writeIfUnset(
        resultsPath = resultsPath, suite = suite, platform = "android", nowMs = at,
        run = run, app = "com.example.app", file = "screens/login.test.json",
        store = { store }, notice = { k, m -> notices.add(k to m) }
    )

    private fun read(f: File): JsonObject = Json.parseToJsonElement(f.readText()).jsonObject

    // 1. resultsPath unset → a file in the default folder, with run/app/file.
    @Test
    fun `resultsPath unset writes one file with run, app and file`() {
        assertTrue(write())
        val files = appDir.listFiles()!!.toList()
        assertEquals(1, files.size)
        val f = files.single()
        assertEquals(DefaultResults.fileName(now, run1, login), f.name)
        assertTrue(DefaultResults.NAME_REGEX.matches(f.name))
        val json = read(f)
        assertEquals(
            listOf("format", "version", "platform", "generatedAt", "run", "app", "suites"),
            json.keys.toList()
        )
        assertEquals("jsonui-test-results", json["format"]!!.jsonPrimitive.content)
        assertEquals(run1, json["run"]!!.jsonPrimitive.content)
        assertEquals("com.example.app", json["app"]!!.jsonPrimitive.content)
        assertEquals(DefaultResults.generatedAt(now), json["generatedAt"]!!.jsonPrimitive.content)
        val s = json["suites"]!!.jsonArray.single().jsonObject
        assertEquals("screens/login.test.json", s["file"]!!.jsonPrimitive.content)
        assertEquals("LoginScreen", s["suiteName"]!!.jsonPrimitive.content)
        assertEquals(listOf("passed", "failed"),
            s["results"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content })
        assertTrue(notices.isEmpty())
    }

    // 2. two runs → two files.
    @Test
    fun `two runs leave two files, the first is not overwritten`() {
        write(run = run1, at = now)
        val first = appDir.listFiles()!!.single().readText()
        write(run = run2, at = now + 1)
        val names = appDir.list()!!.sorted()
        assertEquals(2, names.size)
        assertEquals(first, File(appDir, DefaultResults.fileName(now, run1, login)).readText())
        assertEquals(run2, read(File(appDir, names[1]))["run"]!!.jsonPrimitive.content)
    }

    @Test
    fun `two runs in the same millisecond still leave two files`() {
        write(run = run1, at = now)
        write(run = run2, at = now)
        assertEquals(2, appDir.list()!!.size)
    }

    // 3. pruning.
    @Test
    fun `one test file rerun - pruning keeps the newest 500, drops the 15-day-old one, touches nothing else`() {
        appDir.mkdirs()
        val sibling = File(base, "com.other.app").apply { mkdirs() }
        val siblingFile = File(sibling, DefaultResults.fileName(now - 30L * 86_400_000, run2, login)).apply { writeText("{}") }
        val foreign = File(appDir, "notes.json").apply { writeText("{}") }
        val tmpLeft = File(appDir, ".${DefaultResults.fileName(now - 20L * 86_400_000, run2, login)}.tmp").apply { writeText("{}") }
        val old = File(appDir, DefaultResults.fileName(now - 15L * 86_400_000, run2, login)).apply { writeText("{}") }
        // 505 recent ones, one per second ending 10 s before the write.
        val recent = (0 until 505).map { i ->
            val id = "%08x-0000-4000-8000-%012x".format(i, i)
            File(appDir, DefaultResults.fileName(now - 10_000 - (505 - i) * 1000L, id, login)).apply { writeText("{}") }
        }
        write()
        val left = appDir.list()!!.toSet()
        assertFalse("15-day-old file survived", old.name in left)
        assertTrue("non-matching file was deleted", foreign.exists())
        assertTrue("dot tmp was deleted", tmpLeft.exists())
        assertTrue("sibling app folder was touched", siblingFile.exists())
        val matching = left.filter { DefaultResults.NAME_REGEX.matches(it) }.sorted()
        assertEquals(500, matching.size)
        assertEquals(DefaultResults.fileName(now, run1, login), matching.last())
        // newest 499 of the pre-existing kept, the 6 oldest gone
        assertEquals(recent.takeLast(499).map { it.name }, matching.dropLast(1))
    }

    @Test
    fun `toPrune never chooses a name outside the regex`() {
        val names = listOf("a.json", "20260101T000000.000Z-x.json", ".20200101T000000.000Z-$run1.json.tmp",
            "20200101T000000.000Z-$run1.json")
        assertEquals(listOf("20200101T000000.000Z-$run1.json"), DefaultResults.toPrune(names, now))
    }

    @Test
    fun `a replaced file under 14 days old is kept, the boundary is 14 days`() {
        val keep = DefaultResults.fileName(now - 14L * 86_400_000 + 1, run1, login)
        val drop = DefaultResults.fileName(now - 14L * 86_400_000 - 1, run2, login)
        val latest = DefaultResults.fileName(now, "%08x-0000-4000-8000-%012x".format(9, 9), login)
        assertEquals(listOf(drop), DefaultResults.toPrune(listOf(keep, drop, latest), now))
    }

    // 4. resultsPath set → old behaviour.
    @Test
    fun `resultsPath set writes no default file`() {
        assertFalse(write(resultsPath = File(base, "results.json")))
        assertFalse(appDir.exists())
    }

    @Test
    fun `a resultsPath file carries no run, app or file key`() {
        val out = File(base, "results.json")
        ResultsWriter.write(listOf(suite), out, "android", "2026-10-18T00:00:00Z")
        val json = read(out)
        assertEquals(listOf("format", "version", "platform", "generatedAt", "suites"), json.keys.toList())
        assertEquals(listOf("suiteName", "totalDurationMs", "results"),
            json["suites"]!!.jsonArray.single().jsonObject.keys.toList())
    }

    // 5. name + sanitize.
    @Test
    fun `name format and regex round-trip`() {
        val name = DefaultResults.fileName(1_792_347_420_123L, run1, "abcd1234")
        assertEquals("20261018T181700.123Z-$run1-abcd1234.json", name)
        assertEquals("abcd1234", DefaultResults.NAME_REGEX.matchEntire(name)!!.groupValues[1])
        // the earlier shape (1.19.0) still matches, without a key
        assertEquals("", DefaultResults.NAME_REGEX.matchEntire("20261018T181700.123Z-$run1.json")!!.groupValues[1])
        assertTrue(DefaultResults.NAME_REGEX.matches(name))
        assertEquals(1_792_347_420_123L, DefaultResults.timeOf(name))
        assertEquals("2026-10-18T18:17:00.123Z", DefaultResults.generatedAt(1_792_347_420_123L))
        assertFalse(DefaultResults.NAME_REGEX.matches(".$name.tmp"))
        assertFalse(DefaultResults.NAME_REGEX.matches(name.uppercase()))
        assertNull(DefaultResults.timeOf("notes.json"))
    }

    @Test
    fun `the app segment is sanitized like ArtifactPaths`() {
        assertEquals("/data/local/tmp/jsonui-test-results/com.example.app",
            DefaultResults.deviceFolder("com.example.app"))
        assertEquals("/data/local/tmp/jsonui-test-results/a_b_c", DefaultResults.deviceFolder("a/b c"))
        assertEquals("/data/local/tmp/jsonui-test-results/unknown", DefaultResults.deviceFolder(".."))
        assertEquals("/data/local/tmp/jsonui-test-results/unknown", DefaultResults.deviceFolder(""))
    }

    // Failure.
    @Test
    fun `a failing store changes nothing for the test and says so once per kind`() {
        val store = FailingStore("device-copy")
        // Must return normally — no exception reaches the runner.
        assertFalse(write(store = store))
        assertFalse(write(run = run2, store = store))
        assertEquals(2, store.writes)
        assertEquals(listOf("default-results-device-copy", "default-results-device-copy"), notices.map { it.first })
        assertTrue(notices.first().second.contains("unaffected"))
    }

    @Test
    fun `a store that cannot even be made is swallowed too`() {
        val ok = DefaultResults.writeIfUnset(
            null, suite, "android", now, run1, "app", "inline",
            store = { throw IllegalStateException("no instrumentation") },
            notice = { k, m -> notices.add(k to m) }
        )
        assertFalse(ok)
        assertEquals(listOf("default-results-error"), notices.map { it.first })
    }

    @Test
    fun `a null store writes nothing and says nothing`() {
        assertFalse(DefaultResults.writeIfUnset(null, suite, "android", now, run1, "app", "inline",
            store = { null }, notice = { k, m -> notices.add(k to m) }))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `the suite object is returned untouched by a failing write`() {
        // The runner's `.also { writeDefaultResults(it) }`: whatever the write
        // does, the value the caller gets is the suite the run produced.
        val returned = suite.also { write(store = FailingStore("own-area")) }
        assertSame(suite, returned)
    }

    @Test
    fun `the runner writes after both run kinds, outside the progress run`() {
        val src = File("src/main/kotlin/com/jsonui/testrunner/runner/JsonUITestRunner.kt").readText()
        for (rows in listOf("runScreenTestRows(test)", "runFlowTestRows(test)")) {
            assertTrue("$rows is no longer followed by the default write",
                src.contains("            $rows\n        }.also { writeDefaultResults(it) }"))
        }
    }
}

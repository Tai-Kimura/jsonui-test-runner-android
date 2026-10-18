package com.jsonui.testrunner.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The latest result of every test file stays (2.3.1; measured by 35: rerunning one file 500 times left 1 of 122
 * files with its latest). src/test/resources/vectors/results-prune.json is a byte copy of jsonui-test-runner
 * specs/vectors/results-prune.json, the rule the three drivers and jui viz share.
 */
class ResultsPruneVectorTest {

    private val vector: JsonObject =
        Json.parseToJsonElement(File("src/test/resources/vectors/results-prune.json").readText()).jsonObject
    private val now = 1_792_314_000_000L
    private val day = 86_400_000L
    private fun id(i: Int) = "%08x-0000-4000-8000-%012x".format(i, i)
    private fun file(i: Int) = "tests/screens/s$i.test.json"
    private fun at(ms: Long, i: Int, f: String) = DefaultResults.fileName(ms, id(i), DefaultResults.fileKey(listOf(f)))
    private fun latestPerFile(names: Collection<String>) =
        names.map { DefaultResults.NAME_REGEX.matchEntire(it)!!.groupValues[1] }.toSet().size

    @Test
    fun `the shared vector - file keys and every prune case`() {
        for (k in vector["fileKeys"]!!.jsonArray.map { it.jsonObject }) {
            val key = k["key"]!!.jsonPrimitive.content
            assertEquals(key, k["fileKey8"]!!.jsonPrimitive.content, DefaultResults.fileKey(key.split("\n")))
        }
        val cases = vector["prune"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, cases.size)
        for (c in cases) {
            val names = c["names"]!!.jsonArray.map { it.jsonPrimitive.content }
            val want = c["pruned"]!!.jsonArray.map { it.jsonPrimitive.content }
            val got = DefaultResults.toPrune(names, c["now"]!!.jsonPrimitive.long, c["maxFiles"]!!.jsonPrimitive.int,
                c["maxAgeDays"]!!.jsonPrimitive.int * day)
            assertEquals(c["name"]!!.jsonPrimitive.content, want, got)
        }
    }

    @Test
    fun `122 files, then one of them rerun 500 times - the other 121 keep their latest`() {
        val names = (0 until 122).map { at(now - day + it, it, file(it)) } +
            (0 until 500).map { at(now - day / 2 + it * 1000L, 1000 + it, file(0)) }
        val left = names - DefaultResults.toPrune(names, now).toSet()
        assertEquals(DefaultResults.MAX_FILES, left.size)
        assertEquals(122, latestPerFile(left))
        assertTrue(names.last() in left)
    }

    @Test
    fun `one round of 600 files - none of them is pruned`() {
        val names = (0 until 600).map { at(now - 60_000 + it, it, file(it)) }
        assertEquals(emptyList<String>(), DefaultResults.toPrune(names, now))
    }

    @Test
    fun `a file not run for 20 days keeps its result, its replaced older one goes`() {
        val only = at(now - 20 * day, 1, file(1))
        val replaced = at(now - 20 * day, 2, file(2))
        val latest = at(now - 1000, 3, file(2))
        assertEquals(listOf(replaced), DefaultResults.toPrune(listOf(only, replaced, latest), now))
    }

    /**
     * The copy is compared with its source when the source is at hand: $JSONUI_TEST_RUNNER_DIR, or this driver as
     * jsonui-test-runner's submodule (drivers/android). This repo's CI checks out the driver alone, so there it is
     * skipped — and says so.
     */
    @Test
    fun `the vector is a byte copy of jsonui-test-runner's`() {
        val source = listOfNotNull(
            System.getenv("JSONUI_TEST_RUNNER_DIR")?.let { File(it, "specs/vectors/results-prune.json") },
            File("../../../specs/vectors/results-prune.json")
        ).firstOrNull { it.isFile }
        assumeTrue("jsonui-test-runner specs/vectors not found: the copy is not compared here", source != null)
        assertArrayEquals(source!!.readBytes(), File("src/test/resources/vectors/results-prune.json").readBytes())
    }
}

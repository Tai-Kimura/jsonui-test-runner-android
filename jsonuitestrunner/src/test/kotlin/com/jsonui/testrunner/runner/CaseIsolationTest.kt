package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.CaseIsolation
import com.jsonui.testrunner.models.TestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * caseIsolation (screen-test.schema.json): absent / "shared" keeps the
 * once-per-file setup and teardown; "reopen" runs reopen → setup → case →
 * teardown for every case. The runner needs instrumentation, so the sequence
 * is pinned on [ReopenedCases], the pure loop the runner delegates to.
 */
class CaseIsolationTest {

    private fun screen(extra: String = "") = """
        {
          "type": "screen",
          "source": { "layout": "layouts/x.json" },
          "metadata": { "name": "Iso" },
          $extra
          "cases": [
            { "name": "one", "steps": [{ "action": "wait", "ms": 1 }] },
            { "name": "two", "steps": [{ "action": "wait", "ms": 1 }] },
            { "name": "three", "skip": true, "steps": [{ "action": "wait", "ms": 1 }] },
            { "name": "four", "steps": [{ "action": "wait", "ms": 1 }] }
          ]
        }
    """.trimIndent()

    private fun load(extra: String = "") =
        (TestLoader().loadFromString(screen(extra)) as LoadedTest.Screen).test

    // MARK: decoding

    @Test
    fun `absent means shared`() {
        assertNull(load().caseIsolation)
    }

    @Test
    fun `decodes both values`() {
        assertEquals(CaseIsolation.SHARED, load("\"caseIsolation\": \"shared\",").caseIsolation)
        assertEquals(CaseIsolation.REOPEN, load("\"caseIsolation\": \"reopen\",").caseIsolation)
    }

    @Test
    fun `a misspelt value fails the load instead of running as shared`() {
        try {
            load("\"caseIsolation\": \"reOpen\",")
            fail("expected the load to fail")
        } catch (e: Exception) {
            // any load failure; the point is that it is not silently 'shared'
        }
    }

    // MARK: reopen sequence

    private data class Row(
        val name: String,
        val passed: Boolean,
        val attempts: Int? = null,
        val skipped: Boolean = false,
        val error: String? = null
    )

    private class Recorder(
        val failSetupOn: Set<Int> = emptySet(),
        val failBody: (Int) -> Boolean = { false },
        val failTeardownOn: Set<Int> = emptySet()
    ) {
        val seq = mutableListOf<String>()
        private var setups = 0
        private var bodies = 0
        private var teardowns = 0

        fun run(cases: List<TestCase>, retries: Int = 0): List<Row> = ReopenedCases.run(
            cases = cases,
            retries = retries,
            hooks = ReopenedCases.Hooks(
                gate = { if (it.skip == true) Row(it.name, true, skipped = true) else null },
                reopen = { seq.add("reopen") },
                setup = {
                    setups++
                    seq.add("setup")
                    if (setups in failSetupOn) throw IllegalStateException("setup boom $setups")
                },
                body = {
                    bodies++
                    seq.add("case:${it.name}")
                    Row(it.name, !failBody(bodies))
                },
                teardown = {
                    teardowns++
                    seq.add("teardown")
                    if (teardowns in failTeardownOn) throw IllegalStateException("teardown boom")
                },
                setupFailed = { c, e -> Row(c.name, false, error = e.message) },
                teardownFailed = { _, e -> Row("teardown", false, error = e.message) },
                isPass = { it.passed },
                stampAttempts = { r, n -> r.copy(attempts = n) }
            )
        )
    }

    @Test
    fun `reopen runs reopen, setup, case, teardown once per case and nothing for a gated case`() {
        val rec = Recorder()
        val rows = rec.run(load().cases)
        assertEquals(
            listOf(
                "reopen", "setup", "case:one", "teardown",
                "reopen", "setup", "case:two", "teardown",
                "reopen", "setup", "case:four", "teardown"
            ),
            rec.seq
        )
        assertEquals(listOf("one", "two", "three", "four"), rows.map { it.name })
        assertTrue(rows[2].skipped)
        assertEquals(listOf(1, 1, 1), rows.filter { !it.skipped }.map { it.attempts })
    }

    @Test
    fun `a setup failure fails that case only and its teardown still runs`() {
        val rec = Recorder(failSetupOn = setOf(2))
        val rows = rec.run(load().cases)
        assertEquals(
            listOf(
                "reopen", "setup", "case:one", "teardown",
                "reopen", "setup", "teardown",
                "reopen", "setup", "case:four", "teardown"
            ),
            rec.seq
        )
        assertEquals(listOf(true, false, true, true), rows.map { it.passed })
        assertEquals("setup boom 2", rows[1].error)
    }

    @Test
    fun `each retry attempt reopens`() {
        val rec = Recorder(failBody = { it == 1 })
        val rows = rec.run(load().cases.take(1), retries = 1)
        assertEquals(
            listOf("reopen", "setup", "case:one", "teardown", "reopen", "setup", "case:one", "teardown"),
            rec.seq
        )
        assertEquals(listOf(Row("one", true, attempts = 2)), rows)
    }

    @Test
    fun `a teardown failure is an extra row after its case`() {
        val rec = Recorder(failTeardownOn = setOf(1))
        val rows = rec.run(load().cases.take(2))
        assertEquals(listOf("one", "teardown", "two"), rows.map { it.name })
        assertEquals("teardown boom", rows[1].error)
    }

    @Test
    fun `a VirtualMachineError is rethrown, never recorded as a setup row`() {
        val thrown = try {
            ReopenedCases.run(
                cases = load().cases.take(1),
                retries = 0,
                hooks = ReopenedCases.Hooks<Row>(
                    gate = { null },
                    reopen = { throw OutOfMemoryError("oom") },
                    setup = {},
                    body = { Row(it.name, true) },
                    teardown = {},
                    setupFailed = { c, _ -> Row(c.name, false) },
                    teardownFailed = { _, _ -> Row("teardown", false) },
                    isPass = { it.passed },
                    stampAttempts = { r, _ -> r },
                    rethrowIfFatal = { if (it is VirtualMachineError) throw it }
                )
            )
            null
        } catch (e: OutOfMemoryError) {
            e
        }
        assertEquals("oom", thrown?.message)
    }
}

package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A look after a miss reads fresh nodes (ticket test-android-stale-retry-
 * rereads-the-same-cached-node). The fake cache below is UiAutomation's as
 * the consumer's run had it: it holds the page from before a swipe, nothing
 * the app sends invalidates it, and only a clear lets a read see the screen.
 */
class NodeCacheTest {

    private class FakeCache(private val live: () -> String) {
        private var held: String? = null
        var clears = 0
        val log = mutableListOf<String>()
        fun read(): String = (held ?: live().also { held = it }).also { log += "read:$it" }
        fun clear() { held = null; clears++; log += "clear" }
    }

    private var mono = 0L
    private fun deadline(budgetMs: Long) = Deadline(budgetMs, { mono }, { 0L })

    /** The consumer's shape: the first read caches No.2, then the swipe lands. */
    @Test
    fun aRetryClearsBeforeItLooksAgain() {
        var screen = "No.2"
        val cache = FakeCache { screen }
        cache.read()           // the read before the swipe fills the cache
        screen = "No.3"        // the swipe; Compose sends no event
        val found = deadline(8_000).poll(sleep = { mono += it }, beforeRetry = cache::clear) {
            cache.read().takeIf { it == "No.3" }
        }
        assertEquals("No.3", found)
        assertEquals(listOf("read:No.2", "read:No.2", "clear", "read:No.3"), cache.log)
    }

    /** The mutation: no clear between looks. Every look reads the cached page until the budget runs out. */
    @Test
    fun withoutTheClearTheRetryRereadsTheSameNode() {
        var screen = "No.2"
        val cache = FakeCache { screen }
        cache.read()
        screen = "No.3"
        val found = deadline(8_000).poll(sleep = { mono += it }) { cache.read().takeIf { it == "No.3" } }
        assertNull(found)
        assertEquals(0, cache.clears)
        assertTrue(cache.log.all { it == "read:No.2" })
    }

    /** A first look that answers costs no clear: the clear is between looks, not before the first. */
    @Test
    fun aHitOnTheFirstLookClearsNothing() {
        val cache = FakeCache { "No.3" }
        assertEquals("No.3", deadline(1_000).poll(sleep = { mono += it }, beforeRetry = cache::clear) { cache.read() })
        assertEquals(0, cache.clears)
    }

    @Test
    fun aClearThatCannotRunSaysSoAndDoesNotThrow() {
        assertFalse(NodeCache.clear { throw IllegalStateException("no clearCache below API 34") })
        assertFalse(NodeCache.clear { false })
        assertTrue(NodeCache.clear { true })
    }
}

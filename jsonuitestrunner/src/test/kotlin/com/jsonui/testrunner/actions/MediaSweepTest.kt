package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SQL and shell text of addMedia's orphaned-row sweep. */
class MediaSweepTest {

    @Test
    fun `the clause names the exact spelling, the respelled copies, and only orphaned or own rows`() {
        val w = MediaSweep.whereClause("item_front_fixture.png", "com.example.app")
        assertTrue(w.contains("_display_name = 'item_front_fixture.png'"))
        assertTrue(w.contains("_display_name LIKE 'item_front_fixture (%).png'"))
        assertTrue(w.contains("owner_package_name IS NULL"))
        assertTrue(w.contains("owner_package_name = 'com.example.app'"))
        // a live row of another package is not matched: no bare OR at the top level
        assertTrue(w.startsWith("(") && w.contains(") AND ("))
    }

    @Test
    fun `a quote in a name is doubled for SQL and the clause survives the shell`() {
        val w = MediaSweep.whereClause("it's.png", "pkg")
        assertTrue(w.contains("'it''s.png'"))
        val s = MediaSweep.script("content://media/external/images/media", w)
        assertFalse("the script must not contain an unescaped double quote inside the where word",
            s.lines().any { it.contains("--where \"") && it.count { c -> c == '"' } != 2 })
    }

    @Test
    fun `the script counts before and after and prints the difference`() {
        val s = MediaSweep.script("content://media/external/images/media", "x = 1")
        assertEquals(listOf("b=", "content delete", "a=", "echo \"swept="), s.lines().filter { it.isNotBlank() }.map { l ->
            when {
                l.startsWith("b=") -> "b="
                l.startsWith("a=") -> "a="
                l.startsWith("content delete") -> "content delete"
                else -> l.substring(0, 12)
            }
        })
    }

    @Test
    fun `the count is read from the script output and never negative`() {
        assertEquals(3, MediaSweep.sweptCount("Row: 0\nswept=3\n"))
        assertEquals(0, MediaSweep.sweptCount("swept=-1"))
        assertEquals(0, MediaSweep.sweptCount("garbage"))
    }

    @Test
    fun `dollar and backtick in a name are escaped for the shell word`() {
        assertEquals("a\\\$b\\`c\\\"d", MediaSweep.shell("a\$b`c\"d"))
    }
}

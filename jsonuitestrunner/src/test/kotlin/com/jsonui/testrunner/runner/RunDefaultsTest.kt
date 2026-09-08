package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sidecar keeps "no default declared" and "installed by an older CLI" apart.
 *
 * `jsonui-test validate` writes `jsonui-test-run.json` on EVERY install,
 * including when it declares nothing, so the two states are distinguishable at
 * run time. They have to be distinguishable HERE and nowhere else: this
 * driver's version is not readable from a project tree, so `x-requires-driver`
 * — which is what tells a web project it is running too old a driver — can
 * only ever produce a note for Android. If `load` returned an empty table for
 * a missing file, a bundle installed before the feature existed would be
 * indistinguishable from one whose project declared no default, and a tablet
 * lane would silently keep running in whatever orientation it booted in,
 * which is the exact state this whole change exists to end.
 *
 * The parse half is unit-tested rather than the asset half: the version check
 * and the miss taxonomy are the parts worth pinning, and an instrumentation
 * test is a far coarser instrument for them.
 */
class RunDefaultsTest {

    @Test
    fun readsATable() {
        val load = RunDefaultsLoader.parse(
            """{"schemaVersion":1,"orientation":{"regular":"landscape"}}"""
        )
        assertNull(load.miss)
        assertEquals(mapOf("regular" to "landscape"), load.defaults?.orientation)
    }

    @Test
    fun anEmptyTableIsATableNotAMiss() {
        val load = RunDefaultsLoader.parse("""{"schemaVersion":1,"orientation":{}}""")
        assertNull(load.miss)
        assertEquals(emptyMap<String, String>(), load.defaults?.orientation)
    }

    @Test
    fun anOmittedOrientationIsAnEmptyTableNotAMiss() {
        // The CLI always writes the key, but a driver that fell over without
        // it would turn a forward-compatible file into a hard miss.
        val load = RunDefaultsLoader.parse("""{"schemaVersion":1}""")
        assertNull(load.miss)
        assertEquals(emptyMap<String, String>(), load.defaults?.orientation)
    }

    @Test
    fun aNewerSchemaVersionIsRefusedRatherThanGuessedAt() {
        val load = RunDefaultsLoader.parse("""{"schemaVersion":2,"orientation":{"regular":"landscape"}}""")
        assertNull(load.defaults)
        assertEquals(RunDefaultsMiss.UNKNOWN_VERSION, load.miss)
    }

    @Test
    fun malformedJsonIsAMissNotAnEmptyTable() {
        val load = RunDefaultsLoader.parse("{ not json")
        assertNull(load.defaults)
        assertEquals(RunDefaultsMiss.UNREADABLE, load.miss)
    }

    @Test
    fun aMissAlwaysCarriesAReason() {
        // A null with no reason is the shape a caller silently swallows.
        for (text in listOf("{ not json", """{"schemaVersion":9}""")) {
            val load = RunDefaultsLoader.parse(text)
            assertNull(load.defaults)
            org.junit.Assert.assertNotNull(load.reason)
        }
    }

    @Test
    fun unknownKeysDoNotBreakTheRead() {
        // Forward compatibility within a version: the CLI may add a sibling
        // field before this driver knows it, and refusing the whole file then
        // would drop a default this driver CAN honour.
        val load = RunDefaultsLoader.parse(
            """{"schemaVersion":1,"orientation":{"regular":"landscape"},"future":true}"""
        )
        assertNull(load.miss)
        assertEquals("landscape", RunDefaultsLoader.forTier(load.defaults, "regular"))
    }

    @Test
    fun forTierReturnsTheRow() {
        val load = RunDefaultsLoader.parse(
            """{"schemaVersion":1,"orientation":{"compact":"portrait","regular":"landscape"}}"""
        )
        assertEquals("portrait", RunDefaultsLoader.forTier(load.defaults, "compact"))
        assertEquals("landscape", RunDefaultsLoader.forTier(load.defaults, "regular"))
    }

    @Test
    fun forTierIsNullForATierWithNoRow() {
        val load = RunDefaultsLoader.parse("""{"schemaVersion":1,"orientation":{"regular":"landscape"}}""")
        assertNull(RunDefaultsLoader.forTier(load.defaults, "medium"))
    }

    @Test
    fun forTierIsNullForNullDefaults() {
        // A miss is not a default. Folding it into one is the defect above.
        assertNull(RunDefaultsLoader.forTier(null, "regular"))
    }

    @Test
    fun forTierDropsAValueItCannotName() {
        // The CLI validates the table before writing it, so a value like this
        // means a hand-edited bundle; forwarding it would ask the device for
        // an orientation no test file could have requested.
        val load = RunDefaultsLoader.parse("""{"schemaVersion":1,"orientation":{"regular":"sideways"}}""")
        assertNull(RunDefaultsLoader.forTier(load.defaults, "regular"))
    }
}

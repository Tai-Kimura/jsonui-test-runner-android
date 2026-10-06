package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * An app without Compose (a View / XML app) must not fail on [ZeroSizeNodes].
 *
 * Compose is compileOnly in this module, so it is NOT on the unit-test
 * classpath — which is exactly an app without Compose. The first test is the
 * control that makes the other two mean that: if Compose were here, a green
 * `available == false` would be impossible and the guard would be untested.
 * The on-device half (a Compose app finds a 0 x 0 tagged node) is
 * KotlinJsonUI conformance-host's ZeroSizeDriverProbeTest.
 */
class ZeroSizeNodesWithoutComposeTest {

    @Test
    fun composeIsNotOnThisClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("androidx.compose.ui.node.RootForTest")
        }
    }

    @Test
    fun itSaysComposeIsUnavailable() {
        assertFalse(ZeroSizeNodes.available)
    }

    @Test
    fun aLookupFindsNothingAndLoadsNoComposeClass() {
        // A NoClassDefFoundError here is the failure this guards against.
        assertEquals(emptyList<ZeroSizeNodes.Hit>(), ZeroSizeNodes.find("any_id"))
    }
}

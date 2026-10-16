package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.LaunchConfig
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the mocks/launch relaunch fold: the truth table for the
 * single relaunch decision, and the shape of the extras that ride the intent.
 * The on-device half (the activity really starts once and can read the
 * extra) is LaunchDeliveryOnDeviceTest.
 */
class LaunchDecisionTest {

    @Test
    fun relaunchReasonTruthTable() {
        assertNull(relaunchReason(mocks = false, launch = false))
        assertEquals("mocks", relaunchReason(mocks = true, launch = false))
        assertEquals("launch", relaunchReason(mocks = false, launch = true))
        assertEquals("mocks+launch", relaunchReason(mocks = true, launch = true))
        // initialState (spec §10.2) is a reason of its own, and shares the one relaunch.
        assertEquals("initialState", relaunchReason(mocks = false, launch = false, initialState = true))
        assertEquals("mocks+initialState", relaunchReason(mocks = true, launch = false, initialState = true))
        assertEquals("launch+initialState", relaunchReason(mocks = false, launch = true, initialState = true))
        assertEquals("mocks+launch+initialState", relaunchReason(mocks = true, launch = true, initialState = true))
    }

    @Test
    fun launchExtrasCarryArgumentsAsJson() {
        assertTrue(launchExtras(null).isEmpty())
        // clearState/permissions alone put nothing on the intent.
        assertTrue(launchExtras(LaunchConfig(clearState = true)).isEmpty())
        val extras = launchExtras(
            LaunchConfig(arguments = mapOf("probe" to JsonPrimitive("hello")))
        )
        assertEquals(setOf("JSONUI_TEST_ARGS"), extras.keys)
        assertEquals("""{"probe":"hello"}""", extras["JSONUI_TEST_ARGS"])
    }

    @Test
    fun launchExtrasCarryTheInitialStatePayloadBesideTheArguments() {
        assertEquals(mapOf("JSONUI_TEST_INITIAL_STATE" to "{}"), launchExtras(null, "{}"))
        val both = launchExtras(LaunchConfig(arguments = mapOf("probe" to JsonPrimitive("hello"))), "{}")
        assertEquals(setOf("JSONUI_TEST_ARGS", "JSONUI_TEST_INITIAL_STATE"), both.keys)
    }
}

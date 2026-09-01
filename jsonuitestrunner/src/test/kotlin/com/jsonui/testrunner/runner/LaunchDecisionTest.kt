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
}

package com.jsonui.testrunner.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression: test-flow-file-level-mocks-silently-ignored.
 *
 * FlowTest previously had no `mocks` field, so kotlinx's ignoreUnknownKeys
 * dropped a file-level `mocks` map at parse time and the runner never applied
 * it. These tests pin the field so the map survives decoding.
 */
class FlowMocksDecodeTest {

    // Same configuration as TestLoader
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun fileLevelMocksDecodeIntoFlowTest() {
        val flow = json.decodeFromString(
            FlowTest.serializer(),
            """
            {
                "type": "flow",
                "metadata": { "name": "flow_with_mocks" },
                "mocks": { "listDrinkingHistory": "real_id", "getDrinkingHistory": "real_id" },
                "steps": [ { "screen": "home", "action": "tap", "id": "start_button" } ]
            }
            """.trimIndent()
        )

        assertEquals(
            mapOf("listDrinkingHistory" to "real_id", "getDrinkingHistory" to "real_id"),
            flow.mocks
        )
    }

    @Test
    fun absentMocksDecodeToNull() {
        val flow = json.decodeFromString(
            FlowTest.serializer(),
            """
            {
                "type": "flow",
                "metadata": { "name": "flow_no_mocks" },
                "steps": [ { "screen": "home", "action": "tap", "id": "start_button" } ]
            }
            """.trimIndent()
        )

        assertNull(flow.mocks)
    }
}

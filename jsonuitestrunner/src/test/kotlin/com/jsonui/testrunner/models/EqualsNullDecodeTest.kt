package com.jsonui.testrunner.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `equals: null` is a value (`state` of a key holding null, spec §10.4), not
 * a missing `equals`. Until 1.17.0 it decoded as Kotlin null and the step
 * failed with "state requires 'equals'".
 */
class EqualsNullDecodeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun aWrittenNullIsJsonNullAndAnAbsentEqualsIsNull() {
        assertEquals(JsonNull, json.decodeFromString<TestStep>("""{"assert":"state","path":"n","equals":null}""").equals)
        assertNull(json.decodeFromString<TestStep>("""{"assert":"state","path":"n"}""").equals)
        assertEquals(JsonPrimitive(3), json.decodeFromString<TestStep>("""{"assert":"state","path":"n","equals":3}""").equals)
        assertEquals(JsonNull, json.decodeFromString<FlowTestStep>("""{"assert":"state","path":"n","equals":null}""").equals)
    }
}

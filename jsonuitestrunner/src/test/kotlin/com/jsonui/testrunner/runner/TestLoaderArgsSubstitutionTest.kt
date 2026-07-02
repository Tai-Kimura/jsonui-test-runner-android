package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for TestLoader args substitution.
 *
 * Also guards the substituteArgsInString rewrite (explicit non-null local
 * binding instead of a lambda-captured smart cast, which Kotlin 2.x rejects).
 */
class TestLoaderArgsSubstitutionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeCase(raw: String): TestCase = json.decodeFromString(TestCase.serializer(), raw)

    @Test
    fun substitutesArgsAcrossAllStepFields() {
        val testCase = decodeCase(
            """
            {
                "name": "Substitution Case",
                "args": { "userName": "alice", "row": 2 },
                "steps": [
                    { "action": "input", "id": "field_@{userName}", "value": "hello @{userName}" },
                    { "action": "tap", "ids": ["btn_@{userName}", "btn_@{row}"] },
                    { "assert": "text", "id": "label", "contains": "@{userName}" },
                    { "action": "tap", "button": "@{userName}", "label": "@{userName}", "text": "@{userName}" }
                ]
            }
            """.trimIndent()
        )

        val substituted = TestLoader().applyArgsSubstitution(testCase)

        assertEquals("field_alice", substituted.steps[0].id)
        assertEquals("hello alice", substituted.steps[0].value)
        assertEquals(listOf("btn_alice", "btn_2"), substituted.steps[1].ids)
        assertEquals("alice", substituted.steps[2].contains)
        assertEquals("alice", substituted.steps[3].button)
        assertEquals("alice", substituted.steps[3].label)
        assertEquals("alice", substituted.steps[3].text)
        // Untouched fields survive
        assertEquals("text", substituted.steps[2].assert)
        assertEquals("label", substituted.steps[2].id)
    }

    @Test
    fun flowArgsOverrideScreenDefaults() {
        val testCase = decodeCase(
            """
            {
                "name": "Override Case",
                "args": { "userName": "default" },
                "steps": [
                    { "assert": "text", "id": "greeting", "equals": "Hi @{userName}" }
                ]
            }
            """.trimIndent()
        )

        val substituted = TestLoader().applyArgsSubstitution(
            testCase,
            flowArgs = mapOf("userName" to JsonPrimitive("bob"))
        )

        assertEquals(JsonPrimitive("Hi bob"), substituted.steps[0].equals)
    }

    @Test
    fun unknownPlaceholdersAreLeftIntact() {
        val testCase = decodeCase(
            """
            {
                "name": "Unknown Placeholder",
                "args": { "known": "yes" },
                "steps": [
                    { "action": "input", "id": "f", "value": "@{known}/@{unknown}" }
                ]
            }
            """.trimIndent()
        )

        val substituted = TestLoader().applyArgsSubstitution(testCase)

        assertEquals("yes/@{unknown}", substituted.steps[0].value)
    }
}

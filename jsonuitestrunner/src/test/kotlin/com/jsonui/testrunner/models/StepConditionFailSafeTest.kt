package com.jsonui.testrunner.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the fail-safe tolerance of unknown condition keys.
 *
 * `Json { ignoreUnknownKeys = true }` (the TestLoader configuration) would
 * silently drop a condition key this driver does not understand, making the
 * guarded step run-anyway. StepConditionSerializer must instead capture such
 * keys in StepCondition.unknownKeys so the runner can skip the step.
 *
 * `responsive` graduated from unknown to known in Phase 3 (it decodes and is
 * evaluated), so these tests use a fabricated future key (`colorScheme`) as
 * the unknown-key example.
 */
class StepConditionFailSafeTest {

    // Same configuration as TestLoader
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun unknownConditionKeyIsCapturedNotDropped() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """{ "colorScheme": "dark", "visible": "sidebar" }"""
        )

        assertEquals(listOf("colorScheme"), condition.unknownKeys)
        // Known keys still decode alongside the unknown one
        assertEquals("sidebar", condition.visible)
    }

    @Test
    fun knownKeysOnlyProduceNoUnknownKeys() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """
            {
                "visible": "a",
                "notVisible": "b",
                "platform": ["android", "ios"],
                "state": { "path": "user.loggedIn", "equals": true },
                "responsive": "regular"
            }
            """.trimIndent()
        )

        assertTrue(condition.unknownKeys.isEmpty())
        assertEquals("a", condition.visible)
        assertEquals("b", condition.notVisible)
        assertTrue(condition.platform?.includes("android") == true)
        assertEquals("user.loggedIn", condition.state?.path)
        // responsive is a KNOWN key since Phase 3: typed, not an unknown key
        assertEquals(ResponsiveCondition.Named("regular"), condition.responsive)
    }

    @Test
    fun unknownKeySurvivesInsideStepWhenAndRepeatWhile() {
        val step = json.decodeFromString(
            TestStep.serializer(),
            """
            {
                "action": "repeat",
                "while": { "colorScheme": { "is": "dark" } },
                "steps": [
                    { "action": "tap", "id": "next", "when": { "colorScheme": "light" } }
                ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("colorScheme"), step.whileCondition?.unknownKeys)
        assertEquals(listOf("colorScheme"), step.steps?.first()?.whenCondition?.unknownKeys)
    }

    @Test
    fun unknownKeySurvivesInsideFlowStepWhen() {
        val step = json.decodeFromString(
            FlowTestStep.serializer(),
            """
            {
                "screen": "home",
                "assert": "visible",
                "id": "sidebar",
                "when": { "colorScheme": "dark", "platform": "android" }
            }
            """.trimIndent()
        )

        assertEquals(listOf("colorScheme"), step.whenCondition?.unknownKeys)
        assertTrue(step.whenCondition?.platform?.includes("android") == true)
    }

    @Test
    fun responsiveIsNotAnUnknownKeyAnymore() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """{ "responsive": { "minWidth": 840 } }"""
        )

        assertTrue(condition.unknownKeys.isEmpty())
        assertEquals(ResponsiveCondition.Constraint(minWidth = 840.0), condition.responsive)
    }

    @Test
    fun caseLevelResponsiveDecodesForNamedBucketAndConstraintObject() {
        val named = json.decodeFromString(
            TestCase.serializer(),
            """{ "name": "c1", "responsive": "regular", "steps": [] }"""
        )
        val constraint = json.decodeFromString(
            TestCase.serializer(),
            """{ "name": "c2", "responsive": { "minWidth": 840 }, "steps": [] }"""
        )
        val ungated = json.decodeFromString(
            TestCase.serializer(),
            """{ "name": "c3", "steps": [] }"""
        )

        assertEquals(ResponsiveCondition.Named("regular"), named.responsive)
        assertEquals(ResponsiveCondition.Constraint(minWidth = 840.0), constraint.responsive)
        assertNull(ungated.responsive)
    }

    @Test
    fun conditionRoundTripsKnownFields() {
        val condition = StepCondition(
            visible = "a",
            platform = PlatformTarget.Single("android"),
            responsive = ResponsiveCondition.Named("regular-landscape")
        )
        val encoded = json.encodeToString(StepCondition.serializer(), condition)
        val decoded = json.decodeFromString(StepCondition.serializer(), encoded)

        assertEquals(condition, decoded)
    }
}

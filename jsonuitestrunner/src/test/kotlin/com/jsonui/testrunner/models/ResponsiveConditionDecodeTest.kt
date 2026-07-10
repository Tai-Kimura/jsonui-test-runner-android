package com.jsonui.testrunner.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for decoding the `responsive` value (Phase 3): named bucket
 * string vs constraint object at both the condition level (`when` / `while`)
 * and the case level, plus fail-safe decoding of shapes this driver cannot
 * interpret (they become Unsupported and never match — never a hard error).
 */
class ResponsiveConditionDecodeTest {

    // Same configuration as TestLoader
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun namedBucketDecodesAtConditionLevel() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """{ "responsive": "compact-landscape" }"""
        )

        assertEquals(ResponsiveCondition.Named("compact-landscape"), condition.responsive)
        assertTrue(condition.unknownKeys.isEmpty())
    }

    @Test
    fun constraintObjectDecodesAllKeys() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """
            {
                "responsive": {
                    "minWidth": 600,
                    "maxWidth": 839.5,
                    "minHeight": 400,
                    "maxHeight": 1200,
                    "orientation": "portrait"
                }
            }
            """.trimIndent()
        )

        assertEquals(
            ResponsiveCondition.Constraint(
                minWidth = 600.0,
                maxWidth = 839.5,
                minHeight = 400.0,
                maxHeight = 1200.0,
                orientation = "portrait"
            ),
            condition.responsive
        )
    }

    @Test
    fun partialConstraintLeavesAbsentKeysNull() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """{ "responsive": { "minWidth": 840 } }"""
        )

        assertEquals(ResponsiveCondition.Constraint(minWidth = 840.0), condition.responsive)
    }

    @Test
    fun caseLevelNamedAndConstraintDecodeTyped() {
        val named = json.decodeFromString(
            TestCase.serializer(),
            """{ "name": "tablet", "responsive": "regular", "steps": [] }"""
        )
        val constraint = json.decodeFromString(
            TestCase.serializer(),
            """{ "name": "narrow", "responsive": { "maxWidth": 599, "orientation": "portrait" }, "steps": [] }"""
        )

        assertEquals(ResponsiveCondition.Named("regular"), named.responsive)
        assertEquals(
            ResponsiveCondition.Constraint(maxWidth = 599.0, orientation = "portrait"),
            constraint.responsive
        )
    }

    @Test
    fun unsupportedShapesDecodeFailSafeNotThrow() {
        val number = json.decodeFromString(
            StepCondition.serializer(),
            """{ "responsive": 840 }"""
        )
        val array = json.decodeFromString(
            StepCondition.serializer(),
            """{ "responsive": ["regular", "medium"] }"""
        )

        assertEquals(ResponsiveCondition.Unsupported, number.responsive)
        assertEquals(ResponsiveCondition.Unsupported, array.responsive)
        // ...and Unsupported never matches, so the gated step fail-safe skips
        val size = WindowDimensions(width = 900, height = 500)
        assertTrue(!matchesResponsive(ResponsiveCondition.Unsupported, size, ResponsiveThresholds()))
    }

    @Test
    fun namedAndConstraintRoundTrip() {
        for (original in listOf(
            StepCondition(responsive = ResponsiveCondition.Named("medium")),
            StepCondition(
                responsive = ResponsiveCondition.Constraint(minWidth = 600.0, orientation = "landscape")
            )
        )) {
            val encoded = json.encodeToString(StepCondition.serializer(), original)
            val decoded = json.decodeFromString(StepCondition.serializer(), encoded)
            assertEquals(original, decoded)
        }
    }
}

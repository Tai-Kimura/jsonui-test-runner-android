package com.jsonui.testrunner.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure responsive matching logic (no device needed —
 * window dimensions in dp are injected).
 *
 * Semantics mirror the Android renderer (kjui compose codegen):
 * - tiers: compact < 600dp, medium 600..839dp, regular >= 840dp (defaults)
 * - landscape iff width > height (INLINE_LANDSCAPE_CONDITION; square is
 *   NOT landscape)
 */
class ResponsiveMatchingTest {

    private val defaults = ResponsiveThresholds()

    private fun matches(condition: ResponsiveCondition, width: Int, height: Int,
                        thresholds: ResponsiveThresholds = defaults): Boolean =
        matchesResponsive(condition, WindowDimensions(width, height), thresholds)

    private fun named(value: String) = ResponsiveCondition.Named(value)

    // MARK: Tier resolution (exclusive, renderer default thresholds)

    @Test
    fun tierBoundariesMatchRendererRules() {
        assertEquals("compact", resolveSizeTier(0, defaults))
        assertEquals("compact", resolveSizeTier(599, defaults))
        assertEquals("medium", resolveSizeTier(600, defaults))
        assertEquals("medium", resolveSizeTier(839, defaults))
        assertEquals("regular", resolveSizeTier(840, defaults))
        assertEquals("regular", resolveSizeTier(2000, defaults))
    }

    @Test
    fun tiersAreExclusiveWhenMatching() {
        // 700dp wide is medium: it must NOT match compact or regular
        assertTrue(matches(named("medium"), 700, 1000))
        assertFalse(matches(named("compact"), 700, 1000))
        assertFalse(matches(named("regular"), 700, 1000))
    }

    // MARK: Orientation (renderer rule: landscape iff width > height)

    @Test
    fun landscapeIffWidthGreaterThanHeight() {
        assertEquals("landscape", deriveOrientation(WindowDimensions(900, 500)))
        assertEquals("portrait", deriveOrientation(WindowDimensions(500, 900)))
        // Square window is NOT landscape (kjui: containerSize.width > height)
        assertEquals("portrait", deriveOrientation(WindowDimensions(600, 600)))
    }

    @Test
    fun namedTierMatchesAnyOrientation() {
        // medium portrait and medium landscape both match bare "medium"
        assertTrue(matches(named("medium"), 700, 1000))
        assertTrue(matches(named("medium"), 700, 500))
    }

    @Test
    fun landscapeBucketMatchesAnyTier() {
        assertTrue(matches(named("landscape"), 500, 400))   // compact landscape
        assertTrue(matches(named("landscape"), 700, 500))   // medium landscape
        assertTrue(matches(named("landscape"), 900, 800))   // regular landscape
        assertFalse(matches(named("landscape"), 400, 500))  // portrait
        assertFalse(matches(named("landscape"), 600, 600))  // square -> portrait
    }

    @Test
    fun hyphenatedComboRequiresTierAndLandscape() {
        assertTrue(matches(named("regular-landscape"), 900, 500))
        assertFalse(matches(named("regular-landscape"), 900, 1200)) // regular but portrait
        assertFalse(matches(named("medium-landscape"), 900, 500))   // landscape but regular
        assertTrue(matches(named("compact-landscape"), 500, 400))
        assertTrue(matches(named("medium-landscape"), 700, 500))
    }

    @Test
    fun unknownBucketNeverMatchesFailSafe() {
        // "expanded" is a Material-3 name the renderer never emits
        assertFalse(matches(named("expanded"), 900, 500))
        assertFalse(matches(named(""), 900, 500))
    }

    // MARK: Constraint objects (inclusive min/max, ANDed)

    @Test
    fun constraintMinMaxAreInclusive() {
        assertTrue(matches(ResponsiveCondition.Constraint(minWidth = 840.0), 840, 1000))
        assertFalse(matches(ResponsiveCondition.Constraint(minWidth = 840.0), 839, 1000))
        assertTrue(matches(ResponsiveCondition.Constraint(maxWidth = 839.0), 839, 1000))
        assertFalse(matches(ResponsiveCondition.Constraint(maxWidth = 839.0), 840, 1000))
        assertTrue(matches(ResponsiveCondition.Constraint(minHeight = 500.0, maxHeight = 500.0), 400, 500))
        assertFalse(matches(ResponsiveCondition.Constraint(minHeight = 501.0), 400, 500))
    }

    @Test
    fun constraintKeysAreAnded() {
        val band = ResponsiveCondition.Constraint(
            minWidth = 600.0,
            maxWidth = 839.0,
            orientation = "portrait"
        )
        assertTrue(matches(band, 700, 1000))
        assertFalse(matches(band, 700, 500))   // orientation fails
        assertFalse(matches(band, 599, 1000))  // minWidth fails
        assertFalse(matches(band, 840, 1000))  // maxWidth fails
    }

    @Test
    fun constraintOrientationComparesDerivedOrientation() {
        assertTrue(matches(ResponsiveCondition.Constraint(orientation = "landscape"), 900, 500))
        assertFalse(matches(ResponsiveCondition.Constraint(orientation = "landscape"), 500, 900))
        assertTrue(matches(ResponsiveCondition.Constraint(orientation = "portrait"), 600, 600))
        // Unknown orientation value never matches (fail-safe)
        assertFalse(matches(ResponsiveCondition.Constraint(orientation = "upside-down"), 900, 500))
    }

    @Test
    fun emptyConstraintMatchesEverything() {
        // The CLI validator rejects empty {}; the runtime stays permissive
        assertTrue(matches(ResponsiveCondition.Constraint(), 1, 1))
    }

    // MARK: Threshold overrides (thresholds are config; bucket names are not)

    @Test
    fun thresholdOverrideMovesTierBoundaries() {
        val custom = ResponsiveThresholds(medium = 500, regular = 700)
        assertEquals("compact", resolveSizeTier(499, custom))
        assertEquals("medium", resolveSizeTier(500, custom))
        assertEquals("medium", resolveSizeTier(699, custom))
        assertEquals("regular", resolveSizeTier(700, custom))

        // 650dp is medium under the override (would be medium under defaults
        // too only from 600) and regular is reached at 700 instead of 840
        assertTrue(matches(named("regular"), 700, 1000, custom))
        assertFalse(matches(named("regular"), 700, 1000, defaults))
    }
}

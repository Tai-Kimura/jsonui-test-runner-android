package com.jsonui.testrunner.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Schema precedence for selectOption selectors: index, then value, then label. */
class SelectOptionSelectorTest {

    @Test
    fun allThreeGivenIndexWins() {
        // The 2026-09-03 consumer step: index plus a free-text note in label.
        assertEquals(
            SelectOptionSelector.ByIndex(1),
            SelectOptionSelector.resolve(1, "2024", "R2: first selectOption (note)")
        )
    }

    @Test
    fun valueBeatsLabelWhenNoIndex() {
        assertEquals(
            SelectOptionSelector.ByValue("2024"),
            SelectOptionSelector.resolve(null, "2024", "Twenty twenty-four")
        )
    }

    @Test
    fun labelAloneSelectsByLabel() {
        assertEquals(
            SelectOptionSelector.ByLabel("Twenty twenty-four"),
            SelectOptionSelector.resolve(null, null, "Twenty twenty-four")
        )
    }

    @Test
    fun indexZeroIsAnIndexNotAbsence() {
        assertEquals(SelectOptionSelector.ByIndex(0), SelectOptionSelector.resolve(0, "x", "y"))
    }

    @Test
    fun nothingGivenIsNull() {
        assertNull(SelectOptionSelector.resolve(null, null, null))
    }
}

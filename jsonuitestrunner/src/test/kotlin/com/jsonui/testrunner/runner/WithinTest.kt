package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.FlowTestStep
import com.jsonui.testrunner.models.StepCondition
import com.jsonui.testrunner.models.TestCase
import com.jsonui.testrunner.models.TestStep
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `within` (a step's, and a condition's): decoding, args substitution and the
 * failure text. The lookups themselves (ElementLookup) need a device.
 */
class WithinTest {

    // Same configuration as TestLoader
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun aScreenStepDecodesWithin() {
        val step = json.decodeFromString(
            TestStep.serializer(),
            """{ "assert": "text", "id": "ocr_field_label_text", "within": "ocr_fields_item_2", "equals": "年数" }"""
        )
        assertEquals("ocr_fields_item_2", step.within)
        assertEquals("ocr_field_label_text", step.id)
    }

    @Test
    fun aStepWithoutWithinHasNone() {
        val step = json.decodeFromString(TestStep.serializer(), """{ "action": "tap", "id": "submit" }""")
        assertNull(step.within)
    }

    @Test
    fun aFlowStepDecodesWithin() {
        val step = json.decodeFromString(
            FlowTestStep.serializer(),
            """{ "screen": "list", "action": "tapItem", "id": "inner", "index": 0, "within": "outer_item_3" }"""
        )
        assertEquals("outer_item_3", step.within)
    }

    @Test
    fun withinIsAKnownConditionKeyAndRoundTrips() {
        val condition = json.decodeFromString(
            StepCondition.serializer(),
            """{ "visible": "badge", "within": "rows_item_0" }"""
        )
        // Not an unknown key: before, it made the guarded step skip fail-safe.
        assertTrue(condition.unknownKeys.isEmpty())
        assertEquals("badge", condition.visible)
        assertEquals("rows_item_0", condition.within)
        val again = json.decodeFromString(
            StepCondition.serializer(),
            json.encodeToString(StepCondition.serializer(), condition)
        )
        assertEquals(condition, again)
    }

    @Test
    fun aConditionWithoutWithinEncodesNoWithinKey() {
        val condition = json.decodeFromString(StepCondition.serializer(), """{ "hidden": "banner" }""")
        assertNull(condition.within)
        assertFalse(json.encodeToString(StepCondition.serializer(), condition).contains("within"))
    }

    @Test
    fun withinIsSubstitutedFromArgs() {
        val testCase = json.decodeFromString(
            TestCase.serializer(),
            """
            {
                "name": "row",
                "args": { "row": 2 },
                "steps": [ { "assert": "visible", "id": "label", "within": "rows_item_@{row}" } ]
            }
            """.trimIndent()
        )
        assertEquals("rows_item_2", TestLoader().applyArgsSubstitution(testCase).steps[0].within)
    }

    @Test
    fun aMissingContainerIsNamedWithTheWayToBringItIn() {
        val text = Within.containerNotFound("rows_item_7", "label", 5000)
        assertTrue(text, text.contains("Container 'rows_item_7'"))
        assertTrue(text, text.contains("'label'"))
        assertTrue(text, text.contains("5000ms"))
        assertTrue(text, text.contains("only while it is drawn"))
        assertTrue(text, text.contains("scrollUntilVisible to 'rows_item_7' first"))
    }

    @Test
    fun aTargetMissingInsideAPresentContainerNamesBoth() {
        val text = Within.targetNotInContainer("label", "rows_item_7", 3000)
        assertTrue(text, text.contains("Element 'label' not found inside container 'rows_item_7'"))
        assertTrue(text, text.contains("3000ms"))
        assertTrue(text, text.contains("'rows_item_7' is on screen"))
    }

    @Test
    fun theLabelIsTheBareIdWithoutWithin() {
        // Unscoped messages keep their exact old wording ("Element 'x' ...").
        assertEquals("'x'", Within.label("x", null))
        assertEquals("'x' inside 'c'", Within.label("x", "c"))
    }

    // -- the container a scoped semantics read looks inside ----------------

    private data class Node(
        val tag: String?,
        val asResourceId: Boolean = false,
        val children: List<Node> = emptyList()
    )

    private fun first(roots: List<Node>, within: String): Node? =
        Within.firstContainer(roots, false, { it.children }, { n, above -> above || n.asResourceId }) { n, exposed ->
            exposed && n.tag == within
        }?.first

    /** Two sections, each with an `_item_0`: the second section's badge is drawn. */
    private val twoSections = Node(null, asResourceId = true, children = listOf(
        Node("list_section_0", children = listOf(Node("list_item_0", children = listOf(Node("title_a"))))),
        Node("list_section_1", children = listOf(Node("list_item_0", children = listOf(Node("title_b"), Node("badge")))))
    ))

    @Test
    fun theFirstOfTwoContainersWithOneIdIsTaken() {
        val container = first(listOf(twoSections), "list_item_0")
        assertEquals(listOf("title_a"), container?.children?.map { it.tag })
    }

    @Test
    fun theFirstContainerIsFoundInPreOrderAcrossRoots() {
        val dialog = Node(null, asResourceId = true, children = listOf(Node("list_item_0", children = listOf(Node("in_dialog")))))
        assertEquals(listOf("title_a"), first(listOf(twoSections, dialog), "list_item_0")?.children?.map { it.tag })
        assertEquals(listOf("in_dialog"), first(listOf(dialog, twoSections), "list_item_0")?.children?.map { it.tag })
    }

    @Test
    fun aContainerNotExposedAsAResourceIdIsNotOne() {
        val hidden = Node(null, children = listOf(Node("list_item_0")))
        assertNull(first(listOf(hidden), "list_item_0"))
        assertNull(first(listOf(twoSections), "list_item_9"))
    }

    @Test
    fun aMissingContainerFailsOnlyWithWithin() {
        assertTrue(Within.missingContainerFails("list_item_9", containerFound = false))
        assertFalse(Within.missingContainerFails("list_item_1", containerFound = true))
        assertFalse(Within.missingContainerFails(null, containerFound = false))
    }
}

package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every place a step is rebuilt carries every field of it. A field a rebuild leaves out reaches the action as its
 * default in silence: the iOS driver's args substitution dropped setSliderValue's range / step / tolerance that way
 * (jsonui-test-runner-ios cb0e510). Here the rebuilds are
 *  - FlowTestStep.toTestStep (JsonUITestRunner.kt): a constructor call, which must name every TestStep field;
 *  - substituteArgsInStep in TestLoader.kt and JsonUITestRunner.kt: `step.copy(...)`, which carries every field by
 *    construction, and must substitute `@{}` in the ids a step names (id, within, handle).
 * A source gate: the emitted step is the same whether a field is carried or defaulted, until a test happens to set it.
 */
class StepRebuildCarriesEveryFieldTest {
    private val root: File by lazy {
        val rel = "src/main/kotlin/com/jsonui/testrunner"
        listOf(File(rel), File("jsonuitestrunner/$rel")).first { it.isDirectory }
    }

    private fun params(source: String, cls: String): List<String> {
        val start = source.indexOf("data class $cls(") + "data class $cls(".length - 1
        var depth = 0
        var end = start
        for (k in start until source.length) {
            if (source[k] == '(') depth++
            if (source[k] == ')') { depth--; if (depth == 0) { end = k; break } }
        }
        val body = source.substring(start + 1, end).replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("^\\s*(?:@\\S+\\s*)*val (\\w+)\\s*:", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `FlowTestStep's toTestStep names every TestStep field`() {
        val models = File(root, "models/TestModels.kt").readText()
        val testStep = params(models, "TestStep")
        assertTrue("TestStep's fields not read: $testStep", testStep.size > 40 && "handle" in testStep && "tolerance" in testStep)
        // Every TestStep field has its FlowTestStep twin, so the rebuild can carry it.
        assertEquals(emptyList<String>(), testStep - params(models, "FlowTestStep").toSet())
        val runner = File(root, "runner/JsonUITestRunner.kt").readText()
        val at = runner.indexOf("private fun FlowTestStep.toTestStep(): TestStep = TestStep(")
        assertTrue("toTestStep not found", at >= 0)
        val call = runner.substring(at, runner.indexOf("\n    )", at))
        val named = Regex("^\\s+(\\w+) =", RegexOption.MULTILINE).findAll(call).map { it.groupValues[1] }.toSet()
        assertEquals("TestStep fields toTestStep leaves out", emptyList<String>(), testStep.filter { it !in named })
    }

    @Test
    fun `each args substitution copies the step and substitutes the ids it names`() {
        for (file in listOf("runner/TestLoader.kt", "runner/JsonUITestRunner.kt")) {
            val source = File(root, file).readText()
            val at = source.indexOf("private fun substituteArgsInStep(step: TestStep")
            assertTrue("$file: substituteArgsInStep not found", at >= 0)
            val body = source.substring(at, source.indexOf("\n    }", at))
            assertTrue("$file: the rebuild is not step.copy(...)", body.contains("return step.copy("))
            for (field in listOf("id", "within", "handle")) {
                assertTrue("$file: '$field' is not substituted", body.contains("$field = substituteArgsInString(step.$field, args)"))
            }
        }
    }
}

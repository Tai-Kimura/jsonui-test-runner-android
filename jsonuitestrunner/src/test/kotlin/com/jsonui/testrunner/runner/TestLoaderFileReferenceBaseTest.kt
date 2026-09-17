package com.jsonui.testrunner.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * File-reference resolution in a flow: the base the references resolve
 * against is the FLOW file's directory, and reading a referenced screen
 * test must not move it.
 *
 * Before the fix, `load(path)` set `basePath` unconditionally, so the first
 * `file:` reference moved the base to `screens/<first>/` and the second
 * reference (a different screen) looked under `screens/screens/<second>/`
 * and was "not found". Referencing the SAME screen twice passed by accident
 * (`<base>/<ref>.test.json` happened to exist), which is why the defect
 * stayed invisible until a flow crossed two screens. Same shape in the web
 * and iOS drivers (one ticket, three faces).
 *
 * `gamma` lives in tests/flows/ beside the flow, so it resolves ONLY while
 * the base is the flow directory — a behavioural probe of the invariant,
 * beside the direct `basePath` assertion.
 */
class TestLoaderFileReferenceBaseTest {

    private lateinit var root: File
    private lateinit var flowPath: String
    private lateinit var alphaPath: String

    private fun screenTest(name: String) = """
        {"type":"screen","source":{"layout":"layouts/$name.json"},
         "metadata":{"name":"$name"},
         "cases":[{"name":"initial_display","steps":[]}]}
    """.trimIndent()

    private fun flowTest() = """
        {"type":"flow","metadata":{"name":"two screens"},
         "steps":[{"file":"alpha","case":"initial_display"},
                  {"file":"beta","case":"initial_display"}]}
    """.trimIndent()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("jtr-basepath-").toFile()
        val flows = File(root, "tests/flows").apply { mkdirs() }
        val alpha = File(root, "tests/screens/alpha").apply { mkdirs() }
        val beta = File(root, "tests/screens/beta").apply { mkdirs() }
        flowPath = File(flows, "two.test.json").apply { writeText(flowTest()) }.path
        alphaPath = File(alpha, "alpha.test.json").apply { writeText(screenTest("alpha")) }.path
        File(beta, "beta.test.json").writeText(screenTest("beta"))
        File(flows, "gamma.test.json").writeText(screenTest("gamma"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // The reported shape: two DIFFERENT screens, in either order.
    @Test
    fun resolvesASecondDifferentScreenAfterTheFirstWasRead() {
        val loader = TestLoader().apply { setBasePath(flowPath) }
        assertEquals("alpha", loader.resolveFileReference("alpha").metadata.name)
        assertEquals("beta", loader.resolveFileReference("beta").metadata.name)

        val reversed = TestLoader().apply { setBasePath(flowPath) }
        assertEquals("beta", reversed.resolveFileReference("beta").metadata.name)
        assertEquals("alpha", reversed.resolveFileReference("alpha").metadata.name)
    }

    // The form that passed before the fix — by accident — has to keep passing.
    @Test
    fun stillResolvesTheSameScreenReferencedTwice() {
        val loader = TestLoader().apply { setBasePath(flowPath) }
        assertEquals("alpha", loader.resolveFileReference("alpha").metadata.name)
        assertEquals("alpha", loader.resolveFileReference("alpha").metadata.name)
    }

    // The invariant itself, stated directly AND behaviourally.
    @Test
    fun leavesTheBaseAtTheFlowDirectoryAfterResolvingAReference() {
        val loader = TestLoader().apply { setBasePath(flowPath) }
        loader.resolveFileReference("alpha")
        assertEquals(File(flowPath).parent, loader.basePath)
        assertEquals("gamma", loader.resolveFileReference("gamma").metadata.name)
    }

    // The step-level entry the runner actually calls.
    @Test
    fun resolvesEveryFileStepOfATwoScreenFlowThroughResolveFileReferenceCases() {
        val loader = TestLoader().apply { setBasePath(flowPath) }
        val flow = loader.load(flowPath)
        assertTrue(flow is LoadedTest.Flow)
        val names = (flow as LoadedTest.Flow).test.steps.flatMap { step ->
            loader.resolveFileReferenceCases(step).map { it.name }
        }
        assertEquals(listOf("initial_display", "initial_display"), names)
    }

    // Negative control: the resolver still refuses to guess a base.
    @Test
    fun refusesToResolveWhenNoBaseHasBeenSet() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TestLoader().resolveFileReference("alpha")
        }
        assertTrue(error.message!!.contains("Base path not set"))
    }

    // A TOP-LEVEL load still owns the base — a screen test run on its own
    // resolves against its own directory, so the flow-only reference is gone.
    @Test
    fun movesTheBaseOnATopLevelLoad() {
        val loader = TestLoader().apply { setBasePath(flowPath) }
        loader.load(alphaPath)
        assertEquals(File(alphaPath).parent, loader.basePath)
        assertThrows(IllegalArgumentException::class.java) { loader.resolveFileReference("gamma") }
    }
}

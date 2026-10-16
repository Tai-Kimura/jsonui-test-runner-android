package com.jsonui.testrunner.runner

import com.jsonui.testrunner.models.FlowTest
import com.jsonui.testrunner.models.ScreenTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared vectors of spec §10 — src/test/resources/vectors/ is a byte
 * copy of jsonui-test-runner specs/vectors/ (initial-state-payload.json,
 * state-path.json). The driver reads launches / caseFailures / warnings of the
 * payload vector, and both parts of the state-path vector.
 */
class InitialStateVectorsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun vector(name: String): JsonObject =
        json.parseToJsonElement(File("src/test/resources/vectors/$name").readText()).jsonObject

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    @Test
    fun thePayloadVector() {
        val cases = vector("initial-state-payload.json")["cases"]!!.jsonArray
        assertTrue(cases.size >= 19)
        val failures = mutableListOf<String>()
        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"].string()!!
            val test = case["test"]!!.jsonObject
            val files = (case["files"] as? JsonObject).orEmpty()
            val plan = when (test["type"].string()) {
                "screen" -> InitialStatePlan.plan(json.decodeFromJsonElement<ScreenTest>(test))
                "flow" -> InitialStatePlan.plan(json.decodeFromJsonElement<FlowTest>(test)) { file ->
                    files[file]?.let { json.decodeFromJsonElement<ScreenTest>(it) }
                }
                else -> { fail("$name: unknown type"); return }
            }
            val expect = case["expect"]!!.jsonObject

            val expectedLaunches = expect["launches"]!!.jsonArray.map {
                val l = it.jsonObject
                l["before"].string() to (l["screens"]?.takeUnless { s -> s is JsonNull })
            }
            val actualLaunches = plan.launches.map { l ->
                l.before to l.screens?.let { screens -> JsonObject(screens.mapValues { (_, v) -> JsonObject(v) }) }
            }
            if (actualLaunches != expectedLaunches) failures += "$name: launches $actualLaunches, expected $expectedLaunches"

            val expectedFailures = expect["caseFailures"]!!.jsonArray.map {
                val f = it.jsonObject
                assertEquals("$name: failureReason", "setup", f["failureReason"].string())
                f["case"].string() to f["code"].string()
            }.toSet()
            val actualFailures = plan.caseFailures.map { it.caseName to it.code }.toSet()
            if (actualFailures != expectedFailures) failures += "$name: caseFailures $actualFailures, expected $expectedFailures"

            val expectedWarnings = expect["warnings"]!!.jsonArray.map {
                val w = it.jsonObject
                Triple(w["code"].string(), w["file"].string(), w["case"].string())
            }.toSet()
            val actualWarnings = plan.warnings.map { Triple(it.code, it.file, it.caseName) }.toSet()
            if (actualWarnings != expectedWarnings) failures += "$name: warnings $actualWarnings, expected $expectedWarnings"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /** What rides the intent: version 1, the launch's id, the screens as planned. */
    @Test
    fun thePayloadText() {
        val text = InitialStatePayload.encode(mapOf("login" to mapOf("email" to JsonPrimitive("a"), "n" to JsonNull)), "L1")
        val root = json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive(1), root["version"])
        assertEquals("L1", root["launchId"].string())
        assertEquals(json.parseToJsonElement("""{"login":{"email":"a","n":null}}"""), root["screens"])
    }

    /** A screen of viewModels the flow never reached: one warning each, the web driver's words. */
    @Test
    fun anUnreachedScreenOfTheFlowIsAWarning() {
        assertEquals(
            listOf(
                "initialState for screen 'home' was never checked: the flow never reached it (no step names that screen)",
                "initialState for screen 'cart' was never checked: the flow never reached it (no step names that screen)"
            ),
            InitialStatePlan.unreachedWarnings(listOf("home", "cart"))
        )
        assertEquals(emptyList<String>(), InitialStatePlan.unreachedWarnings(emptyList()))
    }

    /** A flow's screen leaves the pending set only once its receipt has passed: a retry checks it again. */
    @Test
    fun aFlowScreenStaysPendingUntilItsReceiptPasses() {
        val receipts = FlowReceipts(mapOf("home" to "L1", "cart" to "L1"))
        val awaited = mutableListOf<String>()
        val failed = runCatching {
            receipts.checkOnArrival("home") { screen, _ -> awaited += screen; throw InitialStateNotConfirmedException("no receipt") }
        }.exceptionOrNull()
        assertTrue(failed is InitialStateNotConfirmedException)
        assertEquals("a failed receipt keeps the screen pending", setOf("home", "cart"), receipts.unreached)

        receipts.checkOnArrival("home") { screen, launchId -> awaited += "$screen@$launchId" }
        assertEquals(setOf("cart"), receipts.unreached)
        receipts.checkOnArrival("home") { screen, _ -> awaited += "again:$screen" }
        receipts.checkOnArrival("other") { screen, _ -> awaited += screen }
        assertEquals(listOf("home", "home@L1"), awaited)
    }

    /**
     * A second flow file that starts on the screen the first one ended on, and
     * a retried attempt: each attempt's head forgets the screen, so it is
     * verified and its receipt checked again, and `state` reads nothing until
     * a step names a screen.
     */
    @Test
    fun aFlowAttemptStartsWithNoScreenEvenOnTheScreenTheLastFlowEndedOn() {
        val screens = FlowScreens()
        val verified = mutableListOf<String>()
        val awaited = mutableListOf<String>()
        fun arrive(screen: String, receipts: FlowReceipts) =
            screens.arrive(screen, receipts, verify = { verified += it }, await = { s, l -> awaited += "$s@$l" })

        // Flow 1 ends on home.
        screens.startAttempt()
        arrive("home", FlowReceipts())
        arrive("home", FlowReceipts())
        assertEquals(listOf("home"), verified)

        // Flow 2 starts on home, with a payload for it.
        screens.startAttempt()
        assertEquals("state reads no screen before a step names one", null, screens.state)
        arrive("home", FlowReceipts(mapOf("home" to "L2")))
        assertEquals(listOf("home", "home"), verified)
        assertEquals(listOf("home@L2"), awaited)
        assertEquals("home", screens.state)
    }

    @Test
    fun screenIds() {
        assertEquals("ProductDetail", ScreenIdentity.screenId("Layouts/ProductDetail.json"))
        assertEquals("home", ScreenIdentity.screenId("a/b/home@regular.json"))
        assertEquals("home@wide", ScreenIdentity.screenId("home@wide.json"))
    }

    @Test
    fun theStatePathVector() {
        val vector = vector("state-path.json")
        val data = vector["data"]!!.jsonObject
        val failures = mutableListOf<String>()
        for (element in vector["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val path = case["path"].string()!!
            val expect = case["expect"]!!.jsonObject
            val result = StateBridge.resolve(path, data)
            val ok = if (expect.containsKey("value")) {
                result == StateBridge.PathResult.Value(expect["value"]!!)
            } else {
                result == StateBridge.PathResult.Unresolved
            }
            if (!ok) failures += "${case["name"].string()}: $path -> $result, expected $expect"
        }
        for (element in vector["bridges"]!!.jsonArray) {
            val case = element.jsonObject
            val expect = case["expect"]!!.jsonObject
            val outcome = StateBridge.outcome(StateBridge.parse(case["bridge"]), case["path"].string()!!)
            val ok = when (expect["outcome"].string()) {
                "pending" -> outcome == StateBridge.Outcome.Pending
                "truncated" -> outcome is StateBridge.Outcome.Truncated
                "unresolved" -> outcome == StateBridge.Outcome.Unresolved
                "value" -> outcome == StateBridge.Outcome.Value(expect["value"]!!)
                else -> false
            }
            if (!ok) failures += "${case["name"].string()}: $outcome, expected $expect"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    // -- the receipt ------------------------------------------------------------------

    private fun bridge(text: String) = StateBridge.parse(text)

    @Test
    fun aReceiptIsThisLaunchesAndHasNoRejection() {
        val confirmed = bridge("""{"launchId":"L1","initialState":{"applied":["a"],"rejected":[]},"data":{}}""")
        assertEquals(StateBridge.ReceiptState.Confirmed, StateBridge.receipt(confirmed, "L1"))
        assertEquals("another launch's receipt is not this one's", StateBridge.ReceiptState.Waiting, StateBridge.receipt(confirmed, "L2"))
        assertEquals(StateBridge.ReceiptState.Waiting,
            StateBridge.receipt(bridge("""{"launchId":"L1","initialState":null,"data":{}}"""), "L1"))
        assertEquals(StateBridge.ReceiptState.Waiting, StateBridge.receipt(StateBridge.Reading.Absent, "L1"))
        assertEquals(
            StateBridge.ReceiptState.Rejected(listOf(StateBridge.Rejection("x", "not a data field"))),
            StateBridge.receipt(bridge("""{"launchId":"L1","initialState":{"applied":[],"rejected":[{"key":"x","reason":"not a data field"}]},"data":{}}"""), "L1")
        )
    }

    @Test
    fun awaitingAReceipt() {
        var clock = 0L
        val readings = ArrayDeque(listOf(
            StateBridge.Reading.Absent,
            bridge("""{"launchId":"L1","initialState":null,"data":{}}"""),
            bridge("""{"launchId":"L1","initialState":{"applied":["a"],"rejected":[]},"data":{}}""")
        ))
        StateBridge.awaitReceipt("s", "L1", 1000, read = { readings.removeFirst() }, sleep = { clock += it }, now = { clock })
        assertEquals(400L, clock)

        clock = 0L
        val none = runCatching {
            StateBridge.awaitReceipt("s", "L1", 1000, read = { StateBridge.Reading.Absent }, sleep = { clock += it }, now = { clock })
        }.exceptionOrNull()
        assertTrue(none is InitialStateNotConfirmedException)
        assertTrue(none!!.message!!, none.message!!.contains("KotlinJsonUI older than 2.44.0"))
        assertEquals("setup", FailureClassifier.wireValue(none))

        val rejected = runCatching {
            StateBridge.awaitReceipt("s", "L1", 1000, read = {
                bridge("""{"launchId":"L1","initialState":{"applied":[],"rejected":[{"key":"n","reason":"type mismatch: expected Int"}]},"data":{}}""")
            }, sleep = {}, now = { 0L })
        }.exceptionOrNull()
        assertEquals("initialState for 's' was rejected by the app: 'n': type mismatch: expected Int", rejected?.message)
    }

    @Test
    fun aBridgeValueAsStateComparesIt() {
        assertEquals(null, StateBridge.plain(JsonNull))
        assertEquals("a", StateBridge.plain(JsonPrimitive("a")))
        assertEquals(true, StateBridge.plain(JsonPrimitive(true)))
        assertEquals(3L, StateBridge.plain(JsonPrimitive(3)))
        assertEquals(1.5, StateBridge.plain(JsonPrimitive(1.5)))
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), StateBridge.plain(JsonArray(listOf(JsonPrimitive(1)))))
    }
}

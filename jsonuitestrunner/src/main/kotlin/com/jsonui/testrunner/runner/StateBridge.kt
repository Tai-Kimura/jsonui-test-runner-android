package com.jsonui.testrunner.runner

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reading a screen's state bridge (spec §10.4–10.5): KotlinJsonUI (2.44.0)
 * puts `{launchId, initialState: {applied, rejected} | null, data}` on the
 * screen marker node (test tag `__screen_<screenId>`) under the semantics key
 * `JsonUIState` — no node of its own.
 *
 * The parsing, the path rule and the receipt judgement are pure and driven by
 * the shared vector `specs/vectors/state-path.json` on the JVM; only
 * [StateBridgeReader] touches the app (Compose is compileOnly, so it is kept
 * out of this object, which the JVM suite loads).
 */
object StateBridge {
    /** The semantics key KotlinJsonUI publishes the bridge under. */
    const val KEY = "JsonUIState"
    const val MARKER_PREFIX = "__screen_"

    data class Rejection(val key: String, val reason: String)

    data class Receipt(val applied: List<String>, val rejected: List<Rejection>)

    sealed interface Reading {
        /** No marker carrying a bridge: the screen is not showing, or its library has no bridge. */
        data object Absent : Reading
        /** Over a size limit (the iOS bridge's; KotlinJsonUI does not truncate). */
        data class Truncated(val bytes: Int) : Reading
        /** A value that is not a bridge (no `data`). */
        data object Malformed : Reading
        data class Bridge(val launchId: String?, val receipt: Receipt?, val data: JsonObject) : Reading
    }

    private val json = Json { isLenient = false }

    fun parse(value: String?): Reading {
        if (value == null) return Reading.Absent
        val root = runCatching { json.parseToJsonElement(value) as? JsonObject }.getOrNull() ?: return Reading.Malformed
        return parse(root)
    }

    fun parse(root: JsonElement?): Reading {
        if (root == null || root is JsonNull) return Reading.Absent
        if (root !is JsonObject) return Reading.Malformed
        (root["truncated"] as? JsonPrimitive)?.intOrNull?.let { return Reading.Truncated(it) }
        val data = root["data"] as? JsonObject ?: return Reading.Malformed
        val receipt = (root["initialState"] as? JsonObject)?.let { initialState ->
            Receipt(
                applied = (initialState["applied"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                rejected = (initialState["rejected"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val o = entry as? JsonObject ?: return@mapNotNull null
                    val key = (o["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    Rejection(key, (o["reason"] as? JsonPrimitive)?.contentOrNull ?: "")
                }
            )
        }
        val launchId = (root["launchId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Reading.Bridge(launchId, receipt, data)
    }

    // -- path ----------------------------------------------------------------------

    sealed interface PathResult {
        /** The value at the path; [JsonNull] for a key that holds null. */
        data class Value(val value: JsonElement) : PathResult
        data object Unresolved : PathResult
    }

    /**
     * `a.b.0.c`: on an object a segment is a key (case-sensitive, digits
     * included); on an array a segment of decimal digits with no leading zero
     * is an index from 0. An empty segment, and any step into a scalar or
     * null, does not resolve.
     */
    fun resolve(path: String, data: JsonObject): PathResult {
        val segments = path.split('.')
        if (segments.isEmpty() || segments.any { it.isEmpty() }) return PathResult.Unresolved
        var current: JsonElement = data
        for (segment in segments) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return PathResult.Unresolved
                is JsonArray -> {
                    if (!isIndex(segment)) return PathResult.Unresolved
                    val index = segment.toIntOrNull() ?: return PathResult.Unresolved
                    current.getOrNull(index) ?: return PathResult.Unresolved
                }
                else -> return PathResult.Unresolved
            }
        }
        return PathResult.Value(current)
    }

    private fun isIndex(segment: String): Boolean =
        segment.isNotEmpty() && segment.all { it in '0'..'9' } && (segment == "0" || !segment.startsWith("0"))

    // -- what a `state` read concludes ---------------------------------------------

    sealed interface Outcome {
        /** Keep waiting: no bridge (or a malformed one) yet. */
        data object Pending : Outcome
        /** Fail at once. */
        data class Truncated(val bytes: Int) : Outcome
        data class Value(val value: JsonElement) : Outcome
        /** The bridge is there and the path does not resolve (yet). */
        data object Unresolved : Outcome
    }

    fun outcome(reading: Reading, path: String): Outcome = when (reading) {
        Reading.Absent, Reading.Malformed -> Outcome.Pending
        is Reading.Truncated -> Outcome.Truncated(reading.bytes)
        is Reading.Bridge -> when (val r = resolve(path, reading.data)) {
            is PathResult.Value -> Outcome.Value(r.value)
            PathResult.Unresolved -> Outcome.Unresolved
        }
    }

    /**
     * A bridge value as `state` compares it: null, a String, a Boolean, a
     * number (Long when integral, else Double); an object or array stays JSON.
     */
    fun plain(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.longOrNull != null -> value.longOrNull
            value.doubleOrNull != null -> value.doubleOrNull
            else -> value.content
        }
        else -> value
    }

    // -- receipt -------------------------------------------------------------------

    sealed interface ReceiptState {
        /** No receipt for this launch yet. */
        data object Waiting : ReceiptState
        data object Confirmed : ReceiptState
        data class Rejected(val rejections: List<Rejection>) : ReceiptState
        data class Truncated(val bytes: Int) : ReceiptState
    }

    /** Whether the screen confirmed the payload of launch [launchId]. */
    fun receipt(reading: Reading, launchId: String): ReceiptState = when (reading) {
        is Reading.Truncated -> ReceiptState.Truncated(reading.bytes)
        is Reading.Bridge -> {
            val receipt = reading.receipt
            if (reading.launchId != launchId || receipt == null) ReceiptState.Waiting
            else if (receipt.rejected.isEmpty()) ReceiptState.Confirmed
            else ReceiptState.Rejected(receipt.rejected)
        }
        Reading.Absent, Reading.Malformed -> ReceiptState.Waiting
    }

    /**
     * Wait up to [timeoutMs] for [screenId] to confirm launch [launchId];
     * throws [InitialStateNotConfirmedException] when it does not, or rejects
     * a key. [read] is the bridge reader (the app's, unless a test passes one).
     */
    fun awaitReceipt(
        screenId: String,
        launchId: String,
        timeoutMs: Long,
        read: (String) -> Reading = StateBridgeReader::read,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = System::currentTimeMillis
    ) {
        val deadline = now() + timeoutMs
        var last: Reading = Reading.Absent
        while (true) {
            last = read(screenId)
            when (val state = receipt(last, launchId)) {
                ReceiptState.Confirmed -> return
                is ReceiptState.Rejected -> throw InitialStateNotConfirmedException(rejectedMessage(screenId, state.rejections))
                is ReceiptState.Truncated, ReceiptState.Waiting -> Unit
            }
            if (now() >= deadline) throw InitialStateNotConfirmedException(noReceiptMessage(screenId, timeoutMs, last))
            sleep(200)
        }
    }

    /** The failure text when no receipt came (spec §10.5). */
    fun noReceiptMessage(screenId: String, timeoutMs: Long, lastReading: Reading): String {
        val seen = when (lastReading) {
            Reading.Absent -> "no screen marker '$MARKER_PREFIX$screenId' carrying the state bridge was found"
            Reading.Malformed -> "the state bridge on '$MARKER_PREFIX$screenId' could not be read"
            is Reading.Truncated -> "the state bridge is ${lastReading.bytes} bytes and was truncated"
            is Reading.Bridge -> "the state bridge shows launch ${lastReading.launchId ?: "null"} without a receipt for this launch"
        }
        return "initialState for '$screenId' was not confirmed by the app within ${timeoutMs}ms ($seen). " +
            "Likely causes: KotlinJsonUI older than 2.44.0, or code generated by an older jsonui-cli " +
            "(rebuild with `jui build`); a build with the screen-marker gate closed (not debuggable and no " +
            "com.kotlinjsonui.SCREEN_MARKER meta-data); a screen id that does not match the layout ('$screenId')."
    }

    fun rejectedMessage(screenId: String, rejections: List<Rejection>): String =
        "initialState for '$screenId' was rejected by the app: " +
            rejections.joinToString("; ") { "'${it.key}': ${it.reason}" }

    /** The failure text for a `state` read of a truncated bridge. */
    fun truncatedMessage(screenId: String, bytes: Int): String =
        "the state bridge of '$screenId' is $bytes bytes and was truncated; nothing in it can be read"
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/**
 * The default `state` source when the harness passes no provider (spec
 * §10.5): the bridge `data` of the screen [screenId] names. Kept beside
 * [ViewModelStateProvider] rather than implementing it — the bridge tells
 * "the key holds null" from "no such key" and "no bridge yet", which a
 * provider's `getValue` returning `Any?` cannot.
 */
class BridgeStateSource(var screenId: () -> String?, var read: (String) -> StateBridge.Reading = StateBridgeReader::read) {
    fun outcome(path: String): StateBridge.Outcome {
        val screen = screenId() ?: return StateBridge.Outcome.Pending
        return StateBridge.outcome(read(screen), path)
    }
}

/**
 * The app side of [StateBridge]: the marker node's `JsonUIState`, read from
 * the app's unmerged semantics tree in-process, in every window, as
 * [HiddenNodes] reads `JsonUIHidden` — a custom semantics key never reaches
 * the accessibility tree. Without Compose there is no bridge: absent.
 */
object StateBridgeReader {
    /** The bridge of [screenId] as the app publishes it now. */
    fun read(screenId: String): StateBridge.Reading {
        if (!HiddenNodes.available) return StateBridge.Reading.Absent
        var value: String? = null
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runCatching { value = findBridge("${StateBridge.MARKER_PREFIX}$screenId") }
            }
        }
        return StateBridge.parse(value)
    }

    private fun rootViews(): List<View> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return android.view.inspector.WindowInspector.getGlobalWindowViews()
        }
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED)
            .flatMap { registry.getActivitiesInStage(it) }
            .mapNotNull { (it as Activity).window?.decorView }
    }

    private fun findBridge(tag: String): String? {
        val views = ArrayDeque(rootViews())
        while (views.isNotEmpty()) {
            val view = views.removeFirst()
            if (view is ViewGroup) for (i in 0 until view.childCount) view.getChildAt(i)?.let(views::add)
            if (view !is RootForTest) continue
            walk(view.semanticsOwner.unmergedRootSemanticsNode, tag)?.let { return it }
        }
        return null
    }

    private fun walk(node: SemanticsNode, tag: String): String? {
        val config = node.config
        if (config.getOrElseNullable(SemanticsProperties.TestTag) { null } == tag) {
            config.firstOrNull { it.key.name == StateBridge.KEY }?.let { return it.value as? String }
        }
        for (child in node.children) walk(child, tag)?.let { return it }
        return null
    }

}

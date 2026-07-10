package com.jsonui.testrunner.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// MARK: - Responsive condition (size-class gating resolved at runtime)

/**
 * Value of the `responsive` condition / case-level gate: a named size-class
 * bucket from the render-side canonical vocabulary, or an explicit size
 * constraint. Mirrors the web driver's `ResponsiveCondition`.
 *
 * Named buckets are resolved the way the Android renderer (kjui compose
 * codegen) resolves them — window width in dp from the actual window size
 * (the `LocalWindowInfo.containerSize` equivalent), NOT the deprecated
 * `Configuration.screenWidthDp`. See [matchesResponsive].
 */
@Serializable(with = ResponsiveConditionSerializer::class)
sealed class ResponsiveCondition {

    /**
     * Named bucket, e.g. `"regular"` or `"compact-landscape"`. The raw string
     * is kept so a bucket from a newer schema than this driver simply never
     * matches (fail-safe skip), instead of hard-failing at decode time.
     */
    data class Named(val value: String) : ResponsiveCondition()

    /**
     * Explicit size constraint; present keys are ANDed, min/max are inclusive.
     * Units are dp on Android (pt on iOS, logical px on web).
     */
    data class Constraint(
        val minWidth: Double? = null,
        val maxWidth: Double? = null,
        val minHeight: Double? = null,
        val maxHeight: Double? = null,
        /** "portrait" | "landscape" */
        val orientation: String? = null
    ) : ResponsiveCondition()

    /**
     * A JSON shape this driver cannot interpret (number, array, ...).
     * Never matches, so the gated step/case fail-safe skips.
     */
    object Unsupported : ResponsiveCondition() {
        override fun toString(): String = "Unsupported"
    }
}

/**
 * Decodes a `responsive` value: JSON string → [ResponsiveCondition.Named],
 * JSON object → [ResponsiveCondition.Constraint], anything else →
 * [ResponsiveCondition.Unsupported] (fail-safe: unmet, never a hard error).
 */
object ResponsiveConditionSerializer : KSerializer<ResponsiveCondition> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResponsiveCondition")

    override fun deserialize(decoder: Decoder): ResponsiveCondition {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalArgumentException("ResponsiveCondition can only be decoded from JSON")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive ->
                if (element.isString) ResponsiveCondition.Named(element.content)
                else ResponsiveCondition.Unsupported
            is JsonObject -> ResponsiveCondition.Constraint(
                minWidth = element.numberOrNull("minWidth"),
                maxWidth = element.numberOrNull("maxWidth"),
                minHeight = element.numberOrNull("minHeight"),
                maxHeight = element.numberOrNull("maxHeight"),
                orientation = (element["orientation"] as? JsonPrimitive)?.contentOrNull
            )
            else -> ResponsiveCondition.Unsupported
        }
    }

    override fun serialize(encoder: Encoder, value: ResponsiveCondition) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalArgumentException("ResponsiveCondition can only be encoded to JSON")
        when (value) {
            is ResponsiveCondition.Named -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is ResponsiveCondition.Constraint -> jsonEncoder.encodeJsonElement(buildJsonObject {
                value.minWidth?.let { put("minWidth", it) }
                value.maxWidth?.let { put("maxWidth", it) }
                value.minHeight?.let { put("minHeight", it) }
                value.maxHeight?.let { put("maxHeight", it) }
                value.orientation?.let { put("orientation", it) }
            })
            // Unsupported carries no decodable value; encode as null.
            ResponsiveCondition.Unsupported -> jsonEncoder.encodeJsonElement(JsonNull)
        }
    }

    private fun JsonObject.numberOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}

// MARK: - Runtime resolution (pure functions; unit-testable without a device)

/**
 * Named-bucket width thresholds in dp. Defaults mirror the Android renderer's
 * break rules (kjui `compose_builder.rb` INLINE_WIDTH_CONDITIONS: compact
 * < 600, medium 600..839, regular >= 840). Thresholds are overridable for
 * projects that also override the renderer's breakpoints; bucket NAMES are
 * fixed — config cannot add or rename buckets, otherwise CLI validation and
 * the runner would disagree on the vocabulary.
 */
data class ResponsiveThresholds(
    /** Minimum width in dp (inclusive) for the `medium` tier */
    val medium: Int = 600,
    /** Minimum width in dp (inclusive) for the `regular` tier */
    val regular: Int = 840
)

/** Current window size in dp (px window bounds ÷ density, truncated like the renderer) */
data class WindowDimensions(val width: Int, val height: Int)

/**
 * Landscape iff width > height — the kjui renderer's rule
 * (INLINE_LANDSCAPE_CONDITION: `containerSize.let { it.width > it.height }`).
 * Note a square window is NOT landscape here, mirroring the renderer.
 */
fun deriveOrientation(size: WindowDimensions): String =
    if (size.width > size.height) "landscape" else "portrait"

/** Exclusive width tier: >= regular → "regular", >= medium → "medium", else "compact" */
fun resolveSizeTier(width: Int, thresholds: ResponsiveThresholds): String = when {
    width >= thresholds.regular -> "regular"
    width >= thresholds.medium -> "medium"
    else -> "compact"
}

/**
 * Evaluate a `responsive` condition against the current window size.
 * - Named tier buckets match the resolved tier at any orientation.
 * - "landscape" matches orientation landscape at any tier.
 * - "<tier>-landscape" matches tier AND landscape.
 * - Constraint objects AND all present keys (min/max inclusive per the schema).
 * - An unrecognized named bucket / unsupported shape (newer schema than this
 *   driver) is UNMET, so the gated step/case fail-safe skips.
 */
fun matchesResponsive(
    condition: ResponsiveCondition,
    size: WindowDimensions,
    thresholds: ResponsiveThresholds
): Boolean {
    val orientation = deriveOrientation(size)

    return when (condition) {
        is ResponsiveCondition.Named -> {
            val tier = resolveSizeTier(size.width, thresholds)
            when (condition.value) {
                "compact", "medium", "regular" -> tier == condition.value
                "landscape" -> orientation == "landscape"
                "compact-landscape", "medium-landscape", "regular-landscape" ->
                    orientation == "landscape" && tier == condition.value.removeSuffix("-landscape")
                // Fail-safe: a bucket this driver does not know is unmet → skip
                else -> false
            }
        }
        is ResponsiveCondition.Constraint -> when {
            condition.minWidth != null && size.width < condition.minWidth -> false
            condition.maxWidth != null && size.width > condition.maxWidth -> false
            condition.minHeight != null && size.height < condition.minHeight -> false
            condition.maxHeight != null && size.height > condition.maxHeight -> false
            condition.orientation != null && condition.orientation != orientation -> false
            else -> true
        }
        ResponsiveCondition.Unsupported -> false
    }
}

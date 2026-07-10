package com.jsonui.testrunner.models

import kotlinx.serialization.KSerializer
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Decode-layer unknown-key awareness for [StepCondition].
 *
 * The loader's `Json { ignoreUnknownKeys = true }` would silently drop a
 * condition key this driver does not understand (i.e. one written against a
 * newer schema than this driver), and the condition would then evaluate on
 * the keys it *does* know — i.e. the step would run-anyway on every size.
 * That is forbidden: unknown condition keys must be fail-safe SKIP.
 *
 * This serializer reads the raw JsonObject, records every key outside the
 * known set (visible|notVisible|platform|state|responsive) in
 * [StepCondition.unknownKeys], then decodes the known fields normally. The
 * runner treats a condition with unknown keys as unmet → step skipped.
 */
object StepConditionSerializer : KSerializer<StepCondition> {

    /** Condition keys this driver can evaluate. Keep in sync with [StepCondition]. */
    private val KNOWN_KEYS = setOf("visible", "notVisible", "platform", "state", "responsive")

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StepCondition")

    override fun deserialize(decoder: Decoder): StepCondition {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalArgumentException("StepCondition can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Expected object for condition, got: $element")
        val json = jsonDecoder.json

        return StepCondition(
            visible = obj.stringOrNull("visible"),
            notVisible = obj.stringOrNull("notVisible"),
            platform = obj.nonNull("platform")?.let {
                json.decodeFromJsonElement(PlatformTargetSerializer, it)
            },
            state = obj.nonNull("state")?.let {
                json.decodeFromJsonElement(StateCondition.serializer(), it)
            },
            responsive = obj.nonNull("responsive")?.let {
                json.decodeFromJsonElement(ResponsiveConditionSerializer, it)
            },
            unknownKeys = obj.keys.filterNot { it in KNOWN_KEYS }
        )
    }

    override fun serialize(encoder: Encoder, value: StepCondition) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalArgumentException("StepCondition can only be encoded to JSON")
        // Unknown keys carry no decoded value, so only the known fields are re-emitted.
        val obj = buildJsonObject {
            value.visible?.let { put("visible", it) }
            value.notVisible?.let { put("notVisible", it) }
            value.platform?.let {
                put("platform", jsonEncoder.json.encodeToJsonElement(PlatformTargetSerializer, it))
            }
            value.state?.let {
                put("state", jsonEncoder.json.encodeToJsonElement(StateCondition.serializer(), it))
            }
            // Unsupported carries no decodable value; omit it (fail-safe shapes
            // do not round-trip, matching unknownKeys).
            value.responsive?.takeUnless { it is ResponsiveCondition.Unsupported }?.let {
                put("responsive", jsonEncoder.json.encodeToJsonElement(ResponsiveConditionSerializer, it))
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    private fun JsonObject.nonNull(key: String) = this[key]?.takeUnless { it is JsonNull }

    private fun JsonObject.stringOrNull(key: String): String? =
        (nonNull(key) as? JsonPrimitive)?.contentOrNull
}

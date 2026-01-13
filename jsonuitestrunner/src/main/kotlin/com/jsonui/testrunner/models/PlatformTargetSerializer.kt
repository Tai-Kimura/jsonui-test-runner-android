package com.jsonui.testrunner.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

object PlatformTargetSerializer : KSerializer<PlatformTarget> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PlatformTarget")

    override fun deserialize(decoder: Decoder): PlatformTarget {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()

        return when (element) {
            is JsonPrimitive -> PlatformTarget.Single(element.content)
            is JsonArray -> PlatformTarget.Multiple(element.map { it.jsonPrimitive.content })
            else -> throw IllegalArgumentException("Expected String or Array for PlatformTarget")
        }
    }

    override fun serialize(encoder: Encoder, value: PlatformTarget) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is PlatformTarget.Single -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is PlatformTarget.Multiple -> jsonEncoder.encodeJsonElement(
                JsonArray(value.values.map { JsonPrimitive(it) })
            )
        }
    }
}

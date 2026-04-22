package io.github.kikin81.atproto.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@PublishedApi
internal val recordDecoderJson: Json = Json { ignoreUnknownKeys = true }

@PublishedApi
internal val recordEncoderJson: Json = Json {
    explicitNulls = true
    ignoreUnknownKeys = true
}

public fun <T> JsonObject.decodeRecord(serializer: KSerializer<T>, json: Json = recordDecoderJson): T = json.decodeFromJsonElement(serializer, this)

public inline fun <reified T> JsonObject.decodeRecord(json: Json = recordDecoderJson): T = json.decodeFromJsonElement(this)

public fun <T> encodeRecord(serializer: KSerializer<T>, record: T, type: String, json: Json = recordEncoderJson): JsonObject {
    val obj = json.encodeToJsonElement(serializer, record).jsonObject
    return JsonObject(obj + ("\$type" to JsonPrimitive(type)))
}

public inline fun <reified T> encodeRecord(record: T, type: String, json: Json = recordEncoderJson): JsonObject {
    val obj = json.encodeToJsonElement(record).jsonObject
    return JsonObject(obj + ("\$type" to JsonPrimitive(type)))
}

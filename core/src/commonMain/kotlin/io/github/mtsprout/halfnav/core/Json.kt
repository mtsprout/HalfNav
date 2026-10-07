package io.github.mtsprout.halfnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// Lenient lookups over kotlinx.serialization's JSON tree, in the style of org.json's opt* calls:
// missing or mismatched fields give null (or the default) instead of throwing.

internal fun parseJsonObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** String value, or "" if missing or not a string (like org.json's optString). */
internal fun JsonObject.str(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

internal fun JsonObject.int(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.has(key: String): Boolean = this[key] != null && this[key] !is JsonNull

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

internal fun JsonArray?.strings(): List<String> =
    this?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

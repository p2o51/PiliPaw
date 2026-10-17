package com.gpo.pilipala.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON instance. Bilibili responses are wide and loosely typed, so every
 * @Serializable model must tolerate unknown keys, nulls and type drift.
 */
val BiliJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

// ── Loose accessors ─────────────────────────────────────────────────────────
// For quick parsing without data classes: `data.obj("owner").str("name")`.

val JsonElement?.asObj: JsonObject? get() = this as? JsonObject
val JsonElement?.asArr: JsonArray? get() = this as? JsonArray
val JsonElement?.asPrim: JsonPrimitive? get() = this as? JsonPrimitive

fun JsonElement?.asString(default: String = ""): String {
    val p = this as? JsonPrimitive ?: return default
    if (p is JsonNull) return default
    return p.content
}

fun JsonElement?.asLong(default: Long = 0L): Long {
    val p = this as? JsonPrimitive ?: return default
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull() ?: default
}

fun JsonElement?.asInt(default: Int = 0): Int {
    val p = this as? JsonPrimitive ?: return default
    return p.intOrNull ?: p.doubleOrNull?.toInt() ?: p.content.toIntOrNull() ?: default
}

fun JsonElement?.asDouble(default: Double = 0.0): Double {
    val p = this as? JsonPrimitive ?: return default
    return p.doubleOrNull ?: default
}

fun JsonElement?.asBool(default: Boolean = false): Boolean {
    val p = this as? JsonPrimitive ?: return default
    return p.booleanOrNull ?: p.intOrNull?.let { it != 0 } ?: default
}

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.arr(key: String): JsonArray = (this?.get(key) as? JsonArray) ?: JsonArray(emptyList())
fun JsonObject?.str(key: String, default: String = ""): String = this?.get(key).asString(default)
fun JsonObject?.long(key: String, default: Long = 0L): Long = this?.get(key).asLong(default)
fun JsonObject?.int(key: String, default: Int = 0): Int = this?.get(key).asInt(default)
fun JsonObject?.double(key: String, default: Double = 0.0): Double = this?.get(key).asDouble(default)
fun JsonObject?.bool(key: String, default: Boolean = false): Boolean = this?.get(key).asBool(default)
fun JsonObject?.has(key: String): Boolean = this?.get(key).let { it != null && it !is JsonNull }

/** Dotted path lookup: `root.path("data.item")`. Array indices are allowed: `"list.0.title"`. */
fun JsonElement?.path(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (seg in path.split('.')) {
        cur = when (cur) {
            is JsonObject -> cur[seg]
            is JsonArray -> seg.toIntOrNull()?.let { cur.getOrNull(it) }
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** Decode a JsonElement into a @Serializable type with the lenient [BiliJson]. */
inline fun <reified T> JsonElement.decode(): T = BiliJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), this)

inline fun <reified T> JsonElement?.decodeOrNull(): T? =
    this?.let { runCatching { BiliJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), it) }.getOrNull() }

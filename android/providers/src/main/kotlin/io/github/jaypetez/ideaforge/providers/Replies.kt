package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

// Request and reply plumbing the two adapters share.

private val JSON_TYPE = "application/json".toMediaType()

/**
 * A JSON body as `JSON.stringify` writes it. Built from bytes on purpose: the String overload
 * of toRequestBody appends `; charset=utf-8` to a media type without one, and OkHttp's bridge
 * then sends that as the Content-Type, which is not the header the web app sends.
 */
internal fun jsonBody(body: JsonElement): RequestBody =
    Json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8).toRequestBody(JSON_TYPE)

/**
 * A successful reply's JSON object. A body that is not JSON is the JS `res.json()` rejecting;
 * natively that is a `bad_response` (never retried) rather than an uncoded error.
 */
internal fun readJsonReply(text: String, label: String): JsonObject {
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return parsed as? JsonObject
        ?: throw ProviderException(ProviderErrorCode.BAD_RESPONSE, "$label returned a reply that is not a JSON object")
}

/** One element of a JS `Array.join`: `undefined` and `null` become empty, anything else String(). */
internal fun jsText(e: JsonElement?): String = if (e == null || e is JsonNull) "" else jsString(e)

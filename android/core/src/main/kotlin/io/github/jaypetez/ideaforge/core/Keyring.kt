package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsEq
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.spread
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The credential keyring: the pure half of src/store/secrets.js, replayed against the
 * `keyring` golden vectors.
 *
 * It lives in :core rather than :app because it is pure shape logic with no platform in it,
 * and because :core is where the vector harness and the JS-semantics helpers are. The
 * encryption is the platform half and lives in :app (data/KeyringStore.kt), exactly as the
 * web splits secrets.js's WebCrypto calls from these functions.
 *
 * A keyring is a JSON object, not a data class, for the same reason sessions are: the web
 * and Android must agree on the plaintext byte for byte, and the vectors compare JSON. Its
 * v2 shape is
 *
 *     { version: 2, active: kind|null, byKind: { [kind]: {apiKey, baseUrl, model, wrapModel} },
 *       stt: {kind|null, apiKey}, tts: {kind, apiKey, voice, verified} }
 *
 * v1 stored ONE record, so choosing another provider overwrote the previous provider's key.
 * v2 keeps a record per provider. Every function here accepts anything, because what it is
 * handed was decrypted from disk and may be from an older build or simply corrupt; the
 * answer to garbage is an empty keyring, never an exception, since an app that will not
 * start over a credential it could re-ask for is worse than one that asks.
 */
typealias Keyring = JsonObject

const val CREDENTIALS_VERSION = 2

/** JS `typeof v === 'string' ? v.trim() : ''`. */
private fun text(e: JsonElement?): String = stringOf(e)?.let(::jsTrim) ?: ""

/** JS `s || null` for a string. */
private fun orNull(s: String): JsonElement = if (s.isEmpty()) JsonNull else JsonPrimitive(s)

/** JS `v && typeof v === 'object'`: a non-null object, arrays included. */
private fun isObjectLike(e: JsonElement?): Boolean = e is JsonObject || e is JsonArray

/**
 * JS `Object.entries(v)` / `{...v}` for the values a keyring field can hold: an object's own
 * keys, an array's indices, a string's UTF-16 indices; nothing for anything else.
 */
private fun entries(e: JsonElement?): List<Pair<String, JsonElement>> = when (e) {
    is JsonObject -> e.entries.map { it.key to it.value }
    is JsonArray -> e.mapIndexed { i, v -> i.toString() to v }
    is JsonPrimitive -> stringOf(e)?.mapIndexed { i, c -> i.toString() to JsonPrimitive(c.toString()) }.orEmpty()
    else -> emptyList()
}

/** JS `v[key]` on an object or an array (by canonical index). */
private fun member(e: JsonElement?, key: String): JsonElement? = when (e) {
    is JsonObject -> e[key]
    is JsonArray -> key.toIntOrNull()?.takeIf { it >= 0 && it.toString() == key }?.let(e::getOrNull)
    else -> null
}

/** JS `Object.values(v)`. */
private fun values(e: JsonElement?): List<JsonElement> = entries(e).map { it.second }

/** One provider's record. `model` is the QUESTIONS model and keeps its old name on purpose. */
private fun record(r: JsonElement?): JsonObject = obj(
    "apiKey" to str(text(r["apiKey"])),
    "baseUrl" to str(text(r["baseUrl"])),
    "model" to str(text(r["model"])),
    "wrapModel" to str(text(r["wrapModel"])),
)

private fun sttRecord(kind: JsonElement?, apiKey: JsonElement?): JsonObject =
    obj("kind" to orNull(text(kind)), "apiKey" to str(text(apiKey)))

/**
 * Hosted speech: off ('browser') unless explicitly 'openai', and never `verified` without a
 * key to have verified.
 */
private fun speechRecord(value: JsonElement?): JsonObject {
    val apiKey = text(value["apiKey"])
    return obj(
        "kind" to str(if (jsEq(value["kind"], "openai")) "openai" else "browser"),
        "apiKey" to str(apiKey),
        "voice" to str(if (jsEq(value["voice"], "cedar")) "cedar" else "marin"),
        "verified" to JsonPrimitive(value["verified"] == JsonPrimitive(true) && apiKey.isNotEmpty()),
    )
}

fun emptyKeyring(): Keyring = obj(
    "version" to num(CREDENTIALS_VERSION),
    "active" to JsonNull,
    "byKind" to JsonObject(emptyMap()),
    "stt" to obj("kind" to JsonNull, "apiKey" to str("")),
    "tts" to speechRecord(null),
)

/**
 * Anything decrypted from disk, as a v2 keyring. Applied on read, as the web does: the next
 * save upgrades the blob at rest.
 */
fun migrateCredentials(raw: JsonElement?): Keyring {
    if (raw !is JsonObject) return emptyKeyring()
    if (jsEq(raw["version"], CREDENTIALS_VERSION)) return normalise(raw)

    // v1: {kind, apiKey, baseUrl, model, sttKind, sttKey}. No baseUrl is carried across: the
    // v1 Base URL field was unreachable, so no v1 blob can hold a meaningful one.
    var active: JsonElement = JsonNull
    val byKind = LinkedHashMap<String, JsonElement>()
    val kind = text(raw["kind"])
    if (kind.isNotEmpty()) {
        active = JsonPrimitive(kind)
        byKind[kind] = record(obj("apiKey" to raw["apiKey"], "model" to raw["model"]))
    }
    var ring = emptyKeyring().spread("active" to active, "byKind" to JsonObject(byKind))
    if (text(raw["sttKind"]).isNotEmpty()) ring = ring.spread("stt" to sttRecord(raw["sttKind"], raw["sttKey"]))
    return ring
}

private fun normalise(raw: JsonObject): Keyring {
    val byKind = LinkedHashMap<String, JsonElement>()
    val by = raw["byKind"]
    if (isObjectLike(by)) {
        for ((kind, rec) in entries(by)) if (isObjectLike(rec)) byKind[kind] = record(rec)
    }
    val stt = raw["stt"]
    val tts = raw["tts"]
    // Only the known fields survive: anything else a newer build wrote is dropped, as on the web.
    return obj(
        "version" to num(CREDENTIALS_VERSION),
        "active" to orNull(text(raw["active"])),
        "byKind" to JsonObject(byKind),
        "stt" to if (isObjectLike(stt)) sttRecord(stt["kind"], stt["apiKey"]) else sttRecord(null, null),
        "tts" to speechRecord(if (isObjectLike(tts)) tts else null),
    )
}

/** A v2 ring as given, or an empty one: the base the `with*` helpers build on. */
private fun base(ring: JsonElement?): JsonObject =
    if (ring is JsonObject && jsEq(ring["version"], CREDENTIALS_VERSION)) ring else emptyKeyring()

/** One provider's record, always complete, so no call site needs a guard. */
fun credsFor(ring: JsonElement?, kind: String): JsonObject {
    val r = member(ring["byKind"], kind).takeIf(::truthy)
    fun field(name: String): JsonElement = r[name].takeIf(::truthy) ?: str("")
    return obj(
        "kind" to str(kind),
        "apiKey" to field("apiKey"),
        "baseUrl" to field("baseUrl"),
        "model" to field("model"),
        "wrapModel" to field("wrapModel"),
    )
}

/** A new ring with one provider replaced and made active; every other record survives. */
fun withCreds(ring: JsonElement?, kind: String, rec: JsonElement?): Keyring {
    val b = base(ring)
    val byKind = LinkedHashMap<String, JsonElement>()
    for ((k, v) in entries(b["byKind"])) byKind[k] = v
    byKind[kind] = record(rec.takeIf(::truthy))
    return b.spread("active" to str(kind), "byKind" to JsonObject(byKind))
}

fun withStt(ring: JsonElement?, stt: JsonElement?): Keyring =
    base(ring).spread("stt" to sttRecord(stt["kind"], stt["apiKey"]))

/** Speech is an independent opt-in; choosing it must not change the interview provider. */
fun withTts(ring: JsonElement?, tts: JsonElement?): Keyring =
    base(ring).spread("tts" to speechRecord(tts))

/** True when the ring holds a secret at all: what Forget keys off. */
fun hasAnyKey(ring: JsonElement?): Boolean {
    if (!truthy(ring)) return false
    val stt = ring["stt"]
    if (truthy(stt) && truthy(stt["apiKey"])) return true
    val tts = ring["tts"]
    if (truthy(tts) && truthy(tts["apiKey"])) return true
    return values(ring["byKind"]).any { truthy(it) && truthy(it["apiKey"]) }
}

/** Never render a key in full; enough to recognise which one it is. */
fun maskKey(key: JsonElement?): String {
    val k = jsString(if (truthy(key)) key else str(""))
    if (k.length <= 12) return "••••"
    return k.substring(0, 6) + "…" + k.substring(k.length - 4)
}

fun maskKey(key: String?): String = maskKey(key?.let(::JsonPrimitive))

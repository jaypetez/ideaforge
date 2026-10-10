package io.github.jaypetez.ideaforge.core.vectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The golden vectors written by `npm run vectors` (tools/vectors.mjs), read straight off
 * disk. Gradle passes the directory in and declares it as a test input, so editing a vector
 * re-runs these tests instead of serving them from the build cache.
 */
object Vectors {
    val dir: File by lazy {
        val path = System.getProperty("ideaforge.vectors")
            ?: error("ideaforge.vectors is not set; run the tests through Gradle")
        File(path).also { require(it.isDirectory) { "no vector directory at $it" } }
    }

    fun families(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()

    fun load(family: String): Family {
        val doc = Json.parseToJsonElement(File(dir, "$family.json").readText()).jsonObject
        require(doc["vectorsVersion"]?.jsonPrimitive?.content == "1") { "$family: unknown vectorsVersion" }
        val fixtures = doc["fixtures"]?.jsonObject ?: JsonObject(emptyMap())
        val cases = doc["cases"]!!.jsonArray.map { raw ->
            val c = raw.jsonObject
            Case(
                name = c["name"]!!.jsonPrimitive.content,
                fn = c["fn"]!!.jsonPrimitive.content,
                args = c["args"]?.let { resolve(it, fixtures).jsonArray } ?: JsonArray(emptyList()),
                input = c["input"]?.let { resolve(it, fixtures) },
                script = c["script"],
                expect = c["expect"]!!.jsonObject,
            )
        }
        return Family(family, doc["source"]?.jsonPrimitive?.content.orEmpty(), cases)
    }

    /** Replace every `{"$ref": key}` with the fixture it names. */
    private fun resolve(e: JsonElement, fixtures: JsonObject): JsonElement = when (e) {
        is JsonObject -> {
            val ref = e["\$ref"]
            if (ref != null && e.size == 1) fixtures[ref.jsonPrimitive.content]
                ?: error("missing fixture ${ref.jsonPrimitive.content}")
            else JsonObject(e.mapValues { (_, v) -> resolve(v, fixtures) })
        }
        is JsonArray -> JsonArray(e.map { resolve(it, fixtures) })
        else -> e
    }
}

data class Family(val name: String, val source: String, val cases: List<Case>)

data class Case(
    val name: String,
    val fn: String,
    val args: JsonArray,
    val input: JsonElement?,
    val script: JsonElement?,
    val expect: JsonObject,
)

/** One ported JS function: takes the recorded call, returns what the JS returned. */
typealias Port = (Case) -> JsonElement

/**
 * What a family's Kotlin consumer can do today. `ported` maps a JS function name to its
 * Kotlin twin; `pending` maps the rest to the issue that will port them. A case whose
 * function appears in neither fails, so nothing is ever skipped silently.
 */
data class Consumer(
    val family: String,
    val ported: Map<String, Port>,
    val pending: Map<String, String>,
)

fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: ""

fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    // JS has one number type and prints integral values without a fraction; Kotlin's Double
    // would print 50.0 where the JS wrote 50, and JsonPrimitive compares the text.
    is Double -> if (value.isFinite() && value == Math.rint(value) && Math.abs(value) < 9.007199254740992E15)
        JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    is Float -> json(value.toDouble())
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> error("cannot encode ${value::class}")
}

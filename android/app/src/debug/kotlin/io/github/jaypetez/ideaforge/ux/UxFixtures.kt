package io.github.jaypetez.ideaforge.ux

import android.content.Context
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.migrate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class UxState(
    val id: String,
    val surface: String,
    val evidence: String,
    val phase: String?,
    val dataset: String?,
    val sessionId: String?,
)

/**
 * The shared reducer-built data, not another native seed or state inventory. Only the debug
 * variant copies this asset; neither it nor the loader belongs in a shipped source set.
 */
class UxFixtures(val json: JsonObject) {
    val now = json.getValue("now").jsonPrimitive.long
    val states: List<UxState> = (json.getValue("states") as JsonArray).map { s ->
        UxState(
            required(s, "id"), required(s, "surface"), required(s, "evidence"),
            stringOf(s["phase"]), stringOf(s["dataset"]), stringOf(s["sessionId"]),
        )
    }
    val text = json.getValue("text") as JsonObject
    val datasets: Map<String, List<Session>> = (json.getValue("datasets") as JsonObject).mapValues { (name, data) ->
        (data as JsonArray).map { raw ->
            val session = requireNotNull(migrate(raw)) { "UX dataset $name contains a rejected session" }
            require(session == raw) { "UX dataset $name changes when the native reducer migrates it" }
            session
        }
    }

    init {
        require(json["schema"]?.jsonPrimitive?.content == "1") { "unsupported UX fixture schema" }
        require(states.isNotEmpty() && states.map { it.id }.distinct().size == states.size) { "UX states must be nonempty and unique" }
        for (s in states) {
            require(Regex("[a-z0-9-]+").matches(s.id)) { "${s.id} is not a safe UX artifact id" }
            require(s.evidence in setOf("real-app", "isolated-component")) { "${s.id} has invalid evidence kind" }
            if (s.dataset != null) require(s.dataset in datasets) { "${s.id} names missing dataset ${s.dataset}" }
            if (s.sessionId != null) {
                require(sessions(s).any { stringOf(it["id"]) == s.sessionId }) { "${s.id} names missing session ${s.sessionId}" }
            }
        }
    }

    fun state(id: String): UxState = states.singleOrNull { it.id == id } ?: error("missing requested UX state: $id")
    fun sessions(state: UxState): List<Session> = state.dataset?.let { datasets.getValue(it) }.orEmpty()
    fun text(name: String): String = required(text, name)

    companion object {
        const val ASSET = "ux.json"
        fun load(context: Context): UxFixtures = parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        fun parse(text: String): UxFixtures = UxFixtures(Json.parseToJsonElement(text) as JsonObject)
        private fun required(json: JsonElement, key: String): String =
            stringOf(json[key])?.takeIf { it.isNotBlank() } ?: error("UX fixture is missing $key")
    }
}

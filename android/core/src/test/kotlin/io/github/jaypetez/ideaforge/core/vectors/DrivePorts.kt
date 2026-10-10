package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.runtime.DriveIo
import io.github.jaypetez.ideaforge.core.runtime.DriveLoop
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.core.runtime.DriveTurn
import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The scripted io tools/fixtures/vectors.mjs drove createDriveLoop with, line for line: each
 * outcome is consumed by one listen with the last repeating, `stopAfter: n` ends the run after
 * the nth listen, and the log records every effect and state change in order.
 */
private class ScriptedDrive(input: JsonObject) : DriveIo {
    val log = mutableListOf<JsonElement>()
    private val outcomes = input["outcomes"] as JsonArray
    private val turns = toNumber(input["turns"]).toInt()
    private val chips = (input["chips"] as? JsonArray)?.map { stringOf(it)!! } ?: emptyList()
    private val offerAt = input["offerAt"]?.let { toNumber(it).toInt() }
    private val draft = truthy(input["hasDraft"])
    private var i = 0
    private var answered = 0
    private var live = true
    private var listens = 0
    var runaway = false

    private fun entry(vararg parts: String) = JsonArray(parts.map(::json))

    override suspend fun speak(text: String) { log.add(entry("speak", text)) }
    override suspend fun listen(prompt: String, confirm: Boolean): String {
        val o = outcomes[minOf(i, outcomes.size - 1)]
        i += 1; listens += 1
        log.add(entry("listen"))
        if (listens > 60) { runaway = true; live = false; return "" }
        val stopAfter = o["stopAfter"]?.let { toNumber(it).toInt() }
        if (stopAfter != null && stopAfter != 0 && i >= stopAfter) live = false
        if (stringOf(o["kind"]) == "throw") {
            throw ListenFailure(
                stringOf(o["message"]).orEmpty(),
                fatal = o["fatal"]?.jsonPrimitive?.booleanOrNull,
                recoverable = o["recoverable"]?.jsonPrimitive?.booleanOrNull,
            )
        }
        return stringOf(o["text"]).orEmpty()
    }
    override fun openTurn(): DriveTurn? = if (answered < turns) DriveTurn(
        id = "t$answered", question = "Question $answered?",
        bridge = if (answered != 0) "Bridge $answered." else null, chips = chips,
    ) else null
    override suspend fun submit(text: String) { log.add(entry("submit", text)); answered += 1 }
    override suspend fun skip() { log.add(entry("skip")); answered += 1 }
    override suspend fun wrap() { log.add(entry("wrap")); live = false }
    override fun offerWrap(): String? = if (offerAt != null && answered >= offerAt) "coverage" else null
    override fun advisory(reason: String): String = "Advisory for $reason."
    override fun notify(message: String) { log.add(entry("notify", message)) }
    override fun running(): Boolean = live
    override fun answeredCount(): Int = answered
    override fun hasDraft(): Boolean = draft
    override fun onState(phase: DrivePhase) { log.add(entry("state", phase.wire)) }
    override suspend fun pause() { log.add(entry("pause")) }
    override suspend fun exit() { log.add(entry("exit")) }
    override suspend fun scratch() { log.add(entry("scratch")) }
}

/** The `drive-loop` family: a whole run, compared as {result, log}. */
val DRIVE_PORTS: Map<String, Port> = mapOf(
    "driveLoop" to { c ->
        val input = c.input as JsonObject
        val io = ScriptedDrive(input)
        val trigger = stringOf(input["config"]["trigger"])
        val result: JsonElement = try {
            json(runBlocking { DriveLoop(io, trigger).run() }.wire)
        } catch (e: IllegalStateException) {
            JsonObject(mapOf("throws" to json(e.message)))
        } catch (e: ListenFailure) {
            JsonObject(mapOf("throws" to json(e.message)))
        }
        check(!io.runaway) { "${c.name}: the scenario does not terminate" }
        JsonObject(mapOf("result" to result, "log" to JsonArray(io.log)))
    },
)

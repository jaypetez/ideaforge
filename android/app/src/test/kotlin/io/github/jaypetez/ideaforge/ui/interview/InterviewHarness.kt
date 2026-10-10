package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/** The walkthrough's scripted replies, read off the golden vector (one source of truth). */
val WALKTHROUGH_SCRIPT: JsonElement by lazy { Vectors.load("walkthrough").cases.first().script!! }

/**
 * The walkthrough model as a test provider: replies by `Turn number:`, the wrap-up on the
 * `complex` tier. [gate], when set, holds every turn call until it completes (a call in
 * flight); [fail] throws instead of answering (a provider outage).
 */
class ScriptedModel(
    private val script: JsonElement = WALKTHROUGH_SCRIPT,
    var gate: CompletableDeferred<Unit>? = null,
    var fail: ProviderException? = null,
) : Provider {
    val requests = mutableListOf<Pair<String, PromptParts>>()
    private val turns get() = script["turns"] as JsonObject

    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        requests += modelTier to parts
        fail?.let { throw it }
        if (modelTier == "complex") return SampleResult(script["synthesis"], modelTierApplied = modelTier)
        gate?.await()
        val n = Regex("Turn number: (\\d+)").find(parts.tail)?.groupValues?.get(1)
        val reply = n?.let { turns[it] } ?: throw ProviderException("bad_response", "no scripted reply")
        return SampleResult(reply, modelTierApplied = modelTier)
    }

    val turnPrompts: List<PromptParts> get() = requests.filter { it.first != "complex" }.map { it.second }
}

/**
 * Everything an InterviewViewModel needs, on one virtual-time dispatcher: the ViewModel's own
 * scope (Dispatchers.Main, which the test sets to [dispatcher]), the app scope the turns run
 * in, and the store's I/O. `advanceTimeBy` then moves the draft debounce exactly.
 */
class InterviewHarness(val dir: File, val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()) {
    val dispatcher = StandardTestDispatcher(scheduler)
    var store = SessionStore(dir, io = dispatcher)
    var worker = SessionWorker()
    var writes = CoroutineScope(SupervisorJob() + dispatcher)

    fun vm(
        provider: Provider?,
        id: String = "s_test_00000001",
        clock: Clock = Clock { 1_760_000_000_000 },
        source: ProviderSource = ProviderSource { provider ?: throw ProviderException("config", "no provider") },
    ) = InterviewViewModel(id, store, worker, clock, ring = { emptyKeyring() }, providers = source, writes = writes)

    /** A fresh process over the same files: new store, new worker, new app scope. */
    fun restart() {
        writes.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        store = SessionStore(dir, io = dispatcher)
        worker = SessionWorker()
        writes = CoroutineScope(SupervisorJob() + dispatcher)
    }
}

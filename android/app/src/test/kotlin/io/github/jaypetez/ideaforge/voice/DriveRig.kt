package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.data.testInferenceConsent
import io.github.jaypetez.ideaforge.data.testInferenceRing
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.interview.ScriptedModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Records what it was asked to say; says it in 200 ms of virtual time; [next] decides how it went. */
class FakeSpeaker : Speaker {
    val said = mutableListOf<String>()
    var cancels = 0
    var next: (String) -> SpeakResult = { SpeakResult(SpeakStatus.SPOKEN) }

    override suspend fun speak(text: String): SpeakResult {
        said += text
        delay(200)
        return next(text)
    }

    override fun cancel() { cancels++ }
    override fun shutdown() = Unit
}

class FakeService : DriveServiceControl {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

/** A capture that hears [text] (trigger included) as a final, with a live level. */
fun spoken(text: String): List<At> = listOf(
    At(300, Ev.Ready), At(400, Ev.Rms(4f)), At(600, Ev.Begin), At(1200, Ev.Partial(text, final = true)),
)

/**
 * A hands-free drive on one virtual clock: the real DriveController, Recognizer, DriveLoop,
 * SessionStore and SessionWorker, with a scripted recogniser engine, a fake speaker, a fake
 * service and the walkthrough model. Nothing touches a microphone, a speaker or the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveRig(dir: File, val model: ScriptedModel = ScriptedModel(), agreed: Boolean = true) {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)

    init { Dispatchers.setMain(dispatcher) }

    val store = SessionStore(dir, io = dispatcher)
    val worker = SessionWorker()
    /** Anything thrown out of a launched coroutine: in the app, an uncaught crash. */
    val crashes = mutableListOf<Throwable>()
    private val handler = CoroutineExceptionHandler { _, e -> crashes += e }
    val writes = CoroutineScope(SupervisorJob() + dispatcher + handler)
    val main = CoroutineScope(SupervisorJob() + dispatcher + handler)
    val scripts = ArrayDeque<List<At>>()
    val factory = FakeFactory { FakeRecognizerEngine(main, scripts) }
    var recognizerBuilt = false
    val recognizer: Recognizer by lazy { recognizerBuilt = true; Recognizer(factory, main) }
    val speaker = FakeSpeaker()
    /** SpeechRecognizer.isRecognitionAvailable, as the device answers it. */
    var recognitionAvailable = true
    /** The Whisper fallback the graph would build (null: no consent or no stt key). */
    var whisper: WhisperListener? = null
    var whisperAsked = 0
    val service = FakeService()
    val clock = Clock { 1_760_000_000_000 }
    val id = "s_drive_00000001"
    var ring = testInferenceRing()
    val inference = testInferenceConsent(writes, ring = { ring }, agreed = agreed, onRetire = ::retireInference)

    private fun retireInference(message: String) { main.launch { controller.blockForInference(message) } }

    val controller = DriveController(
        store, worker, clock,
        ring = { ring },
        providers = { ProviderSource { model } },
        writes = writes,
        main = main,
        recognizerSource = { recognizer },
        speaker = { speaker },
        service = service,
        inference = inference,
        recognitionAvailable = { recognitionAvailable },
        whisper = { whisperAsked++; whisper },
    )

    val seeded: Session = seedTurn(createSession(id, num(clock.now()), str("android")), num(clock.now()))

    val state get() = controller.state.value

    /** Another idea, never saved, whose answer box held [draft]. */
    fun unsaved(id: String, draft: String): Session =
        setDraftAnswer(seedTurn(createSession(id, num(clock.now()), str("android")), num(clock.now())), str(draft), num(clock.now()))

    /** The library's delete: retire the idea's work, then delete under its lock. */
    fun delete(id: String = this.id) {
        worker.retire(id)
        writes.launch { worker.exclusive(id) { store.delete(id) } }
    }

    fun engine(): FakeRecognizerEngine = factory.engines.single()
    fun starts(): Int = factory.engines.sumOf { it.starts }

    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** Advance virtual time until [cond] holds, or [maxMs] passes. */
    fun until(maxMs: Long = 120_000, step: Long = 50, cond: () -> Boolean): Boolean {
        var t = 0L
        while (t < maxMs) {
            if (cond()) return true
            advance(step)
            t += step
        }
        return cond()
    }

    fun saved(): Session? = store.get(id)
    fun turns(): List<JsonObject> = (saved()?.get("turns") as? JsonArray).orEmpty().map { it as JsonObject }
    fun answered(): List<JsonObject> = turns().filter { truthy(it["answer"]) }
    fun draft(): String? = stringOf(saved()?.get("draftAnswer"))

    fun close() {
        main.cancel()
        writes.cancel()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }
}

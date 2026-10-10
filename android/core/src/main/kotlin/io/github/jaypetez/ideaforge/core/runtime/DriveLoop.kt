package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.DEFAULT_TRIGGER
import io.github.jaypetez.ideaforge.core.js.JS_DOT_RUN
import io.github.jaypetez.ideaforge.core.js.jsRegexI
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.matchAffirmation
import io.github.jaypetez.ideaforge.core.parseSpeech
import io.github.jaypetez.ideaforge.core.spokenExamples
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlin.coroutines.cancellation.CancellationException

// The hands-free loop, ported from src/runtime/drive.js: read a question, listen, submit,
// repeat, for a whole commute. Replayed against the `drive-loop` golden vectors.
//
// Every effect is injected through DriveIo, so the loop's correctness, which is entirely
// about SEQUENCING, is testable with no microphone, no speaker and no clock.
//
// THE INVARIANT, stated so it is falsifiable:
//
//   Until the user pauses/exits, the interview wraps, or the loop explicitly stands down,
//   speaking, listening and processing are serialized. Transient failures recover without
//   waiting for a tap; cancellation never starts another effect.
//
// Cancellation replaces the JS AbortError. A CancellationException out of an effect (the UI
// cancelled the recogniser or the speech) stops the run, never recovers it. Cancelling the
// coroutine that called run() stops the run and propagates, as structured concurrency wants.
//
// stop() is the JS `halt`: synchronous, idempotent, and it invalidates progression without
// waiting for the effect in flight. That effect is NOT cancelled, matching the JS race: a
// submitted turn may still settle for its idea, and the UI owns media shutdown. Its late
// result or failure belongs to the retired run and is ignored.

/** What one question looks like to the loop: what is said, and what identifies it. */
data class DriveTurn(
    val id: String? = null,
    val question: String,
    val bridge: String? = null,
    val chips: List<String> = emptyList(),
)

/** How a run ended. [wire] is the JS string. */
enum class DriveResult(val wire: String) { STOPPED("stopped"), WRAPPED("wrapped"), DONE("done") }

/** The phase the loop announces before each effect. [wire] is the JS string. */
enum class DrivePhase(val wire: String) {
    SPEAKING("speaking"), LISTENING("listening"), PROCESSING("processing"),
    RECOVERING("recovering"), PAUSED("paused"), STOPPED("stopped"),
}

/**
 * A capture failure the voice layer can describe. [name] plays the JS `err.name` (a DOMException
 * such as NotAllowedError); [fatal] true or [recoverable] false stands the loop down at once
 * and rethrows. Any other throwable is judged by its class name and message.
 */
open class ListenFailure(
    message: String,
    val name: String = "Error",
    val fatal: Boolean? = null,
    val recoverable: Boolean? = null,
) : Exception(message)

/**
 * The injected effects. The optional ones default to what an absent JS callback does.
 * [listen] returns the RAW transcript, trigger included.
 */
interface DriveIo {
    suspend fun speak(text: String)
    suspend fun listen(prompt: String, confirm: Boolean): String
    fun openTurn(): DriveTurn?
    suspend fun submit(text: String)
    suspend fun skip()
    suspend fun wrap()
    fun offerWrap(): String?
    fun advisory(reason: String): String?
    fun notify(message: String)
    fun running(): Boolean
    /** Session history's answered count, for wrap eligibility on a resumed run; null means
     *  "not provided" and the loop counts its own answers. */
    fun answeredCount(): Int? = null
    fun hasDraft(): Boolean = false
    fun onState(phase: DrivePhase) {}
    suspend fun pause() {}
    suspend fun exit() {}
    suspend fun scratch() {}
}

/**
 * Errors where trying again is noise rather than resilience. A denied permission fails the
 * same way every time, and re-asking a driver to grant it is worse than standing down.
 */
private val RE_FATAL = jsRegexI(
    "denied|not[- ]?allowed|no microphone|microphone$JS_DOT_RUN*(?:in use|unavailable)",
)

/** Consecutive questions given up before we conclude nobody is there. */
private const val MAX_BLIND_SKIPS = 2
/** Below this many answers the export is worthless, so a misheard "wrap it up" is refused. */
private const val MIN_TURNS_TO_WRAP = 4
/** Times to re-ask an unclear yes/no before carrying on regardless. */
private const val CONFIRM_TRIES = 2

/** What the loop says. Copied from drive.js's SAY, typography and all. */
internal object Say {
    const val MISS = "I didn’t catch that. Take your time, and say “%s” when you’re done."
    const val AGAIN = "Let me read that again."
    const val GIVE_UP_ONE = "I’ll come back to that one."
    const val KEPT_DRAFT = "Your draft is still here. I have paused listening rather than skip those words. Resume when you are ready."
    const val STAND_DOWN = "I can’t hear you, so I’ve switched hands-free off. Tap the microphone when you’re ready."
    const val LOST_MIC = "I’ve lost the microphone, so I’ve switched hands-free off."
    const val SCRATCHED = "Scratched — go again."
    const val TOO_EARLY = "We’ve only just started — a few more questions first."
    const val CONFIRM = "Shall I write it up? Say yes, or say keep going."
    const val UNCLEAR = "Sorry — say yes to write it up, or say keep going."
    const val CARRY_ON = "Right — carrying on."
}

/** The JS `CANCELLED` symbol: progression was invalidated, so nothing further may start. */
private class DriveCancelled : CancellationException("drive stopped")

/**
 * JS `str.replace('%s', replacement)`: the first occurrence, with the replacement's `$$`,
 * `$&`, `` $` `` and `$'` patterns honoured as JS honours them for a string pattern.
 */
private fun jsReplaceFirst(s: String, pattern: String, replacement: String): String {
    val at = s.indexOf(pattern)
    if (at < 0) return s
    val out = StringBuilder()
    var i = 0
    while (i < replacement.length) {
        val c = replacement[i]
        val next = replacement.getOrNull(i + 1)
        if (c == '$' && next != null && next in "$&`'") {
            when (next) {
                '$' -> out.append('$')
                '&' -> out.append(pattern)
                '`' -> out.append(s, 0, at)
                '\'' -> out.append(s, at + pattern.length, s.length)
            }
            i += 2
        } else {
            out.append(c); i++
        }
    }
    return s.substring(0, at) + out + s.substring(at + pattern.length)
}

/**
 * One instance owns one run; concurrent or repeated [run] calls share its outcome, and Resume
 * deliberately creates a new instance. State notifications are synchronous, deduplicated, and
 * precede effects. Pause/exit latch paused/stopped before awaiting their callbacks, then
 * return STOPPED; an external [stop] calls neither. Scratch is awaited before relistening.
 */
class DriveLoop(private val io: DriveIo, trigger: String? = null) {
    private val trigger: String = trigger ?: DEFAULT_TRIGGER

    private val lock = Any()
    @Volatile private var stopped = false
    private var phase: DrivePhase? = null
    private var outcome: CompletableDeferred<DriveResult>? = null
    private val stopSignal: CompletableJob = Job()
    private lateinit var effects: CoroutineScope

    private var misses = 0
    private var blindSkips = 0
    private var offered = false
    private var answers = 0

    /**
     * Which question has already been read out, so it is not read twice. Keyed by id rather
     * than identity: every session reducer returns a new object even while the question has
     * not changed.
     */
    private var spokenFor: String? = null
    private fun keyOf(turn: DriveTurn?): String? =
        turn?.id?.takeIf { it.isNotEmpty() } ?: turn?.question?.takeIf { it.isNotEmpty() }

    private fun live() = !stopped && io.running()

    private fun checkRunning() {
        if (!live()) throw DriveCancelled()
    }

    private fun setState(next: DrivePhase) {
        if (phase == next) return
        phase = next
        io.onState(next)
    }

    private fun halt(next: DrivePhase = DrivePhase.STOPPED) {
        synchronized(lock) {
            if (stopped) return
            stopped = true
        }
        stopSignal.complete()
        setState(next)
    }

    /** Stand down: progression is invalidated now, without waiting for the effect in flight. */
    fun stop() = halt()

    /**
     * Race only progression, not the effect itself: the effect runs undispatched (so its
     * first synchronous steps, notify before speak, happen in order) in a supervised child of
     * the run, and the loop stops waiting for it the moment [stop] is called.
     */
    private suspend fun <T> perform(next: DrivePhase, action: suspend () -> T): T {
        checkRunning()
        setState(next)
        checkRunning()
        val effect = effects.async(start = CoroutineStart.UNDISPATCHED) { action() }
        select<Unit> {
            effect.onJoin {}
            stopSignal.onJoin {}
        }
        if (stopped) throw DriveCancelled()          // late outcomes belong to the retired run
        return effect.await()
    }

    private suspend fun say(text: String?) {
        if (text.isNullOrEmpty()) return
        perform(DrivePhase.SPEAKING) {
            io.notify(text)
            checkRunning()
            io.speak(text)
        }
    }

    private suspend fun stopByCommand(kind: String): DriveResult {
        checkRunning()
        halt(if (kind == "pause") DrivePhase.PAUSED else DrivePhase.STOPPED)
        if (kind == "pause") io.pause() else io.exit()
        return DriveResult.STOPPED
    }

    private suspend fun clearDraft() = perform(DrivePhase.PROCESSING) { io.scratch() }

    private suspend fun handleListenError(err: Throwable) {
        checkRunning()
        if (err is CancellationException) throw err
        if (err is ListenFailure && (err.fatal == true || err.recoverable == false)) throw err
        val name = (err as? ListenFailure)?.name ?: err::class.simpleName.orEmpty()
        if (RE_FATAL.containsMatchIn("$name ${err.message.orEmpty()}")) {
            say(Say.LOST_MIC)
            throw DriveCancelled()
        }
        setState(DrivePhase.RECOVERING)
        checkRunning()
    }

    /** What gets read aloud: the bridge and the question. The chips follow, separately. */
    private fun spoken(turn: DriveTurn) =
        if (!turn.bridge.isNullOrEmpty()) "${turn.bridge} ${turn.question}" else turn.question

    /**
     * The wrap offer, asked rather than announced. Two unclear answers means carry on:
     * wrapping cannot be undone by voice, so a guess is the one mistake with no recovery.
     */
    private suspend fun askToWrap(reason: String): Boolean {
        checkRunning()
        offered = true
        say(listOfNotNull(io.advisory(reason)?.takeIf { it.isNotEmpty() }, Say.CONFIRM).joinToString(" "))

        var i = 0
        while (i < CONFIRM_TRIES && live()) {
            val heard = try {
                perform(DrivePhase.LISTENING) { io.listen(Say.CONFIRM, confirm = true) }
            } catch (err: Throwable) {
                handleListenError(err)
                return false                          // a failed confirm is not a yes
            }
            checkRunning()
            val said = parseSpeech(heard, trigger)
            if (said.kind == "pause" || said.kind == "exit") {
                stopByCommand(said.kind)
                return false
            }
            if (said.kind == "scratch") clearDraft()
            if (said.kind == "wrap") return true
            when (matchAffirmation(said.text)) {
                true -> return true
                false -> { say(Say.CARRY_ON); return false }
                null -> if (i < CONFIRM_TRIES - 1) say(Say.UNCLEAR)
            }
            i++
        }
        return false
    }

    /**
     * Nothing usable was captured: re-prompt, then re-read the question, then give that
     * question up. Only after several questions in a row go that way does the loop stand down.
     *
     * @return false to stand down
     */
    private suspend fun recoverFromMiss(): Boolean {
        checkRunning()
        setState(DrivePhase.RECOVERING)
        checkRunning()
        misses += 1

        if (misses == 1) {
            say(jsReplaceFirst(Say.MISS, "%s", trigger))
            return true                               // re-listen; do NOT re-read the question
        }
        if (misses == 2) {
            say(Say.AGAIN)
            spokenFor = null                          // they may simply never have heard it
            return true
        }

        if (io.hasDraft()) {
            say(Say.KEPT_DRAFT)
            return false
        }
        misses = 0
        blindSkips += 1
        say(Say.GIVE_UP_ONE)
        perform(DrivePhase.PROCESSING) { io.skip() }
        answers += 1
        spokenFor = null

        if (blindSkips >= MAX_BLIND_SKIPS) {
            say(Say.STAND_DOWN)
            return false
        }
        return true
    }

    private suspend fun drive(): DriveResult {
        while (live()) {
            val turn = io.openTurn()
            checkRunning()
            if (turn == null) return DriveResult.DONE

            // Asked before the next question is read, or the offer arrives buried under it.
            if (!offered && answers >= 1) {
                val reason = io.offerWrap()
                if (!reason.isNullOrEmpty()) {
                    if (askToWrap(reason)) {
                        perform(DrivePhase.PROCESSING) { io.wrap() }
                        return DriveResult.WRAPPED
                    }
                    if (!live()) break
                }
            }

            if (spokenFor != keyOf(turn)) {
                say(spoken(turn))
                // Two utterances, not one longer string, and that is load-bearing twice over:
                // a question plus four fourteen-word chips outlasts the speech watchdog on its
                // own, and a beat between the question and its examples is how a person says it.
                if (live()) say(spokenExamples(turn.chips))
                spokenFor = keyOf(turn)
            }
            if (!live()) break

            val heard = try {
                perform(DrivePhase.LISTENING) { io.listen(turn.question, confirm = false) }
            } catch (err: Throwable) {
                handleListenError(err)
                // Anything else was already retried inside the provider's own backoff, so a
                // second attempt here would be a third. Treat it as a miss.
                if (!recoverFromMiss()) return DriveResult.STOPPED
                continue
            }
            if (!live()) break

            val said = parseSpeech(heard, trigger)

            if (said.kind == "pause" || said.kind == "exit") return stopByCommand(said.kind)
            if (said.kind == "answer" && said.stopped && said.text.isBlankJs() && io.hasDraft()) {
                perform(DrivePhase.PROCESSING) { io.submit("") }
                answers += 1
                misses = 0; blindSkips = 0
                spokenFor = null
                continue
            }
            if (said.kind == "answer" && said.text.isBlankJs()) {
                if (!recoverFromMiss()) return DriveResult.STOPPED
                continue
            }
            misses = 0
            blindSkips = 0

            if (said.kind == "repeat") { spokenFor = null; continue }
            if (said.kind == "scratch") {
                clearDraft()
                say(Say.SCRATCHED)
                continue
            }

            if (said.kind == "skip") {
                perform(DrivePhase.PROCESSING) { io.skip() }
                answers += 1
                spokenFor = null
                continue
            }

            if (said.kind == "wrap") {
                // A misheard command must not end an interview that has nothing in it yet.
                val count = io.answeredCount() ?: answers
                check(count >= 0) { "The answered turn count must be a nonnegative integer." }
                if (count < MIN_TURNS_TO_WRAP) { say(Say.TOO_EARLY); spokenFor = null; continue }
                perform(DrivePhase.PROCESSING) { io.wrap() }
                return DriveResult.WRAPPED
            }

            perform(DrivePhase.PROCESSING) { io.submit(said.text) }
            answers += 1
            spokenFor = null
        }
        return DriveResult.STOPPED
    }

    /**
     * Drive until done, wrapped or stopped. The first caller runs the loop in its own
     * coroutine; any other caller awaits the same outcome. A non-cancellation failure (an
     * explicitly fatal capture, a failed submit) is rethrown after announcing STOPPED.
     */
    suspend fun run(): DriveResult {
        val mine = CompletableDeferred<DriveResult>()
        val shared = synchronized(lock) { outcome ?: mine.also { outcome = it } }
        if (shared !== mine) return shared.await()

        val caller = currentCoroutineContext()[Job]
        val supervisor = SupervisorJob(caller)
        effects = CoroutineScope(currentCoroutineContext() + supervisor)
        val result = try {
            Result.success(drive())
        } catch (e: CancellationException) {
            // Our own coroutine was cancelled: stand down, then let cancellation propagate.
            if (caller?.isActive == false) Result.failure(e) else Result.success(DriveResult.STOPPED)
        } catch (e: Throwable) {
            Result.failure(e)
        }
        halt()
        supervisor.complete()
        val failure = result.exceptionOrNull()
        if (failure == null || failure is CancellationException) mine.complete(result.getOrDefault(DriveResult.STOPPED))
        else mine.completeExceptionally(failure)
        return result.getOrThrow()
    }
}

/** JS `!text.trim()`. */
private fun String.isBlankJs() = jsTrim(this).isEmpty()

package io.github.jaypetez.ideaforge.ui.interview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.core.HARD_TURN_CEILING
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.archiveSession
import io.github.jaypetez.ideaforge.core.buildTurnPrompt
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.lastAnswerClass
import io.github.jaypetez.ideaforge.core.legalMoves
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.restoreSession
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.TurnResult
import io.github.jaypetez.ideaforge.core.runtime.resumeSynthesis
import io.github.jaypetez.ideaforge.core.runtime.resumeTurn
import io.github.jaypetez.ideaforge.core.runtime.runSynthesis
import io.github.jaypetez.ideaforge.core.runtime.runTurn
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.selectNextDimension
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.core.setPending
import io.github.jaypetez.ideaforge.core.setSessionTags
import io.github.jaypetez.ideaforge.core.setStatusField
import io.github.jaypetez.ideaforge.core.setWrapOffered
import io.github.jaypetez.ideaforge.core.skipQuestion
import io.github.jaypetez.ideaforge.core.wrapAdvisory
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The web's queueDraftSave timer (src/ui/app.js): one write per pause in typing. */
const val DRAFT_DEBOUNCE_MS = 600L

/** What the interview is waiting for, for the loading line. */
enum class Busy { Thinking, Resuming, Writing }

/** Something to tell the user once, as a snackbar. [detail] is the underlying error text. */
sealed interface InterviewNotice {
    val detail: String

    /** The saved provider could not be built; questions come from the checklist. */
    data class NoProvider(override val detail: String) : InterviewNotice

    /** The model call failed; this one question came from the checklist. */
    data class Fallback(override val detail: String) : InterviewNotice

    /** Saving or running the turn threw. */
    data class Failed(override val detail: String) : InterviewNotice
}

data class InterviewState(
    /** Null until the stored idea has been read (or a new one seeded). */
    val session: Session? = null,
    val answer: String = "",
    /** `typed` or `chip`: what submitAnswer is told. An unedited chip is the model's words. */
    val answerSource: String = "typed",
    val busy: Busy? = null,
    /** The engine's wrap advisory, while the offer is on screen. */
    val wrapOffer: String? = null,
    val notice: InterviewNotice? = null,
    /** The write-up has settled: show the result. */
    val finished: Boolean = false,
    /** The write-up behind [finished] failed; the result screen says so (ResultRoute). */
    val writeUpFailed: Boolean = false,
) {
    val openTurn: JsonObject? get() = session?.let(::openTurn)

    /** The open question came from the static bank, not the model: the web's turnline suffix. */
    val degraded: Boolean get() = stringOf(openTurn?.get("questionSource")) == "bank"

    val turnCount: Int get() = (session?.get("turns") as? JsonArray)?.size ?: 0

    /** The web shows "That's enough — write it up" from the fourth question on. */
    val canWriteUp: Boolean get() = session != null && turnCount >= 4 && busy == null

    val canAnswer: Boolean get() = openTurn != null && busy == null
}

/**
 * One idea's interview: the Kotlin form of the interview half of src/ui/app.js
 * (startInterview, resumeInterview, submitAndAdvance, doSkip, nextQuestion, wrapUp).
 *
 * Every model call and every write goes through [worker] tagged with this idea's id and the
 * epoch at scheduling time, so a result that arrives after the idea was deleted (or another
 * epoch began) is dropped rather than written over whatever the idea has become. The work
 * runs in [writes], the app scope: leaving the screen mid-turn does not throw away a call
 * already paid for, and its result is saved for when the idea is reopened, as on the web.
 *
 * `runTurn` bumps `rev` five or six times; the session it returns is saved once.
 */
class InterviewViewModel(
    private val sessionId: String,
    private val store: SessionStore,
    private val worker: SessionWorker,
    private val clock: Clock,
    private val ring: () -> JsonElement,
    private val providers: ProviderSource,
    private val writes: CoroutineScope,
    private val draftDelayMs: Long = DRAFT_DEBOUNCE_MS,
) : ViewModel() {
    private val _state = MutableStateFlow(InterviewState())
    val state: StateFlow<InterviewState> = _state.asStateFlow()

    private var provider: Provider? = null
    private var draftJob: Job? = null

    /**
     * Text queued for a draft write and not yet saved. The debounce job cannot answer this:
     * viewModelScope is cancelled before onCleared runs, so a flush that asked whether the job
     * was still active always found it dead and dropped a draft typed just before leaving.
     */
    @Volatile
    private var pendingDraft: String? = null

    private fun now(): JsonElement = num(clock.now())

    init {
        viewModelScope.launch { open() }
    }

    // ─────────────────────────────────────────────────────────── opening

    private suspend fun open() {
        // The lock waits out work still running for this idea (a turn begun before the screen
        // was last left), so what is read here is what that work saved: the web's
        // resumeInterview awaiting workBySession before it loads.
        val stored = try {
            worker.exclusive(sessionId) {
                store.load()
                store.get(sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = InterviewNotice.Failed(message(e))) }
            return
        }
        provider = try {
            providers.build(ring())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = InterviewNotice.NoProvider(message(e))) }
            null
        }

        if (stored == null) {
            // Turn 1 is hardcoded: no model call, so it paints at once and cannot fail. Nothing
            // is written until there is something to keep (an answer or a draft), so a New idea
            // tapped and abandoned leaves no empty idea in the library.
            val now = now()
            _state.update { it.copy(session = seedTurn(createSession(sessionId, now, str(DEVICE)), now)) }
            return
        }

        val draft = stringOf(stored["draftAnswer"]).orEmpty()
        val chips = (openTurn(stored)?.get("chips") as? JsonArray).orEmpty().mapNotNull(::stringOf)
        _state.update {
            it.copy(session = stored, answer = draft, answerSource = if (draft.isNotEmpty() && draft in chips) "chip" else "typed")
        }
        val pending = stored["pending"]
        when {
            truthy(pending) && stringOf(pending["kind"]) == "synthesis" -> writeUp(resume = true)
            stringOf(stored["status"]) == "done" && openTurn(stored) == null -> _state.update { it.copy(finished = true) }
            truthy(pending) && stringOf(pending["kind"]) == "turn" -> nextQuestion(stored, resume = true)
            openTurn(stored) == null -> nextQuestion(stored, resume = false)
        }
    }

    // ─────────────────────────────────────────────────────────── answering

    /** The answer box changed. Editing a chip makes the words the user's again. */
    fun onAnswerChange(text: String) {
        val st = _state.value
        if (text == st.answer) return
        _state.update { it.copy(answer = text, answerSource = if (it.answerSource == "chip") "typed" else it.answerSource) }
        queueDraft(text)
    }

    /**
     * A tapped chip drops into the box as editable text. It is the model's wording, not the
     * user's, and the ratchet caps it at `partial` for exactly that reason, unless it is edited.
     */
    fun onChip(text: String) {
        _state.update { it.copy(answer = text, answerSource = "chip") }
        queueDraft(text)
    }

    fun send() {
        val st = _state.value
        val s = st.session ?: return
        if (!st.canAnswer) return
        val body = st.answer.trim()
        if (body.isEmpty()) return
        cancelDraft()
        advance(submitAnswer(s, str(body), st.answerSource, now()))
    }

    fun skip() {
        val st = _state.value
        val s = st.session ?: return
        if (!st.canAnswer) return
        cancelDraft()
        advance(skipQuestion(s, now()))
    }

    private fun advance(answered: Session) {
        _state.update { it.copy(session = answered, answer = "", answerSource = "typed", wrapOffer = null) }
        // Started here, synchronously, not from inside a viewModelScope coroutine: Back right
        // after Send clears the ViewModel, and a turn still queued on its scope would be
        // cancelled before it ran, losing the answer with it.
        val turn = startTurn(answered, resume = false)
        viewModelScope.launch { finishTurn(turn) }
    }

    /** Ask the next question: the web's nextQuestion, or resumeTurn for an interrupted one. */
    private suspend fun nextQuestion(session: Session, resume: Boolean) = finishTurn(startTurn(session, resume))

    /** Starts the turn's work in the app scope, where it outlives this screen. */
    private fun startTurn(session: Session, resume: Boolean): Deferred<SessionWorker.Outcome<SettledTurn>> {
        _state.update { it.copy(busy = if (resume) Busy.Resuming else Busy.Thinking) }
        val provider = provider
        val tag = worker.tag(sessionId)
        return writes.async {
            worker.run(
                tag,
                work = {
                    val base = withLibraryMetadata(session, store.get(sessionId), now())
                    val out = if (resume) {
                        resumeTurn(base, provider, now())
                    } else {
                        // The answer, with a marker saying a turn is in flight and which
                        // prompt it sent. If the process dies before the reply, reopening
                        // finds the marker and re-issues the byte-identical prompt.
                        store.save(if (provider != null) withPendingTurn(base, now()) else base)
                        runTurn(base, provider, now())
                    }
                    settle(out)
                },
                commit = { (_, settled, _) -> store.save(settled) },
            )
        }
    }

    private suspend fun finishTurn(turn: Deferred<SessionWorker.Outcome<SettledTurn>>) {
        val outcome = try {
            turn.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, notice = InterviewNotice.Failed(message(e))) }
            return
        }
        val (out, settled, advisory) = (outcome as? SessionWorker.Outcome.Committed)?.value ?: run {
            _state.update { it.copy(busy = null) }
            return
        }
        _state.update {
            it.copy(
                session = settled,
                busy = null,
                wrapOffer = advisory,
                notice = out.error?.let { e -> InterviewNotice.Fallback(message(e)) } ?: it.notice,
            )
        }
        // Nothing left worth asking (coverage, the hard ceiling, an exhausted bank): write it up.
        if (out.turn == null && !out.aborted) writeUp()
    }

    /**
     * What a turn's result becomes on disk, and the advisory to show: the offer is recorded
     * (setWrapOffered) so it is made once, as the web's nextQuestion does.
     */
    private fun settle(out: TurnResult): SettledTurn {
        val advisory = if (out.aborted) null else wrapAdvisory(out.session, out.wrap)
        val settled = if (advisory != null) setWrapOffered(out.session, true, now()) else out.session
        return Triple(out, settled, advisory)
    }

    // ─────────────────────────────────────────────────────────── wrapping up

    /** Not now: the offer goes away and is not made again (wrapOffered is already saved). */
    fun declineWrap() = _state.update { it.copy(wrapOffer = null) }

    /** "Write it up", from the composer or the wrap offer. */
    fun writeUp() {
        val st = _state.value
        if (st.session == null || st.busy != null) return
        writeUp(resume = false)
    }

    /**
     * Like a turn, the write-up starts in the app scope synchronously, so leaving the screen
     * the moment it is tapped cannot cancel it before it runs; only the UI update afterwards
     * belongs to viewModelScope.
     */
    private fun writeUp(resume: Boolean) {
        val session = _state.value.session ?: return
        cancelDraft()
        _state.update { it.copy(busy = Busy.Writing, wrapOffer = null) }
        val provider = provider
        val tag = worker.tag(sessionId)
        val job = writes.async {
            worker.run(
                tag,
                work = {
                    val base = withLibraryMetadata(session, store.get(sessionId), now())
                    val result = if (resume) resumeSynthesis(base, provider, now()) else runSynthesis(base, provider, now())
                    // A failed write-up still finishes the interview: the export renders the
                    // transcript, the open questions and the coverage table without it.
                    val settled = if (result.ok || result.aborted) result.session else setStatusField(result.session, "done", now())
                    result to settled
                },
                commit = { (_, settled) -> store.save(settled) },
            )
        }
        viewModelScope.launch {
            val outcome = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, notice = InterviewNotice.Failed(message(e))) }
                return@launch
            }
            val (result, settled) = (outcome as? SessionWorker.Outcome.Committed)?.value ?: run {
                _state.update { it.copy(busy = null) }
                return@launch
            }
            _state.update {
                it.copy(
                    session = settled,
                    busy = null,
                    finished = !result.aborted,
                    writeUpFailed = !result.ok && !result.aborted,
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────── the draft

    // The answer-box draft is the exception to settled-turn persistence: written while the
    // question is open, debounced so a keystroke is never a write, and the queued write is
    // cancelled before submit or skip so an old timer cannot put answered text back into the
    // next question.

    private fun queueDraft(text: String) {
        draftJob?.cancel()
        if (_state.value.openTurn == null) return
        pendingDraft = text
        draftJob = viewModelScope.launch {
            delay(draftDelayMs)
            writeDraft(text)
        }
    }

    private fun cancelDraft() {
        draftJob?.cancel()
        draftJob = null
        pendingDraft = null
    }

    private suspend fun writeDraft(text: String) {
        val s = _state.value.session ?: return
        if (openTurn(s) == null || stringOf(s["draftAnswer"]).orEmpty() == text) {
            if (pendingDraft == text) pendingDraft = null
            return
        }
        val drafted = setDraftAnswer(s, str(text), now())
        _state.update { it.copy(session = drafted) }
        try {
            val out = worker.run(worker.tag(sessionId), work = { store.save(drafted) })
            if (out is SessionWorker.Outcome.Committed && pendingDraft == text) pendingDraft = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = InterviewNotice.Failed(message(e))) }
        }
    }

    /**
     * The screen is going away (stopped, or this entry popped): write the queued draft now.
     * In the app scope and NonCancellable, because from onCleared viewModelScope is already
     * dead, and through writeDraft so it takes the session's lock like every other write.
     */
    fun flushDraft() {
        val text = pendingDraft ?: return
        draftJob?.cancel()
        draftJob = null
        writes.launch { withContext(NonCancellable) { runCatching { writeDraft(text) } } }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun finishedShown() = _state.update { it.copy(finished = false) }

    override fun onCleared() {
        flushDraft()
    }

    companion object {
        /** `meta.device`, in the web's vocabulary (src/ui/install.js mobilePlatform). */
        const val DEVICE = "android"

        fun factory(sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as IdeaForgeApp).graph
                InterviewViewModel(
                    sessionId, graph.sessionStore, graph.sessionWorker, graph.clock,
                    ring = { graph.keyringStore.state.value.ring },
                    providers = graph.providerSource,
                    writes = graph.appScope,
                )
            }
        }
    }
}

/** A turn's outcome, the session to save for it, and the wrap advisory to show (or null). */
private typealias SettledTurn = Triple<TurnResult, Session, String?>

private fun message(e: Throwable): String = e.message?.takeIf { it.isNotEmpty() } ?: e.toString()

/**
 * The pending marker runTurn sets internally before its call, made durable: the same
 * `{kind, turnId, promptHash, startedAt}`, over the same prompt runTurn is about to build.
 * resumeTurn checks the hash against the prompt it recomputes, so a resumed turn that would
 * send different bytes says so instead of silently missing the provider's cache.
 */
internal fun withPendingTurn(session: Session, now: JsonElement): Session {
    val turns = (session["turns"] as? JsonArray)?.size ?: 0
    if (turns >= HARD_TURN_CEILING) return session
    val target = selectNextDimension(session) ?: return session
    val moves = legalMoves(session, target, lastAnswerClass(session))
    val hash = promptHash(buildTurnPrompt(session, target, moves))
    return setPending(
        session,
        obj("kind" to str("turn"), "turnId" to str("t${turns + 1}"), "promptHash" to str(hash), "startedAt" to now),
        now,
    )
}

/**
 * Library edits (name, tags, archive) saved while this screen held an older copy of the idea
 * are kept, not overwritten by the interview's snapshot: the web's withLibraryMetadata.
 */
internal fun withLibraryMetadata(session: Session, latest: Session?, now: JsonElement): Session {
    if (latest == null) return session
    var s = session
    if (s["name"] != latest["name"]) s = renameSession(s, latest["name"], now)
    if (s["tags"] != latest["tags"]) s = setSessionTags(s, latest["tags"], now)
    if (s["archivedAt"] != latest["archivedAt"]) {
        s = if (truthy(latest["archivedAt"])) archiveSession(s, latest["archivedAt"]!!) else restoreSession(s, now)
    }
    return s
}

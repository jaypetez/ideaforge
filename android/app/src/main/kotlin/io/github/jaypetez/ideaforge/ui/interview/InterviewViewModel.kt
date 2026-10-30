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
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.lastAnswerClass
import io.github.jaypetez.ideaforge.core.legalMoves
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.mergeContentReports
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.restoreSession
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.selectNextDimension
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.core.setPending
import io.github.jaypetez.ideaforge.core.setSessionTags
import io.github.jaypetez.ideaforge.core.skipQuestion
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.InferenceConsent
import io.github.jaypetez.ideaforge.data.InferencePermit
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportController
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.CaptureEnd
import io.github.jaypetez.ideaforge.voice.DictationPhase
import io.github.jaypetez.ideaforge.voice.ManualDictation
import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

enum class DictationOutcome { ADDED, EMPTY, DENIED, CANCELLED, INTERRUPTED, FAILED }

data class DictationState(
    val phase: DictationPhase? = null,
    val caption: String = "",
    val outcome: DictationOutcome? = null,
    val detail: String = "",
) {
    val active: Boolean get() = phase != null
}

/** Bound before any permission or provider suspension; never replaced by a later tap. */
class DictationRequest internal constructor(
    internal val tag: SessionWorker.Tag,
    internal val turn: JsonObject,
    internal val answerRevision: Long,
    internal val draft: String,
    internal val baselineDraft: String,
    internal val permit: InferencePermit,
    internal val audioRevision: Long,
    internal val ownsView: () -> Boolean,
)

/** Something to tell the user once, as a snackbar. [detail] is the underlying error text. */
sealed interface InterviewNotice {
    val detail: String

    /** The saved provider could not be built; questions come from the checklist. */
    data class NoProvider(override val detail: String) : InterviewNotice

    /**
     * What went wrong underneath, when there is an exception to keep. Never shown; it is here
     * so a test that sees an unexpected notice can name the exception class and its stack
     * rather than only the message (a flake that ended an interview early, #138).
     */
    val cause: Throwable? get() = null

    /** The model call failed; this one question came from the checklist. */
    data class Fallback(override val detail: String, override val cause: Throwable? = null) : InterviewNotice

    /** Saving or running the turn threw. */
    data class Failed(override val detail: String, override val cause: Throwable? = null) : InterviewNotice
    data class Consent(override val detail: String) : InterviewNotice
    data class Voice(override val detail: String) : InterviewNotice
}

data class InterviewState(
    /** Null until the stored idea has been read (or a new one seeded). */
    val session: Session? = null,
    val answer: String = "",
    /** `typed`, `voice` or `chip`: an unedited chip is the model's words. */
    val answerSource: String = "typed",
    val busy: Busy? = null,
    /** The engine's wrap advisory, while the offer is on screen. */
    val wrapOffer: String? = null,
    val notice: InterviewNotice? = null,
    /** The write-up has settled: show the result. */
    val finished: Boolean = false,
    /** The write-up behind [finished] failed; the result screen says so (ResultRoute). */
    val writeUpFailed: Boolean = false,
    val consentRequired: Boolean = false,
    val dictation: DictationState = DictationState(),
) {
    val openTurn: JsonObject? get() = session?.let(::openTurn)

    /** The open question came from the static bank, not the model: the web's turnline suffix. */
    val degraded: Boolean get() = stringOf(openTurn?.get("questionSource")) == "bank"

    val turnCount: Int get() = (session?.get("turns") as? JsonArray)?.size ?: 0

    /** The web shows "That's enough — write it up" from the fourth question on. */
    val canWriteUp: Boolean get() = session != null && turnCount >= 4 && busy == null && !consentRequired

    val canAnswer: Boolean get() = openTurn != null && busy == null && !consentRequired
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
    val sessionId: String,
    private val store: SessionStore,
    private val worker: SessionWorker,
    private val clock: Clock,
    private val ring: () -> JsonElement,
    private val providers: ProviderSource,
    private val writes: CoroutineScope,
    private val inference: InferenceConsent,
    private val draftDelayMs: Long = DRAFT_DEBOUNCE_MS,
    reportDrive: () -> DriveController? = { null },
    private val dictation: ManualDictation? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(InterviewState())
    val state: StateFlow<InterviewState> = _state.asStateFlow()
    val inferenceState get() = inference.state

    private var provider: Provider? = null
    private var providerPermit: InferencePermit? = null
    private var providerRing: JsonElement? = null
    private val work = InterviewWork(sessionId, store, worker, clock, writes)
    private var draftJob: Job? = null
    private var answerRevision = 0L
    private var dictationRequest: DictationRequest? = null
    private var dictationCapture: ManualDictation.Capture? = null
    private var dictationJob: Job? = null
    val canDictate: Boolean get() = dictation != null

    /**
     * Text queued for a draft write and not yet saved. The debounce job cannot answer this:
     * viewModelScope is cancelled before onCleared runs, so a flush that asked whether the job
     * was still active always found it dead and dropped a draft typed just before leaving.
     */
    @Volatile
    private var pendingDraft: String? = null

    private fun now(): JsonElement = work.now()

    val reports = ContentReportController(store, worker, clock, writes, viewModelScope, changed = { latest, replaced ->
        if (replaced) cancelDraft()
        _state.update { st ->
            val current = st.session
            when {
                current == null -> st
                replaced -> st.copy(session = latest, answer = stringOf(latest["draftAnswer"]).orEmpty())
                else -> st.copy(session = mergeContentReports(current, latest, now()))
            }
        }
    }, drive = reportDrive, beforeOpen = { retireVoiceInput() })

    fun replaceReportedQuestion(fromVoice: Boolean) {
        cancelDictation()
        if (!fromVoice) {
            // Keep this pending until the replacement is durable, including a failed-save retry.
            draftJob?.cancel()
            draftJob = null
            pendingDraft = _state.value.answer
        }
        reports.replace(if (fromVoice) null else _state.value.answer)
    }

    init {
        viewModelScope.launch { open() }
        if (dictation != null) viewModelScope.launch {
            dictation.revision.collect {
                val request = dictationRequest
                if (request != null && !dictation.current(request.audioRevision)) cancelDictation()
            }
        }
    }

    fun refreshConsent() {
        val consent = inference.state.value
        if (consent.blocked) {
            cancelDictation()
            _state.update { it.copy(consentRequired = true, notice = InterviewNotice.Consent(consent.problem.orEmpty())) }
        } else if (consent.permit != null && _state.value.session != null) {
            _state.update { it.copy(consentRequired = false) }
        }
    }

    private suspend fun modelForWork(expected: InferencePermit? = null): Provider? {
        val permit = inference.require(expected)
        val current = ring()
        if (permit != providerPermit || current != providerRing) {
            provider = try {
                providers.buildAuthorized(current) { inference.require(permit) }
            } catch (error: ProviderException) {
                if (error.code == "consent") throw error
                _state.update { it.copy(notice = InterviewNotice.NoProvider(message(error))) }
                null
            }
            providerPermit = permit
            providerRing = current
        }
        return provider
    }

    private fun consentFailed(error: ProviderException) {
        _state.update {
            it.copy(busy = null, consentRequired = true, notice = InterviewNotice.Consent(message(error)))
        }
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
            _state.update { it.copy(notice = InterviewNotice.Failed(message(e), e)) }
            return
        }
        if (stored != null && stringOf(stored["status"]) == "done" && openTurn(stored) == null
            && !truthy(stored["pending"])) {
            _state.update { it.copy(session = stored, finished = true) }
            return
        }
        if (stored != null) {
            _state.update { it.copy(session = stored, answer = stringOf(stored["draftAnswer"]).orEmpty(), consentRequired = true) }
        }
        try {
            modelForWork()
            _state.update { it.copy(consentRequired = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            if (e.code == "consent") { consentFailed(e); return }
            _state.update { it.copy(notice = InterviewNotice.NoProvider(message(e))) }
        } catch (e: Exception) {
            _state.update { it.copy(notice = InterviewNotice.Failed(message(e), e)) }
            return
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
        cancelDictation()
        answerRevision++
        _state.update { it.copy(answer = text, answerSource = if (it.answerSource == "chip") "typed" else it.answerSource) }
        queueDraft(text)
    }

    /**
     * A tapped chip drops into the box as editable text. It is the model's wording, not the
     * user's, and the ratchet caps it at `partial` for exactly that reason, unless it is edited.
     */
    fun onChip(text: String) {
        cancelDictation()
        answerRevision++
        _state.update { it.copy(answer = text, answerSource = "chip") }
        queueDraft(text)
    }

    fun send() {
        refreshConsent()
        val st = _state.value
        val s = st.session ?: return
        if (!st.canAnswer) return
        val body = st.answer.trim()
        if (body.isEmpty()) return
        cancelDictation()
        cancelDraft()
        advance(submitAnswer(s, str(body), st.answerSource, now()))
    }

    fun skip() {
        refreshConsent()
        val st = _state.value
        val s = st.session ?: return
        if (!st.canAnswer) return
        cancelDictation()
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
        val permit = inference.state.value.permit
        return work.turn(session, {
            if (permit == null) throw ProviderException("consent", "Review the interview-text agreement in Settings.")
            modelForWork(permit)
        }, resume)
    }

    private suspend fun finishTurn(turn: Deferred<SessionWorker.Outcome<SettledTurn>>) {
        val outcome = try {
            turn.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            if (e.code == "consent") { consentFailed(e); return }
            _state.update { it.copy(busy = null, notice = InterviewNotice.Failed(message(e), e)) }
            return
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, notice = InterviewNotice.Failed(message(e), e)) }
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
                notice = out.error?.let { e -> InterviewNotice.Fallback(message(e), e) } ?: it.notice,
            )
        }
        // Nothing left worth asking (coverage, the hard ceiling, an exhausted bank): write it up.
        if (out.turn == null && !out.aborted) writeUp()
    }

    // ─────────────────────────────────────────────────────────── wrapping up

    /** Not now: the offer goes away and is not made again (wrapOffered is already saved). */
    fun declineWrap() = _state.update { it.copy(wrapOffer = null) }

    /** "Write it up", from the composer or the wrap offer. */
    fun writeUp() {
        refreshConsent()
        val st = _state.value
        if (st.session == null || st.busy != null || st.consentRequired) return
        writeUp(resume = false)
    }

    /**
     * Like a turn, the write-up starts in the app scope synchronously, so leaving the screen
     * the moment it is tapped cannot cancel it before it runs; only the UI update afterwards
     * belongs to viewModelScope.
     */
    private fun writeUp(resume: Boolean) {
        cancelDictation()
        val session = _state.value.session ?: return
        cancelDraft()
        _state.update { it.copy(busy = Busy.Writing, wrapOffer = null) }
        val permit = inference.state.value.permit
        val job = work.writeUp(session, {
            if (permit == null) throw ProviderException("consent", "Review the interview-text agreement in Settings.")
            modelForWork(permit)
        }, resume)
        viewModelScope.launch {
            val outcome = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                if (e.code == "consent") { consentFailed(e); return@launch }
                _state.update { it.copy(busy = null, notice = InterviewNotice.Failed(message(e), e)) }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, notice = InterviewNotice.Failed(message(e), e)) }
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
        if (openTurn(s) == null) {
            if (pendingDraft == text) pendingDraft = null
            return
        }
        val expected = openTurn(s) ?: return
        try {
            val out = worker.run(worker.tag(sessionId), work = {
                val latest = store.get(sessionId) ?: s.takeIf { sessionId !in store.knownIds() } ?: return@run null
                val open = openTurn(latest) ?: return@run null
                if (open["id"] != expected["id"] || open["question"] != expected["question"]) return@run null
                // A cancelled save can finish durably without updating the UI's older snapshot.
                if (stringOf(latest["draftAnswer"]).orEmpty() == text) return@run latest
                setDraftAnswer(latest, str(text), now()).also { store.save(it) }
            })
            val drafted = (out as? SessionWorker.Outcome.Committed)?.value
            if (drafted != null) {
                _state.update {
                    if (it.answer == text && it.openTurn?.get("id") == expected["id"] &&
                        it.openTurn?.get("question") == expected["question"]) it.copy(session = drafted) else it
                }
                if (pendingDraft == text) pendingDraft = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = InterviewNotice.Failed(message(e), e)) }
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

    // ─────────────────────────────────────────────────────────── voice

    suspend fun requestDictation(ownsView: () -> Boolean): DictationRequest? {
        if (dictation == null || !_state.value.canAnswer || reports.state.value.output != null || !ownsView()) return null
        val permit = inference.state.value.permit ?: return null
        cancelDictation()
        val audioRevision = dictation.reserve()
        val st = _state.value
        val request = DictationRequest(
            worker.tag(sessionId), st.openTurn ?: return null, answerRevision, st.answer,
            stringOf(st.session?.get("draftAnswer")).orEmpty(), permit, audioRevision, ownsView,
        )
        dictationRequest = request
        _state.update { it.copy(dictation = DictationState(phase = DictationPhase.PREPARING)) }
        if (!authorizeVoice(permit) || !ownsDictation(request)) {
            if (dictationRequest === request) cancelDictation()
            return null
        }
        _state.update { it.copy(dictation = DictationState(phase = DictationPhase.PERMISSION)) }
        return request
    }

    /** Only the original tap's permission result may open audio, never a newer request. */
    suspend fun dictationPermission(request: DictationRequest, granted: Boolean) {
        if (!ownsDictation(request)) {
            if (dictationRequest === request) cancelDictation()
            return
        }
        if (!granted) {
            dictationFinished(request, DictationOutcome.DENIED)
            return
        }
        if (!authorizeDictation(request)) return
        val capture = checkNotNull(dictation).start(request.permit, request.audioRevision)
        dictationCapture = capture
        dictationJob = viewModelScope.launch {
            val watch = launch {
                capture.state.collect { input ->
                    if (ownsDictation(request)) _state.update {
                        it.copy(dictation = DictationState(phase = input.phase, caption = input.caption))
                    }
                }
            }
            try {
                val result = capture.await()
                watch.cancel()
                if (!ownsDictation(request)) return@launch
                // A different editor, reported replacement or deletion may have changed disk
                // while audio/transcription was pending, without changing this VM's snapshot.
                val latest = dictationOrigin(request)
                if (!ownsDictation(request)) return@launch
                if (latest == null || result.end == CaptureEnd.CANCELLED) {
                    dictationFinished(request, DictationOutcome.CANCELLED)
                    return@launch
                }
                val heard = result.text.trim()
                if (heard.isNotEmpty()) {
                    val text = if (request.draft.isEmpty()) heard else request.draft.trimEnd() + " " + heard
                    _state.update { it.copy(session = latest, answer = text, answerSource = "voice") }
                    queueDraft(text)
                }
                dictationFinished(request, when {
                    result.end in setOf(CaptureEnd.INTERRUPTED, CaptureEnd.WATCHDOG) -> DictationOutcome.INTERRUPTED
                    heard.isEmpty() -> DictationOutcome.EMPTY
                    else -> DictationOutcome.ADDED
                })
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                dictationFinished(request, DictationOutcome.CANCELLED)
            } catch (failure: ProviderException) {
                if (failure.code == "consent") consentFailed(failure)
                dictationFinished(request, DictationOutcome.FAILED, message(failure))
            } catch (failure: ListenFailure) {
                dictationFinished(request, if (failure.name == "AbortError") DictationOutcome.CANCELLED else DictationOutcome.FAILED,
                    message(failure))
            } catch (failure: java.io.IOException) {
                dictationFinished(request, DictationOutcome.FAILED, message(failure))
            } catch (failure: SecurityException) {
                dictationFinished(request, DictationOutcome.FAILED, message(failure))
            } catch (failure: IllegalStateException) {
                dictationFinished(request, DictationOutcome.FAILED, message(failure))
            } finally {
                watch.cancel()
                capture.cancel()
                if (dictationCapture === capture) dictationCapture = null
                if (dictationRequest === request) dictationFinished(request, DictationOutcome.CANCELLED)
            }
        }
    }

    /** The rationale Continue and the OS result both revalidate this same original intent. */
    suspend fun authorizeDictation(request: DictationRequest): Boolean {
        if (!ownsDictation(request) || !authorizeVoice(request.permit) || !ownsDictation(request)) {
            if (dictationRequest === request) cancelDictation()
            return false
        }
        val origin = try {
            dictationOrigin(request)
        } catch (failure: java.io.IOException) {
            dictationFinished(request, DictationOutcome.FAILED, message(failure))
            return false
        } catch (failure: SecurityException) {
            dictationFinished(request, DictationOutcome.FAILED, message(failure))
            return false
        } catch (failure: IllegalStateException) {
            dictationFinished(request, DictationOutcome.FAILED, message(failure))
            return false
        }
        if (origin != null && ownsDictation(request)) return true
        if (dictationRequest === request) cancelDictation()
        return false
    }

    private fun ownsDictation(request: DictationRequest): Boolean {
        val st = _state.value
        return dictationRequest === request && request.ownsView() && st.canAnswer &&
            dictation?.current(request.audioRevision) == true &&
            worker.isCurrent(request.tag) && inference.current(request.permit) &&
            answerRevision == request.answerRevision && st.answer == request.draft &&
            st.openTurn?.get("id") == request.turn["id"] && st.openTurn?.get("question") == request.turn["question"] &&
            reports.state.value.output == null
    }

    private suspend fun dictationOrigin(request: DictationRequest): Session? {
        val read = worker.run(request.tag, work = {
            val latest = store.get(sessionId)
            if (latest == null && sessionId in store.knownIds()) return@run null
            if (latest != null && (truthy(latest["pending"]) || stringOf(latest["status"]) != "interviewing")) return@run null
            val current = latest?.let(::openTurn) ?: if (latest == null) _state.value.openTurn else null
            if (current?.get("id") != request.turn["id"] || current["question"] != request.turn["question"]) return@run null
            val durable = latest?.let { stringOf(it["draftAnswer"]).orEmpty() }
            if (latest != null && durable != request.baselineDraft && durable != request.draft) return@run null
            latest ?: _state.value.session
        })
        return (read as? SessionWorker.Outcome.Committed)?.value
    }

    private fun dictationFinished(request: DictationRequest, outcome: DictationOutcome, detail: String = "") {
        if (dictationRequest !== request) return
        dictationRequest = null
        _state.update { it.copy(dictation = DictationState(outcome = outcome, detail = detail)) }
    }

    fun stopDictation() {
        val capture = dictationCapture
        if (capture == null || _state.value.dictation.phase in setOf(DictationPhase.TRANSCRIBING, DictationPhase.STOPPING)) cancelDictation()
        else capture.stop()
    }

    fun cancelDictation() {
        val request = dictationRequest
        dictationRequest = null
        if (request != null) dictation?.cancel(request.audioRevision)
        dictationCapture?.cancel()
        dictationCapture = null
        dictationJob?.cancel()
        dictationJob = null
        if (request != null) _state.update { it.copy(dictation = DictationState(outcome = DictationOutcome.CANCELLED)) }
    }

    fun retireVoiceInput(): Long {
        cancelDictation()
        dictation?.cancel()
        return dictation?.revision?.value ?: 0L
    }

    fun ownsVoiceInput(revision: Long): Boolean = dictation?.current(revision) != false

    /**
     * Hand the open question to the hands-free drive: the session with the answer box as its
     * draft, and that draft's source, or null when there is nothing to answer. The queued
     * draft write is cancelled, because the drive now owns the draft; while it runs this
     * screen shows the voice stage and writes nothing.
     */
    fun handOffToVoice(): Pair<Session, String>? {
        cancelDictation()
        refreshConsent()
        val st = _state.value
        val s = st.session ?: return null
        if (!st.canAnswer) return null
        cancelDraft()
        val drafted = if (stringOf(s["draftAnswer"]).orEmpty() == st.answer) s else setDraftAnswer(s, str(st.answer), now())
        _state.update { it.copy(session = drafted) }
        return drafted to st.answerSource
    }

    suspend fun authorizeVoice(expected: InferencePermit? = null): Boolean = try {
        modelForWork(expected)
        _state.update { it.copy(consentRequired = false) }
        true
    } catch (error: ProviderException) {
        if (error.code != "consent") throw error
        consentFailed(error)
        false
    }

    fun voiceNotStarted(detail: String) = _state.update {
        if (it.notice == null || it.notice is InterviewNotice.Voice) it.copy(notice = InterviewNotice.Voice(detail)) else it
    }

    fun voiceRetry() = _state.update {
        if (it.notice is InterviewNotice.Voice) it.copy(notice = null) else it
    }

    /**
     * The drive ended (Exit, done, or it stood down): read the idea again, as reopening it
     * would. The session lock waits out a turn or write-up the drive left in flight, and a
     * finished interview goes on to the result exactly as it does on open.
     */
    fun reload() {
        cancelDictation()
        cancelDraft()
        _state.update { it.copy(wrapOffer = null) }
        viewModelScope.launch { open() }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun finishedShown() = _state.update { it.copy(finished = false) }

    override fun onCleared() {
        cancelDictation()
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
                    inference = graph.inferenceConsent,
                    reportDrive = { graph.driveController },
                    dictation = graph.manualDictation,
                )
            }
        }
    }
}

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
 * Keep durable library edits and local reports when interview work held an older copy.
 */
internal fun withLibraryMetadata(session: Session, latest: Session?, now: JsonElement): Session {
    if (latest == null) return session
    var s = session
    if (s["name"] != latest["name"]) s = renameSession(s, latest["name"], now)
    if (s["tags"] != latest["tags"]) s = setSessionTags(s, latest["tags"], now)
    if (s["archivedAt"] != latest["archivedAt"]) {
        s = if (truthy(latest["archivedAt"])) archiveSession(s, latest["archivedAt"]!!) else restoreSession(s, now)
    }
    return mergeContentReports(s, latest, now)
}

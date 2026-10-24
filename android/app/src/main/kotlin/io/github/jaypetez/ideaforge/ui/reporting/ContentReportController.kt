package io.github.jaypetez.ideaforge.ui.reporting

import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.addContentReport
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.replaceReportedQuestion
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.voice.DriveController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class ReportFailure { Save, Replace, Retired }

data class ContentReportState(
    val sessionId: String? = null,
    val id: String = "",
    val output: JsonObject? = null,
    val category: String? = null,
    val note: String = "",
    val busy: Boolean = false,
    val saved: Boolean = false,
    val replaced: Boolean = false,
    val failure: ReportFailure? = null,
    val detail: String = "",
    val latest: Session? = null,
) {
    val report: JsonObject? get() = (latest?.get("contentReports") as? JsonArray)
        ?.filterIsInstance<JsonObject>()?.firstOrNull { stringOf(it["id"]) == id }

    val canReplace: Boolean get() {
        val session = latest ?: return false
        val open = openTurn(session) ?: return false
        return saved && !replaced && stringOf(session["status"]) == "interviewing" && !truthy(session["pending"]) &&
            stringOf(output?.get("kind")) in setOf("question", "suggestions") &&
            open["id"] == output?.get("turnId") && open["question"] == output?.get("question") &&
            stringOf(open["questionSource"]) == "model"
    }
}

/**
 * The dialog's immutable output identity and local writes. It never constructs a provider.
 * The session lock waits out already-submitted work, then reads its latest saved object.
 * A submitted save outlives its screen; its UI continuation belongs only to this draft.
 */
class ContentReportController(
    private val store: SessionStore,
    private val worker: SessionWorker,
    private val clock: Clock,
    private val writes: CoroutineScope,
    private val scope: CoroutineScope,
    private val changed: (Session, replaced: Boolean) -> Unit = { _, _ -> },
    private val ids: IdGen = IdGen(clock),
    private val drive: () -> DriveController? = { null },
    private val beforeOpen: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ContentReportState())
    val state = _state.asStateFlow()
    private var reportedDrive: DriveController? = null

    fun open(session: Session, output: JsonObject, activeDrive: DriveController? = drive()) {
        if (_state.value.busy) return
        val id = stringOf(session["id"]) ?: return
        if (reportableOutput(session, stringOf(output["kind"]), stringOf(output["turnId"])) != output) return
        beforeOpen()
        reportedDrive = activeDrive?.takeIf { it.state.value.sessionId == id && it.state.value.attached }
        reportedDrive?.pause()
        _state.value = ContentReportState(sessionId = id, id = ids.newSessionId(), output = output)
    }

    fun category(value: String) {
        if (!_state.value.busy && !_state.value.saved) _state.update { it.copy(category = value) }
    }

    fun note(value: String) {
        if (!_state.value.busy && !_state.value.saved) _state.update { it.copy(note = value) }
    }

    fun dismiss() {
        if (!_state.value.busy) {
            _state.value = ContentReportState()
            reportedDrive = null
        }
    }

    fun save() {
        val draft = _state.value
        val output = draft.output ?: return
        if (draft.sessionId == null) return
        if (draft.busy || draft.saved || draft.category == null) return
        perform(draft, replacing = false) { latest ->
            addContentReport(latest, str(draft.id), output, str(draft.category), str(draft.note), num(clock.now()))
        }
    }

    /** [draftAnswer] is supplied only by the manual editor, never a stale voice-screen copy. */
    fun replace(draftAnswer: String? = null) {
        val draft = _state.value
        if (draft.busy || !draft.canReplace) return
        perform(draft, replacing = true) { latest ->
            val now = num(clock.now())
            val base = if (draftAnswer == null) latest else setDraftAnswer(latest, str(draftAnswer), now)
            replaceReportedQuestion(base, draft.id, now)
        }
    }

    private fun perform(draft: ContentReportState, replacing: Boolean, reduce: (Session) -> Session) {
        val id = draft.sessionId ?: return
        val matchingDrive = reportedDrive?.takeIf { it.state.value.sessionId == id && it.state.value.attached }
        // A notification Resume can happen after the dialog opened; retire that run too.
        matchingDrive?.pause()
        val tag = worker.tag(id)
        _state.update { it.copy(busy = true, failure = null, detail = "") }
        val job = writes.async {
            val outcome = worker.run(tag, work = {
                store.load()
                val latest = store.get(id) ?: error("This idea is no longer available.")
                reduce(latest)
            }, commit = { store.save(it) })
            if (outcome is SessionWorker.Outcome.Committed && matchingDrive != null) {
                // The graph-owned drive outlives this dialog's ViewModel, just like its save.
                withContext(scope.coroutineContext.minusKey(Job)) {
                    store.get(id)?.let(matchingDrive::refreshReportedSession)
                }
            }
            outcome
        }
        scope.launch {
            try {
                val outcome = job.await()
                if (_state.value.id != draft.id) return@launch
                val saved = (outcome as? SessionWorker.Outcome.Committed)?.value
                if (saved == null) {
                    _state.update { it.copy(busy = false, failure = ReportFailure.Retired) }
                } else {
                    changed(saved, replacing)
                    _state.update { it.copy(busy = false, saved = true, replaced = replacing || it.replaced, latest = saved) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.id == draft.id) _state.update {
                    it.copy(busy = false, failure = if (replacing) ReportFailure.Replace else ReportFailure.Save,
                        detail = e.message.orEmpty())
                }
            }
        }
    }
}

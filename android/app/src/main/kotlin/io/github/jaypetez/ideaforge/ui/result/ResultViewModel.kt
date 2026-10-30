package io.github.jaypetez.ideaforge.ui.result

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.coveragePercent
import io.github.jaypetez.ideaforge.core.exportFilename
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.reopen
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.data.InferenceConsent
import io.github.jaypetez.ideaforge.core.restoreSession
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportController
import io.github.jaypetez.ideaforge.voice.DriveController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** The MIME type every way out of the result screen uses, as the web's downloadText does. */
const val MARKDOWN_MIME = "text/markdown"

/** The web's exportFor note (src/ui/app.js), word for word: the export carries it. */
const val NO_PROMPT_NOTE = "The refined prompt could not be generated, but nothing else was lost."

/**
 * The one source of the result's bytes: the web's `exportFor`. Save, Share and Copy all hand
 * over exactly this, and the screen renders it, so what is shown is what is sent.
 */
fun exportFor(session: Session): String {
    val hasPrompt = truthy(session["synthesis"]?.get("text"))
    return buildExport(session, if (hasPrompt) "claude" else "checklist", if (hasPrompt) null else NO_PROMPT_NOTE)
}

/**
 * The web's `#done-meta` line, as data: "8 questions · coverage 79%", with the checklist
 * suffix when any question came from the static bank (renderDone in src/ui/app.js).
 */
data class DoneMeta(val questions: Int, val coverage: Int, val degraded: Boolean)

fun doneMeta(session: Session): DoneMeta {
    val turns = (session["turns"] as? JsonArray).orEmpty()
    return DoneMeta(
        questions = turns.count { truthy(it["answer"]) || truthy(it["skipped"]) },
        coverage = coveragePercent(session),
        degraded = turns.any { stringOf(it["questionSource"]) == "bank" },
    )
}

/** Where a saved export goes. In the app, a Storage Access Framework document. */
fun interface DocumentSink {
    fun openOutput(uri: Uri): OutputStream
}

class ContentResolverSink(private val resolver: ContentResolver) : DocumentSink {
    // "wt" truncates a document being replaced; a provider that only knows "w" gets that.
    override fun openOutput(uri: Uri): OutputStream =
        (try { resolver.openOutputStream(uri, "wt") } catch (_: FileNotFoundException) { null }
            ?: resolver.openOutputStream(uri, "w"))
            ?: throw IOException("The file could not be opened for writing.")
}

/** Something to tell the user once, as a snackbar. */
sealed interface ResultNotice {
    data object Saved : ResultNotice

    data class SaveFailed(val detail: String) : ResultNotice

    data class ReopenFailed(val detail: String) : ResultNotice
    data class LoadFailed(val detail: String) : ResultNotice
    data class CopyFailed(val detail: String) : ResultNotice
    data class ShareFailed(val detail: String) : ResultNotice
}

data class ResultState(
    /** Null until the stored idea has been read. */
    val session: Session? = null,
    val loading: Boolean = true,
    /** The idea is not in the store (deleted, or a newer build's file). */
    val missing: Boolean = false,
    val export: String = "",
    val filename: String = "",
    val meta: DoneMeta? = null,
    val notice: ResultNotice? = null,
    val noticeAttempt: Long = 0,
    /** "Ask me more" is saving the reopened idea. */
    val reopening: Boolean = false,
    /** The reopened idea is saved: go to its interview. */
    val reopened: Boolean = false,
) {
    /** The web's done-title: the display name, or "Your refined prompt" (passed in by the UI). */
    fun title(fallback: String): String = session?.let { sessionDisplayTitle(it, fallback) } ?: fallback

    /** No refined prompt: the export still works, and says `_Not generated._`. */
    val synthesisFailed: Boolean get() = session != null && !truthy(session["synthesis"]?.get("text"))
}

/**
 * One finished idea's result: the Kotlin form of the web's done panel (renderDone, Download,
 * and the "Ask me more" button in src/ui/app.js). Share and Copy need a Context and live in
 * ResultActions; they read [ResultState.export] like everything else.
 */
class ResultViewModel(
    private val sessionId: String,
    private val store: SessionStore,
    private val worker: SessionWorker,
    private val clock: Clock,
    private val sink: DocumentSink,
    private val writes: CoroutineScope,
    private val inference: InferenceConsent,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    reportDrive: () -> DriveController? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(ResultState())
    val state: StateFlow<ResultState> = _state.asStateFlow()
    val reports = ContentReportController(store, worker, clock, writes, viewModelScope,
        changed = { session, _ -> show(session) }, drive = reportDrive)

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        _state.update { it.copy(loading = true, missing = false,
            notice = if (it.notice is ResultNotice.LoadFailed) null else it.notice) }
        // Waits out work still running for this idea (the write-up that brought us here),
        // so what is read is what it saved.
        val stored = try {
            worker.exclusive(sessionId) {
                store.load()
                store.get(sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, notice = ResultNotice.LoadFailed(message(e)),
                noticeAttempt = it.noticeAttempt + 1) }
            return
        }
        if (stored == null) {
            _state.update { it.copy(missing = true, loading = false) }
            return
        }
        show(stored)
    }

    private fun show(session: Session) = _state.update {
        it.copy(session = session, loading = false, missing = false, export = exportFor(session),
            filename = exportFilename(session), meta = doneMeta(session))
    }

    fun retryLoad() {
        if (!_state.value.loading) viewModelScope.launch { open() }
    }

    fun copyFailed(error: Exception) = _state.update {
        it.copy(notice = ResultNotice.CopyFailed(message(error)), noticeAttempt = it.noticeAttempt + 1)
    }
    fun shareFailed(error: Exception) = _state.update {
        it.copy(notice = ResultNotice.ShareFailed(message(error)), noticeAttempt = it.noticeAttempt + 1)
    }

    /** Save .md: the export's bytes, exactly, into the document the user picked. */
    fun save(uri: Uri) {
        val bytes = _state.value.export.takeIf { _state.value.session != null }?.encodeToByteArray() ?: return
        viewModelScope.launch {
            val notice = try {
                withContext(io) { sink.openOutput(uri).use { it.write(bytes) } }
                ResultNotice.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResultNotice.SaveFailed(message(e))
            }
            _state.update { it.copy(notice = notice, noticeAttempt = it.noticeAttempt + 1) }
        }
    }

    /**
     * "Ask me more" (the web's b-reopen): reopen the idea, restore it if it was archived, save,
     * then go back to its interview, which asks the next question. reopen keeps the refined
     * prompt but marks it stale, so a later export says it predates the newest answers.
     *
     * The save starts in the app scope, synchronously, so leaving the screen the moment it is
     * tapped cannot cancel it; the latest stored copy is the base, so a rename made in the
     * library meanwhile is kept.
     */
    fun askMore() {
        val st = _state.value
        val session = st.session ?: return
        if (st.reopening) return
        _state.update { it.copy(reopening = true) }
        val tag = worker.tag(sessionId)
        val revision = inference.state.value.revision
        val permit = inference.state.value.permit
        val job = writes.async {
            worker.run(
                tag,
                work = {
                    inference.require(permit)
                    if (revision != inference.state.value.revision) {
                        throw ProviderException("consent", "The provider changed. Review the agreement again.")
                    }
                    val now = num(clock.now())
                    val base = store.get(sessionId) ?: session
                    val reopened = reopen(base, now)
                    if (truthy(reopened["archivedAt"])) restoreSession(reopened, now) else reopened
                },
                commit = { store.save(it) },
            )
        }
        viewModelScope.launch {
            val outcome = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(reopening = false, notice = ResultNotice.ReopenFailed(message(e)),
                    noticeAttempt = it.noticeAttempt + 1) }
                return@launch
            }
            val saved = (outcome as? SessionWorker.Outcome.Committed)?.value
            _state.update {
                if (saved == null) it.copy(reopening = false) else it.copy(session = saved, reopening = false, reopened = true)
            }
        }
    }

    /** The injected clock, for the share directory's timestamp. */
    fun now(): Long = clock.now()

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun reopenedShown() = _state.update { it.copy(reopened = false) }

    companion object {
        fun factory(sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as IdeaForgeApp
                val graph = app.graph
                ResultViewModel(
                    sessionId, graph.sessionStore, graph.sessionWorker, graph.clock,
                    sink = graph.documentSink,
                    writes = graph.appScope,
                    inference = graph.inferenceConsent,
                    reportDrive = { graph.driveController },
                )
            }
        }
    }
}

private fun message(e: Throwable): String = e.message?.takeIf { it.isNotEmpty() } ?: e.toString()

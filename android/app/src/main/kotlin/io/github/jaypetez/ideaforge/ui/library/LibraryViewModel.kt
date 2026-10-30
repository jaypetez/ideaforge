package io.github.jaypetez.ideaforge.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.archiveSession
import io.github.jaypetez.ideaforge.core.availableTags
import io.github.jaypetez.ideaforge.core.filterSessions
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.libraryCard
import io.github.jaypetez.ideaforge.core.normalizeSessionName
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.restoreSession
import io.github.jaypetez.ideaforge.core.sessionLibraryStatus
import io.github.jaypetez.ideaforge.core.setSessionTags
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionLoadState
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportController
import io.github.jaypetez.ideaforge.ui.result.DocumentSink
import io.github.jaypetez.ideaforge.voice.DriveController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** One library card, read off [libraryCard] so the web and Android cards cannot disagree. */
data class LibraryItem(
    val id: String,
    /** name → title → opening, from `sessionDisplayTitle`. */
    val title: String,
    /** The user's own override only; empty when the title comes from the model or opening. */
    val name: String,
    /** `active`, `completed` or `archived` (sessionLibraryStatus). */
    val status: String,
    val updatedAt: Long,
    val questionCount: Int,
    val coverage: Int,
    val tags: List<String>,
    val canExport: Boolean,
    /**
     * The interview finished (`status == "done"`), archived or not. Decides "View result"
     * and the Result route; [status] says "archived" first, so it is for the badge only.
     */
    val done: Boolean,
) {
    val archived: Boolean get() = status == "archived"
}

data class LibraryFilters(
    val query: String = "",
    /** A tag from [LibraryState.tags], or null for every tag. */
    val tag: String? = null,
    val showArchived: Boolean = false,
)

data class LibraryState(
    val filters: LibraryFilters = LibraryFilters(),
    /** Every tag in the library, de-duplicated case-insensitively and sorted (availableTags). */
    val tags: List<String> = emptyList(),
    val items: List<LibraryItem> = emptyList(),
    /** Every idea on the device, archived included: the web's "N of M ideas". */
    val total: Int = 0,
    val loading: Boolean = false,
    val loadProblem: String? = null,
) {
    /** Nothing stored at all: the welcome, not a filter result. */
    val noIdeasYet: Boolean get() = !loading && loadProblem == null && total == 0

    /** Ideas exist, but the search, tag or archive filter hides every one of them. */
    val nothingMatches: Boolean get() = !loading && loadProblem == null && total > 0 && items.isEmpty()
}

/** What the last mutation did, for a snackbar. The web says the same through `say()`. */
enum class LibraryNotice { Saved, Archived, Restored, Deleted, Failed }

/**
 * The ideas library. Reads [SessionStore.sessions]; filters and cards come from the ported
 * `core/Library.kt`, never re-derived here.
 *
 * Every mutation is a `core` session reducer applied to the store's latest copy of the idea
 * (renameSession, setSessionTags, archiveSession, restoreSession), stamped with [clock], and
 * saved once. That is the web's `editLibrarySession` / `setArchived`: the reducer bumps `rev`
 * and sets `updatedAt` to now, so an edited idea moves to the top of the list as it does in
 * the browser. Writes run on [writes] (the app scope) because a save that has started must
 * finish even if the screen goes away, and under [worker]'s per-session lock so a rename can
 * never interleave with an interview turn's save of the same idea.
 */
class LibraryViewModel(
    private val store: SessionStore,
    private val ids: IdGen,
    private val clock: Clock,
    private val worker: SessionWorker,
    private val writes: CoroutineScope,
    sink: DocumentSink,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val reportDrive: () -> DriveController? = { null },
) : ViewModel() {
    private val filters = MutableStateFlow(LibraryFilters())
    private val _notice = MutableStateFlow<LibraryNotice?>(null)

    val notice: StateFlow<LibraryNotice?> = _notice.asStateFlow()

    val state: StateFlow<LibraryState> = combine(store.sessions, filters, store.loadState, ::present)
        .stateIn(viewModelScope, SharingStarted.Eagerly, present(store.sessions.value, filters.value, store.loadState.value))

    /** The stored documents, for the detail pane's export preview. */
    val sessions: StateFlow<List<Session>> = store.sessions
    val exports = LibraryExports(store::get, sink, viewModelScope, io)
    val reports = ContentReportController(store, worker, clock, writes, viewModelScope, ids = ids, drive = reportDrive)

    init { retryLoad() }

    fun retryLoad() {
        viewModelScope.launch {
            try {
                store.load()
            } catch (e: IOException) {
                Log.w("IdeaForge", "Could not read the ideas library", e)
            } catch (e: SecurityException) {
                Log.w("IdeaForge", "Access to the ideas library was denied", e)
            }
        }
    }

    /** A fresh id for a new idea. Nothing is written until the interview has an answer. */
    fun newSessionId(): String = ids.newSessionId()

    fun setQuery(query: String) = filters.update { it.copy(query = query) }

    /** Selecting the selected tag again clears it, as a chip row should. */
    fun toggleTag(tag: String) = filters.update { it.copy(tag = if (it.tag == tag) null else tag) }

    fun setShowArchived(show: Boolean) = filters.update { it.copy(showArchived = show) }

    fun clearFilters() { filters.value = LibraryFilters() }

    fun noticeShown() { _notice.value = null }

    fun now(): Long = clock.now()

    /** The web's "Rename or tag" form: [tagsText] is comma separated, as there. */
    fun saveDetails(id: String, name: String, tagsText: String) = mutate(id, LibraryNotice.Saved) { s, now ->
        val tags = arr(tagsText.split(',').map(::str))
        setSessionTags(renameSession(s, str(name), now), tags, now)
    }

    fun setArchived(id: String, archived: Boolean) =
        mutate(id, if (archived) LibraryNotice.Archived else LibraryNotice.Restored) { s, now ->
            if (archived) archiveSession(s, now) else restoreSession(s, now)
        }

    /**
     * Deletes after the screen's confirmation: the web's abort plus `deletedSessions`.
     * Retiring first drops an in-flight interview turn's result as stale and stops its voice
     * effects. The delete then runs under the session's lock, so it waits for a write already
     * holding it, and a rename queued behind it finds no idea and writes nothing. The store
     * also refuses any later save of the id, for writers that never took the lock.
     */
    fun delete(id: String) {
        worker.retire(id)
        reportDrive()?.retireSession(id)
        writes.launch {
            report(LibraryNotice.Deleted) { worker.exclusive(id) { store.delete(id) } }
        }
    }

    private fun mutate(id: String, done: LibraryNotice, change: (Session, JsonElement) -> Session) {
        val tag = worker.tag(id)
        writes.launch {
            report(done) {
                // Whether the bytes landed, not the Outcome: a stale tag skips the work (no
                // write), but a retire arriving after the save still leaves it on disk.
                var saved = false
                worker.run(tag, work = {
                    // The latest copy, not the card's: the card may be a render behind.
                    val latest = store.get(id) ?: return@run
                    store.save(change(latest, num(clock.now())))
                    saved = true
                })
                saved
            }
        }
    }

    /** [action] says whether it wrote anything; "saved" is only ever said about a write. */
    private suspend fun report(done: LibraryNotice, action: suspend () -> Boolean) {
        val notice = try {
            if (action()) done else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LibraryNotice.Failed
        }
        if (notice != null) _notice.value = notice
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as IdeaForgeApp).graph
                LibraryViewModel(graph.sessionStore, graph.idGen, graph.clock, graph.sessionWorker, graph.appScope,
                    sink = graph.documentSink,
                    reportDrive = { graph.driveController })
            }
        }

        internal fun present(
            sessions: List<Session>, filters: LibraryFilters, load: SessionLoadState = SessionLoadState(ready = true),
        ): LibraryState {
            val all = JsonArray(sessions)
            val tags = availableTags(all).mapNotNull(::stringOf)
            // A tag that no idea carries any more stops filtering, as the web's select resets
            // to "All tags" when its option disappears.
            val tag = filters.tag?.takeIf { it in tags }
            val shown = if (filters.showArchived) {
                filterSessions(all, filters.query, "archived", tag.orEmpty())
            } else {
                filterSessions(all, filters.query, "all", tag.orEmpty())
                    .filter { sessionLibraryStatus(it) != "archived" }
            }
            return LibraryState(
                filters = filters.copy(tag = tag),
                tags = tags,
                items = shown.map(::item),
                total = sessions.size,
                loading = !load.ready && load.problem == null,
                loadProblem = load.problem,
            )
        }

        internal fun item(session: JsonElement): LibraryItem {
            val card = libraryCard(session)
            return LibraryItem(
                id = stringOf(card["id"]).orEmpty(),
                title = stringOf(card["title"]).orEmpty(),
                name = normalizeSessionName(session["name"]),
                status = stringOf(card["status"]).orEmpty(),
                updatedAt = toNumber(card["updatedAt"]).takeIf { it.isFinite() }?.toLong() ?: 0L,
                questionCount = toNumber(card["questionCount"]).toInt(),
                coverage = toNumber(card["coverage"]).toInt(),
                tags = (card["tags"] as? JsonArray).orEmpty().mapNotNull(::stringOf),
                canExport = truthy(card["canExport"]),
                done = stringOf(session["status"]) == "done",
            )
        }
    }
}

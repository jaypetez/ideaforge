package io.github.jaypetez.ideaforge.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.core.BackupException
import io.github.jaypetez.ideaforge.core.MAX_BACKUP_BYTES
import io.github.jaypetez.ideaforge.core.backupFilename
import io.github.jaypetez.ideaforge.core.buildBackup
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.mergeImported
import io.github.jaypetez.ideaforge.core.parseBackup
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where backup bytes come from and go to. In the app these are Storage Access Framework
 * documents ([ContentResolverDocuments]); tests hand in a fake that counts what was read.
 */
interface BackupDocuments {
    /** The size the provider reports without opening the content, or null when unknown. */
    fun size(uri: Uri): Long?

    fun openInput(uri: Uri): InputStream

    fun openOutput(uri: Uri): OutputStream
}

class ContentResolverDocuments(private val resolver: ContentResolver) : BackupDocuments {
    override fun size(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val column = c.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && c.moveToFirst() && !c.isNull(column)) c.getLong(column) else null
        }
    }.getOrNull()

    override fun openInput(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("The selected file could not be opened.")

    // "wt" truncates a document being replaced; a provider that only knows "w" gets that.
    override fun openOutput(uri: Uri): OutputStream =
        (try { resolver.openOutputStream(uri, "wt") } catch (_: FileNotFoundException) { null }
            ?: resolver.openOutputStream(uri, "w"))
            ?: throw IOException("The backup file could not be written.")
}

/** The web's oversize message, word for word (importLibraryBackup in src/ui/app.js). */
internal fun tooLargeMessage(maxBytes: Int = MAX_BACKUP_BYTES): String =
    "The backup is larger than ${Math.round(maxBytes / 1024.0 / 1024.0)} MB."

/**
 * The file's text, refusing anything over [maxBytes] before it is read in full. A reported
 * size over the limit is refused without opening the document at all (the web's
 * `file.size` check); when the provider reports none, or under-reports, the read stops at
 * the first byte past the limit, so a hostile or endless stream costs at most [maxBytes] + 1.
 *
 * Decoded as `Blob.text()` decodes: UTF-8, malformed bytes replaced, a leading BOM dropped.
 */
internal fun readBackupText(size: Long?, open: () -> InputStream, maxBytes: Int = MAX_BACKUP_BYTES): String {
    if (size != null && size > maxBytes) throw BackupException(tooLargeMessage(maxBytes))
    val bytes = open().use { input ->
        val out = ByteArrayOutputStream(minOf(size ?: 64 * 1024L, maxBytes.toLong() + 1).toInt().coerceAtLeast(16))
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val want = minOf(buffer.size.toLong(), maxBytes + 1 - total).toInt()
            if (want <= 0) break
            val n = input.read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        if (total > maxBytes) throw BackupException(tooLargeMessage(maxBytes))
        out.toByteArray()
    }
    return bytes.decodeToString().removePrefix(BOM)
}

/** U+FEFF, spelled as a code point: lint refuses a literal BOM inside a source file. */
private val BOM = Char(0xFEFF).toString()

/** The web's export notice: "Backed up 3 ideas." */
internal fun backedUpMessage(count: Int): String = "Backed up $count idea${if (count == 1) "" else "s"}."

/** The web's import notice, part for part: "2 imported · 1 kept as copies · …". */
internal fun importSummaryMessage(summary: JsonElement?, skipped: Int): String {
    fun count(key: String) = toNumber(summary[key]).toLong()
    val parts = mutableListOf(
        "${count("imported")} imported",
        "${count("copied")} kept as copies",
        "${count("duplicates")} duplicates skipped",
    )
    if (skipped != 0) parts += "$skipped invalid rows skipped"
    return parts.joinToString(" · ")
}

/**
 * Merges [incoming] (parseBackup's migrated sessions) into [store]: the twin of the web's
 * `importSessions`. The decision is mergeImported's; this supplies what it cannot know.
 *
 * - `reservedIds` is every id the store knows ([SessionStore.knownIds]: readable rows, files
 *   a newer schema wrote, ids deleted this run), so a collision with any of them becomes a
 *   named copy, never a write to that id. An incoming id that cannot name a file here is
 *   reserved too, so it is re-identified as a copy rather than failing the import half way.
 * - `newId` never returns an id the store knows or the backup carries.
 * - Every write goes through [SessionStore.insert], which refuses an id the store already
 *   holds. A failure part way leaves the earlier imports in place (as the web does) and
 *   cannot have replaced a single local row.
 *
 * Returns mergeImported's summary.
 */
internal suspend fun importIntoStore(
    store: SessionStore,
    incoming: JsonArray,
    now: Long,
    newId: () -> String,
): JsonElement? {
    store.load()
    val incomingIds = incoming.mapNotNull { stringOf(it["id"]) }
    val reserved = store.knownIds() + incomingIds.filterNot(SessionStore::isSafeId)
    val taken = HashSet(reserved).apply { addAll(incomingIds) }
    val fresh = {
        val id = generateSequence(newId).first { it !in taken && SessionStore.isSafeId(it) }
        taken += id
        str(id)
    }
    val merge = mergeImported(
        JsonArray(store.sessions.value),
        incoming,
        newId = fresh,
        now = num(now),
        reservedIds = JsonArray(reserved.map(::str)),
    )
    for (write in merge["writes"] as JsonArray) {
        check(store.insert(write as JsonObject)) {
            "An idea with the id ${stringOf(write["id"])} appeared during the import; it was left untouched."
        }
    }
    return merge["summary"]
}

data class BackupState(
    /** A backup job is running or queued. */
    val busy: Boolean = false,
    /** The last outcome, in the web's words: a summary, or the error the web would show. */
    val message: String? = null,
    val failed: Boolean = false,
)

/**
 * Backup work and its state, for the life of the process rather than of a screen.
 *
 * An import keeps writing after its screen is gone (Back mid-import), so the busy flag and
 * the outcome belong here, beside the work, and a ViewModel only observes them: a screen
 * reopened mid-import shows it still running and then its result, instead of an idle
 * section inviting a second import of the same file.
 *
 * Each job runs whole behind one [Mutex]. An import's snapshot of the store's ids and its
 * writes are one unit; two imports interleaved would plan from snapshots that miss each
 * other's writes, and the second would mint "(imported)" copies of ideas the first had just
 * added, then trip [SessionStore.insert]'s refusal part way. Queued behind the first, the
 * second sees them and counts them as duplicates.
 */
class BackupJobs(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val lock = Any()
    private var pending = 0
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private fun begin() {
        synchronized(lock) {
            pending++
            _state.value = BackupState(busy = true)
        }
    }

    /** Queues [action]; its return value is the message shown when it succeeds. */
    fun launch(action: suspend () -> String): Job {
        begin()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) { run(action) { it } }
    }

    /** Preparation is cancelled with its caller; an abandoned screen must not open a picker. */
    suspend fun <T> prepare(action: suspend () -> T): T? {
        begin()
        return run(action) { null }
    }

    private suspend fun <T> run(action: suspend () -> T, message: (T) -> String?): T? {
        var outcome: BackupState? = null
        try {
            return mutex.withLock {
                try {
                    action().also { outcome = BackupState(message = message(it)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    outcome = BackupState(message = e.message ?: "The backup could not be read.", failed = true)
                    null
                }
            }
        } finally {
            synchronized(lock) {
                pending--
                _state.value = (outcome ?: _state.value).copy(busy = pending > 0)
            }
        }
    }
}

/**
 * Export and import of the web backup format through the Storage Access Framework.
 *
 * Export writes exactly what the web's buildBackup writes for the same library, readable
 * sessions newest first (the web's listSessions); a file a newer schema wrote is not
 * exported, as the browser does not export rows it cannot read. Backups contain sessions and
 * nothing else: this reads the session store only, never the keyring or preferences.
 *
 * [jobs] serializes work and retains outcomes. Submitted writes/imports outlive the screen;
 * preparation belongs to its visible caller, before it may open a document picker.
 */
class BackupViewModel(
    private val store: SessionStore,
    private val documents: BackupDocuments,
    private val clock: Clock,
    private val ids: IdGen,
    private val jobs: BackupJobs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val state: StateFlow<BackupState> = jobs.state
    private class PreparedBackup(val filename: String, val count: Int, val bytes: ByteArray)
    private var prepared: PreparedBackup? = null
    private var preparing = false

    /** Validate the entire snapshot before SAF can create a file; keep these exact bytes for its result. */
    suspend fun prepareExport(): String? {
        if (preparing || prepared != null) return null
        preparing = true
        try {
            prepared = jobs.prepare {
                store.load()
                val sessions = store.sessions.value
                val now = clock.now()
                withContext(io) {
                    PreparedBackup(
                        backupFilename(now), sessions.size,
                        buildBackup(JsonArray(sessions), num(now)).encodeToByteArray(),
                    )
                }
            }
            return prepared?.filename
        } finally {
            preparing = false
        }
    }

    fun export(uri: Uri?) {
        val snapshot = prepared
        prepared = null
        if (uri == null) return
        jobs.launch {
            checkNotNull(snapshot) {
                "The prepared backup is no longer available. Start Backup ideas again; the selected file was not written."
            }
            withContext(io) { documents.openOutput(uri).use { it.write(snapshot.bytes) } }
            backedUpMessage(snapshot.count)
        }
    }

    fun exportPickerFailed(error: Exception) {
        prepared = null
        jobs.launch { throw error }
    }

    fun import(uri: Uri) {
        jobs.launch {
            val text = withContext(io) { readBackupText(documents.size(uri), { documents.openInput(uri) }) }
            val parsed = parseBackup(text)
            val skipped = toNumber(parsed["skipped"]).toInt()
            val summary = importIntoStore(store, parsed["sessions"] as JsonArray, clock.now(), ids::newSessionId)
            importSummaryMessage(summary, skipped)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as IdeaForgeApp
                val graph = app.graph
                BackupViewModel(
                    graph.sessionStore,
                    ContentResolverDocuments(app.contentResolver),
                    graph.clock,
                    graph.idGen,
                    graph.backupJobs,
                )
            }
        }
    }
}

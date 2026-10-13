package io.github.jaypetez.ideaforge.data

import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.migrate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

data class SessionLoadState(val ready: Boolean = false, val problem: String? = null)

/**
 * Sessions on disk: one JSON file per session, `<dir>/<id>.json`, each written atomically
 * (see [write]).
 *
 * One session is one library idea, so the store is the library. Three rules:
 *
 * - [save] writes exactly the object it is given. `runTurn` bumps `rev` several times in one
 *   call; the caller persists the object it returns, once, and the store neither migrates
 *   nor touches it on the way out.
 * - Sessions are `JsonObject`s rather than data classes, so fields this build does not know
 *   (a newer web build, a future Android build) round-trip untouched.
 * - A file [migrate] rejects (a newer schema, a corrupt document) is skipped, never deleted
 *   and never overwritten: it belongs to a newer build that may yet read it. [save] refuses
 *   an id whose file was skipped rather than silently replacing it.
 */
class SessionStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // A seam for tests that need something to happen between the bytes landing and the
    // index catching up (a caller cancelled mid-save). Production always uses [write].
    private val writer: (File, ByteArray) -> Unit = ::write,
    private val readAttributes: (File) -> BasicFileAttributes = {
        Files.readAttributes(it.toPath(), BasicFileAttributes::class.java)
    },
) {
    private val mutex = Mutex()
    private val byId = LinkedHashMap<String, Session>()
    private val skipped = LinkedHashSet<String>()
    private val deleted = HashSet<String>()
    private var loaded = false

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    private val _loadState = MutableStateFlow(SessionLoadState())

    /** Every loaded session, most recently updated first. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
    val loadState: StateFlow<SessionLoadState> = _loadState.asStateFlow()

    /** File names (without `.json`) that were left alone because they could not be read. */
    val skippedIds: Set<String> get() = skipped.toSet()

    /** Reads the directory once; later calls are no-ops. Returns the number loaded. */
    suspend fun load(): Int = mutex.withLock { loadLocked() }

    // Callers hold [mutex]. save() runs this too: until the directory has been read, the
    // store cannot know which files belong to a newer build, and IdeaForgeApp starts load()
    // asynchronously, so a save may well arrive first.
    private suspend fun loadLocked(): Int {
        if (loaded) return byId.size
        _loadState.value = SessionLoadState()
        val found = LinkedHashMap<String, Session>()
        val refused = LinkedHashSet<String>()
        try {
            withContext(io) {
                val exists = try {
                    if (!readAttributes(dir).isDirectory) {
                        throw IOException("The ideas storage path is not a directory.")
                    }
                    true
                } catch (_: NoSuchFileException) {
                    // Only a missing sessions directory is a fresh library. A missing or
                    // inaccessible parent is storage trouble, not proof that no ideas exist.
                    val parent = dir.absoluteFile.parentFile ?: throw IOException("The ideas storage directory is unavailable.")
                    if (!readAttributes(parent).isDirectory) {
                        throw IOException("The ideas storage directory is unavailable.")
                    }
                    false
                }
                val files = if (exists) {
                    dir.listFiles { f -> f.name.endsWith(SUFFIX) }
                        ?: throw IOException("The ideas storage directory could not be read.")
                } else emptyArray()
                for (file in files.sortedBy { it.name }) {
                    // File.isFile returns false for failed stat calls. An enumerable but
                    // unsearchable directory must fail the read, not cache an empty library.
                    if (!readAttributes(file).isRegularFile) continue
                    val name = file.name.removeSuffix(SUFFIX)
                    // I/O failure is not a corrupt/newer document: fail the read and allow a
                    // retry rather than publishing an empty or partial successful library.
                    val text = file.readBytes().decodeToString()
                    val session = runCatching { migrate(Json.parseToJsonElement(text)) }.getOrNull()
                    val id = (session?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (session == null || id == null || id != name) {
                        refused += name
                    } else {
                        found[id] = session
                    }
                }
            }
        } catch (e: IOException) {
            _loadState.value = SessionLoadState(problem = e.message ?: e.toString())
            throw e
        } catch (e: SecurityException) {
            _loadState.value = SessionLoadState(problem = e.message ?: e.toString())
            throw e
        }
        byId.putAll(found)
        skipped += refused
        loaded = true
        publish()
        _loadState.value = SessionLoadState(ready = true)
        return byId.size
    }

    fun get(id: String): Session? = _sessions.value.firstOrNull { idOf(it) == id }

    /** Persists [session] exactly as given, then updates the index. */
    suspend fun save(session: Session) = mutex.withLock { saveLocked(session) }

    /**
     * Every id this store holds or has refused: indexed sessions, files it skipped (a newer
     * schema), and ids deleted in this process. An import passes these as mergeImported's
     * `reservedIds`, the twin of the web's `keys(SESSIONS)`, so nothing it writes can land
     * on any of them.
     */
    suspend fun knownIds(): Set<String> = mutex.withLock {
        loadLocked()
        byId.keys + skipped + deleted
    }

    /**
     * [save], but only for an id the store has never held: false, writing nothing, when the
     * id is indexed, skipped, deleted or already on disk. An import writes through this, so
     * even a merge decided against a stale picture of the library cannot replace a row.
     */
    suspend fun insert(session: Session): Boolean = mutex.withLock {
        val id = requireNotNull(idOf(session)) { "a session needs a string id to be saved" }
        require(SAFE_ID.matches(id)) { "unsafe session id: $id" }
        loadLocked()
        if (id in byId || id in skipped || id in deleted || File(dir, "$id$SUFFIX").exists()) return@withLock false
        saveLocked(session)
        true
    }

    private suspend fun saveLocked(session: Session) {
        val id = requireNotNull(idOf(session)) { "a session needs a string id to be saved" }
        require(SAFE_ID.matches(id)) { "unsafe session id: $id" }
        loadLocked()
        check(id !in skipped) { "refusing to overwrite $id$SUFFIX, which this build could not read" }
        // The web's deletedSessions: a turn or a rename already on its way when the idea was
        // deleted would otherwise pass the check below (no file, so "not ours" is false) and
        // write it straight back. Per process, as the web's set is per page.
        check(id !in deleted) { "refusing to save $id, which was deleted" }
        val file = File(dir, "$id$SUFFIX")
        // Belt and braces: a file this store never indexed is not ours to replace, even if
        // it appeared after load().
        check(id in byId || !file.exists()) { "refusing to overwrite unindexed $id$SUFFIX" }
        val bytes = encode(session)
        // NonCancellable: once the write starts, the index must follow it. A cancelled caller
        // otherwise leaves the file renamed into place and byId still holding the old object.
        withContext(NonCancellable + io) {
            writer(file, bytes)
            byId[id] = session
            publish()
        }
    }

    /**
     * Removes [id]'s file and its index entry. Returns false, touching nothing, when the id
     * is not indexed: a file this build skipped belongs to a newer build (see the class
     * comment), so the library cannot reach it, and deleting it here would be data loss.
     * The web's `deleteSession` is the twin.
     */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        loadLocked()
        if (id !in byId) return@withLock false
        withContext(NonCancellable + io) {
            val file = File(dir, "$id$SUFFIX")
            if (file.exists() && !file.delete()) error("could not delete $id$SUFFIX")
            byId.remove(id)
            deleted += id
            publish()
        }
        true
    }

    private fun publish() {
        _sessions.value = byId.values.sortedWith(
            compareByDescending<Session> { updatedAt(it) }.thenBy { idOf(it) },
        )
    }

    companion object {
        private const val SUFFIX = ".json"

        // Web ids are `s_<base36>_<hex>`; imported ids are kept, so allow the general shape
        // but never a path separator or a dot-segment.
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

        /** Whether [id] can name a file here; an import re-ids a session whose id cannot. */
        fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

        fun encode(session: Session): ByteArray =
            Json.encodeToString(JsonObject.serializer(), session).encodeToByteArray()

        /** Write-then-rename; see [writeAtomically]. */
        internal fun write(file: File, bytes: ByteArray) = writeAtomically(file, bytes)

        internal const val NEW = ATOMIC_NEW_SUFFIX

        private fun idOf(session: Session): String? =
            (session["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun updatedAt(session: Session): Double =
            (session["updatedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0
    }
}

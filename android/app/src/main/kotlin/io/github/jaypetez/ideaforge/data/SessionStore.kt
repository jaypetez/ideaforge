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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
) {
    private val mutex = Mutex()
    private val byId = LinkedHashMap<String, Session>()
    private val skipped = LinkedHashSet<String>()
    private var loaded = false

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    /** Every loaded session, most recently updated first. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /** File names (without `.json`) that were left alone because they could not be read. */
    val skippedIds: Set<String> get() = skipped.toSet()

    /** Reads the directory once; later calls are no-ops. Returns the number loaded. */
    suspend fun load(): Int = mutex.withLock { loadLocked() }

    // Callers hold [mutex]. save() runs this too: until the directory has been read, the
    // store cannot know which files belong to a newer build, and IdeaForgeApp starts load()
    // asynchronously, so a save may well arrive first.
    private suspend fun loadLocked(): Int {
        if (loaded) return byId.size
        withContext(io) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
            for (file in files.sortedBy { it.name }) {
                val name = file.name.removeSuffix(SUFFIX)
                val session = runCatching {
                    // Only `<id>.json` is read; a `.json.new` is a write that never finished.
                    val text = file.readBytes().decodeToString()
                    migrate(Json.parseToJsonElement(text))
                }.getOrNull()
                val id = (session?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (session == null || id == null || id != name) {
                    skipped += name
                } else {
                    byId[id] = session
                }
            }
        }
        loaded = true
        publish()
        return byId.size
    }

    fun get(id: String): Session? = _sessions.value.firstOrNull { idOf(it) == id }

    /** Persists [session] exactly as given, then updates the index. */
    suspend fun save(session: Session) = mutex.withLock {
        val id = requireNotNull(idOf(session)) { "a session needs a string id to be saved" }
        require(SAFE_ID.matches(id)) { "unsafe session id: $id" }
        loadLocked()
        check(id !in skipped) { "refusing to overwrite $id$SUFFIX, which this build could not read" }
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

        fun encode(session: Session): ByteArray =
            Json.encodeToString(JsonObject.serializer(), session).encodeToByteArray()

        /**
         * Write-then-rename: the bytes go to `<file>.new`, are synced, then atomically
         * replace `<file>`. A crash at any point leaves either the old file or the new one.
         *
         * Not androidx AtomicFile: its finishWrite logs a failed rename and returns normally,
         * so the caller believes a save that never reached disk. That is not hypothetical:
         * on a Windows JVM (Robolectric on a developer machine) File.renameTo refuses to
         * replace an existing file, and every second save of a session silently vanished.
         * Files.move with REPLACE_EXISTING throws instead, and replaces on every platform.
         */
        internal fun write(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + NEW)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

        internal const val NEW = ".new"

        private fun idOf(session: Session): String? =
            (session["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun updatedAt(session: Session): Double =
            (session["updatedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0
    }
}

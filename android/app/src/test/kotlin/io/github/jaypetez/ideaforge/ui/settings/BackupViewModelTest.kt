package io.github.jaypetez.ideaforge.ui.settings

import android.net.Uri
import io.github.jaypetez.ideaforge.core.MAX_BACKUP_BYTES
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.parseBackup
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.sessionsEqual
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.usableRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Import and export through BackupViewModel, against a real SessionStore on disk and SAF
 * documents in memory. Messages are compared with the web's exact sentences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_760_200_000_000L
    private val uri: Uri = Uri.parse("content://test/backup.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val webText get() = File(fixturesDir, "backup-from-web.json").readText()
    private val webSessions get() = parseBackup(webText)["sessions"] as JsonArray

    private fun dir() = File(tmp.root, "sessions").apply { mkdirs() }

    private fun vm(store: SessionStore, docs: FakeDocuments) =
        BackupViewModel(store, docs, Clock { now }, IdGen(Clock { now }), scope)

    private fun web(id: String) = webSessions.first { stringOf(it["id"]) == id } as JsonObject

    private fun idea(id: String) =
        submitAnswer(seedTurn(createSession(id, num(now - 5)), num(now - 5)), str("a local idea"), now = num(now - 4))

    private fun named(store: SessionStore, name: String) =
        store.sessions.value.filter { stringOf(it["name"]) == name }

    private fun importText(store: SessionStore, text: String): BackupState {
        val docs = FakeDocuments().apply { file(uri, text.encodeToByteArray()) }
        return vm(store, docs).run { import(uri); settled() }
    }

    // Acceptance: a web-generated backup imports with the web's summary. The library holds
    // an exact copy of one of its ideas (a duplicate) and a different idea under another's
    // id (a copy); the third is new, and the fourth row was written by a newer schema.
    @Test
    fun theWebFixtureImportsWithTheWebsSummary() {
        val dir = dir()
        val store = SessionStore(dir)
        val mine = renameSession(web("s_web_draft"), str("My own draft"), num(now - 1))
        runBlocking {
            store.save(web("s_web_archived"))
            store.save(mine)
        }
        val localDraft = File(dir, "s_web_draft.json").readBytes()

        val state = importText(store, webText)

        assertFalse(state.message, state.failed)
        assertEquals("1 imported · 1 kept as copies · 1 duplicates skipped · 1 invalid rows skipped", state.message)
        assertTrue(sessionsEqual(web("s_web_remember_names"), store.get("s_web_remember_names")))
        assertArrayEquals("the local row was rewritten", localDraft, File(dir, "s_web_draft.json").readBytes())
        val copy = named(store, "Names at meetups — draft (imported)").single()
        assertTrue(stringOf(copy["id"])!!.startsWith("s_"))
        assertEquals(4, store.sessions.value.size)
        // And it is all on disk, not just in the index.
        assertEquals(4, runBlocking { SessionStore(dir).load() })
    }

    // CLAUDE.md: an imported id collision becomes a named copy, never a write to that id.
    @Test
    fun anIdCollisionBecomesANamedCopyAndTheLocalRowSurvives() {
        val dir = dir()
        val store = SessionStore(dir)
        val local = renameSession(web("s_web_remember_names"), str("Edited on this phone"), num(now - 1))
        runBlocking { store.save(local) }

        val state = importText(store, webText)

        assertEquals("2 imported · 1 kept as copies · 0 duplicates skipped · 1 invalid rows skipped", state.message)
        assertEquals("Edited on this phone", stringOf(store.get("s_web_remember_names")!!["name"]))
        val copy = named(store, "Conference name recall app (imported)").single()
        assertEquals(now.toDouble(), copy["updatedAt"].toString().toDouble(), 0.0)
    }

    // A file a newer build wrote is skipped by the store and must stay byte for byte; the
    // incoming idea with its id becomes a copy rather than a write to it.
    @Test
    fun aNewerSchemaLocalFileIsNotOverwrittenByImport() {
        val dir = dir()
        val newer = """{"schema":99,"id":"s_web_remember_names","name":"from a newer build"}""".encodeToByteArray()
        File(dir, "s_web_remember_names.json").writeBytes(newer)
        val store = SessionStore(dir)

        val state = importText(store, webText)

        assertFalse(state.message, state.failed)
        assertEquals("2 imported · 1 kept as copies · 0 duplicates skipped · 1 invalid rows skipped", state.message)
        assertArrayEquals(newer, File(dir, "s_web_remember_names.json").readBytes())
        named(store, "Conference name recall app (imported)").single()
    }

    // The store refuses to save a deleted id; an import that tried would fail half way. A
    // deleted id is reserved, so the idea comes back as a copy instead.
    @Test
    fun anIdDeletedThisRunComesBackAsACopyRatherThanFailingTheImport() {
        val store = SessionStore(dir())
        runBlocking {
            store.save(web("s_web_draft"))
            assertTrue(store.delete("s_web_draft"))
        }

        val state = importText(store, webText)

        assertFalse(state.message, state.failed)
        assertEquals("2 imported · 1 kept as copies · 0 duplicates skipped · 1 invalid rows skipped", state.message)
        named(store, "Names at meetups — draft (imported)").single()
    }

    // An id that cannot name a file here (a path, a dot) is re-identified as a copy.
    @Test
    fun anIncomingIdThatCannotNameAFileIsImportedAsACopy() {
        val store = SessionStore(dir())
        val odd = webText.replace("\"s_web_archived\"", "\"../s.web\"")

        val state = importText(store, odd)

        assertFalse(state.message, state.failed)
        assertEquals("2 imported · 1 kept as copies · 0 duplicates skipped · 1 invalid rows skipped", state.message)
        assertTrue(File(tmp.root, "s.web.json").exists().not())
    }

    // Acceptance: files over MAX_BACKUP_BYTES are refused before reading. Three providers:
    // one that reports the size (never opened), one that reports none, and one that
    // under-reports (both stop one byte past the limit).
    @Test
    fun anOversizeFileIsRefusedBeforeItIsRead() {
        val store = SessionStore(dir())

        val reported = FakeDocuments().apply { stream(uri, MAX_BACKUP_BYTES + 1L) { EndlessStream() } }
        val a = vm(store, reported).run { import(uri); settled() }
        assertTrue(a.failed)
        assertEquals("The backup is larger than 10 MB.", a.message)
        assertEquals("a file over the limit was opened", 0, reported.opens)

        val unknown = FakeDocuments().apply { stream(uri, null) { EndlessStream() } }
        val b = vm(store, unknown).run { import(uri); settled() }
        assertEquals("The backup is larger than 10 MB.", b.message)
        assertTrue("read ${unknown.bytesRead} bytes", unknown.bytesRead <= MAX_BACKUP_BYTES + 1L)

        val lying = FakeDocuments().apply { stream(uri, 10) { EndlessStream() } }
        val c = vm(store, lying).run { import(uri); settled() }
        assertEquals("The backup is larger than 10 MB.", c.message)
        assertTrue("read ${lying.bytesRead} bytes", lying.bytesRead <= MAX_BACKUP_BYTES + 1L)

        assertTrue(store.sessions.value.isEmpty())
    }

    @Test
    fun aFileExactlyAtTheLimitIsRead() {
        val body = webText.trimEnd('\n')
        val padded = body + " ".repeat(MAX_BACKUP_BYTES - body.encodeToByteArray().size)
        assertEquals(MAX_BACKUP_BYTES, padded.encodeToByteArray().size)
        val store = SessionStore(dir())
        val state = importText(store, padded)
        assertFalse(state.message, state.failed)
        assertEquals(3, store.sessions.value.size)
    }

    // Garbage is refused with the web's message, and nothing local changes.
    @Test
    fun garbageAndInvalidJsonAreRefusedWithTheWebsMessages() {
        val dir = dir()
        val store = SessionStore(dir)
        runBlocking { store.save(idea("s_local_one")) }
        val before = File(dir, "s_local_one.json").readBytes()

        val cases = listOf(
            "{not json" to "The selected file is not valid JSON.",
            "\u0000\u0001PK garbage" to "The selected file is not valid JSON.",
            "   " to "The backup file is empty.",
            "[1, 2]" to "The selected file is not an IdeaForge backup.",
            """{"format":"something-else","version":1,"sessions":[]}""" to "The selected file is not an IdeaForge backup.",
            """{"format":"ideaforge-backup","version":2,"sessions":[]}""" to
                "This backup was written by a newer or unsupported IdeaForge version.",
            """{"format":"ideaforge-backup","version":1}""" to "The backup has no session list.",
        )
        for ((text, message) in cases) {
            val state = importText(store, text)
            assertTrue(text, state.failed)
            assertEquals(text, message, state.message)
        }
        // Binary bytes that are not UTF-8 at all.
        val docs = FakeDocuments().apply { file(uri, byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0x7b)) }
        assertEquals("The selected file is not valid JSON.", vm(store, docs).run { import(uri); settled() }.message)

        assertEquals(1, store.sessions.value.size)
        assertArrayEquals(before, File(dir, "s_local_one.json").readBytes())
    }

    // A file saved from Windows Notepad starts with a BOM; Blob.text() drops it, so must we.
    @Test
    fun aLeadingByteOrderMarkIsIgnoredAsTheBrowserIgnoresIt() {
        val store = SessionStore(dir())
        val docs = FakeDocuments().apply {
            file(uri, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + webText.encodeToByteArray())
        }
        val state = vm(store, docs).run { import(uri); settled() }
        assertFalse(state.message, state.failed)
        assertEquals(3, store.sessions.value.size)
    }

    // A write failing part way leaves the earlier imports, reports the failure, and has not
    // touched a local row.
    @Test
    fun aFailedImportNeverOverwritesALocalRow() {
        val dir = dir()
        val store = SessionStore(dir, writer = { file, bytes ->
            if (file.name.startsWith("s_web_draft")) throw IOException("disk full")
            SessionStore.write(file, bytes)
        })
        runBlocking { store.save(idea("s_local_one")) }
        val before = File(dir, "s_local_one.json").readBytes()

        val state = importText(store, webText)

        assertTrue(state.failed)
        assertEquals("disk full", state.message)
        assertNotNull("an import before the failure stays", store.get("s_web_remember_names"))
        assertArrayEquals(before, File(dir, "s_local_one.json").readBytes())
    }

    // The last line of defence: insert writes only ids the store has never held.
    @Test
    fun insertRefusesEveryIdTheStoreKnows() {
        val dir = dir()
        File(dir, "s_newer.json").writeText("""{"schema":99,"id":"s_newer"}""")
        val store = SessionStore(dir)
        runBlocking {
            store.save(idea("s_kept"))
            store.save(idea("s_gone"))
            store.delete("s_gone")
            val kept = File(dir, "s_kept.json").readBytes()
            assertFalse(store.insert(renameSession(idea("s_kept"), str("replaced"), num(now))))
            assertFalse(store.insert(idea("s_newer")))
            assertFalse(store.insert(idea("s_gone")))
            assertArrayEquals(kept, File(dir, "s_kept.json").readBytes())
            assertEquals(setOf("s_kept", "s_newer", "s_gone"), store.knownIds())
            assertTrue(store.insert(idea("s_brand_new")))
        }
    }

    // CLAUDE.md: backups contain sessions and nothing else. The app's real graph, with a
    // keyring holding a key, exports a file with no trace of it.
    @Test
    fun theKeyringIsAbsentFromTheExport() {
        val graph = installTestGraph(ring = usableRing())
        val secret = "gsk_returning_user_key_0001"
        val keyringBytes = graph.keyringStore.file.readBytes()
        assertTrue("the keyring was never saved", keyringBytes.isNotEmpty())
        runBlocking { graph.sessionStore.save(idea("s_beside_the_ring")) }
        val docs = FakeDocuments()

        vm(graph.sessionStore, docs).run { export(uri); assertFalse(settled().failed) }

        val text = docs.outputs.getValue(uri).toByteArray().decodeToString()
        assertFalse(text.contains(secret))
        assertFalse(text.contains("apiKey"))
        assertFalse(text.contains("keyring"))
        val doc = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("format", "version", "exportedAt", "sessions"), doc.keys.toList())
        assertEquals(listOf("s_beside_the_ring"), (doc["sessions"] as JsonArray).map { stringOf(it["id"]) })
    }

    @Test
    fun exportSaysWhatTheWebSaysAndNamesTheFileAsTheWebDoes() {
        val store = SessionStore(dir())
        runBlocking { store.save(idea("s_only")) }
        val docs = FakeDocuments()
        val vm = vm(store, docs)
        assertEquals("ideaforge-backup-2025-10-11.json", vm.exportFilename())
        vm.export(uri)
        assertEquals("Backed up 1 idea.", vm.settled().message)
        runBlocking { store.save(idea("s_two")) }
        vm.export(uri)
        assertEquals("Backed up 2 ideas.", vm.settled().message)
    }

    @Test
    fun theSummaryOmitsInvalidRowsWhenThereAreNone() {
        val summary = Json.parseToJsonElement("""{"imported":3,"copied":0,"duplicates":2}""")
        assertEquals("3 imported · 0 kept as copies · 2 duplicates skipped", importSummaryMessage(summary, 0))
        assertEquals("The backup is larger than 10 MB.", tooLargeMessage())
        assertEquals("ok", readBackupText(2, { ByteArrayInputStream("ok".encodeToByteArray()) }))
    }
}

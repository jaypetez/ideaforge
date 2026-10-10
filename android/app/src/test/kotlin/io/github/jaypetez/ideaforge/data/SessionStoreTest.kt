package io.github.jaypetez.ideaforge.data

import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.num
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "sessions")
    private fun store() = SessionStore(dir(), io = Dispatchers.Unconfined)

    private fun session(id: String, now: Long) = createSession(id = id, now = num(now))

    @Test
    fun saveThenLoadReturnsTheSameDocument() = runBlocking {
        val s = session("s_abc_12345678", 10)
        store().save(s)
        val fresh = store()
        assertEquals(1, fresh.load())
        assertEquals(s, fresh.get("s_abc_12345678"))
    }

    // A crash between writing `<id>.json.new` and the rename: the process dies with the
    // half-written temp on disk. The next launch must read the old document, not the fragment.
    @Test
    fun aCrashMidWriteLeavesTheOldFile() = runBlocking {
        val old = session("s_a_00000001", 1)
        store().save(old)
        val file = File(dir(), "s_a_00000001.json")
        File(file.path + SessionStore.NEW).writeText("{\"id\":\"s_a_00000001\",\"turns\":[") // truncated
        val fresh = store()
        assertEquals(1, fresh.load())
        assertEquals(old, fresh.get("s_a_00000001"))
        // And the next save still lands, replacing both the stale temp and the old file.
        val next = session("s_a_00000001", 2)
        fresh.save(next)
        assertEquals(next, Json.parseToJsonElement(file.readText()))
        assertTrue(!File(file.path + SessionStore.NEW).exists())
    }

    // androidx AtomicFile swallowed a failed rename, so on a Windows host the second save
    // of a session never reached disk while the index said it had.
    @Test
    fun aSecondSaveReplacesTheFileOnDisk() = runBlocking {
        val s = store()
        s.save(session("s_a_00000002", 1))
        val v2 = session("s_a_00000002", 2)
        s.save(v2)
        assertEquals(v2, Json.parseToJsonElement(File(dir(), "s_a_00000002.json").readText()))
        assertEquals(v2, store().also { it.load() }.get("s_a_00000002"))
    }

    // Fields this build has never heard of belong to a newer build; they must survive.
    @Test
    fun unknownFieldsRoundTripThroughLoadAndSave() = runBlocking {
        dir().mkdirs()
        val base = session("s_u_00000003", 5)
        val withExtras = JsonObject(
            base + mapOf(
                "futureField" to Json.parseToJsonElement("""{"nested":[1,2.5,"x"],"deeper":{"k":null}}"""),
                "meta" to JsonObject(base["meta"]!!.jsonObject + ("futureMeta" to JsonPrimitive(true))),
            ),
        )
        File(dir(), "s_u_00000003.json").writeText(Json.encodeToString(JsonObject.serializer(), withExtras))
        val s1 = store()
        s1.load()
        val loaded = s1.get("s_u_00000003")!!
        s1.save(loaded)
        val onDisk = Json.parseToJsonElement(File(dir(), "s_u_00000003.json").readText()).jsonObject
        assertEquals(withExtras["futureField"], onDisk["futureField"])
        assertEquals(JsonPrimitive(true), onDisk["meta"]!!.jsonObject["futureMeta"])
    }

    @Test
    fun aNewerSchemaFileIsSkippedAndLeftIntact() = runBlocking {
        dir().mkdirs()
        val newer = JsonObject(session("s_n_00000004", 5) + ("schema" to JsonPrimitive(99)))
        val file = File(dir(), "s_n_00000004.json")
        val text = Json.encodeToString(JsonObject.serializer(), newer)
        file.writeText(text)
        File(dir(), "s_bad_00000005.json").writeText("{not json")
        store().save(session("s_ok_00000006", 1))

        val s = store()
        assertEquals(1, s.load())
        assertNull(s.get("s_n_00000004"))
        assertEquals(setOf("s_n_00000004", "s_bad_00000005"), s.skippedIds)
        assertEquals(text, file.readText())
        // And it is never overwritten by a save that happens to reuse the id.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { s.save(session("s_n_00000004", 9)) }
        }
        assertEquals(text, file.readText())
    }

    @Test
    fun theIndexIsSortedByUpdatedAtDescending() = runBlocking {
        val s = store()
        s.save(session("s_1_00000001", 100))
        s.save(session("s_2_00000002", 300))
        s.save(session("s_3_00000003", 200))
        assertEquals(
            listOf("s_2_00000002", "s_3_00000003", "s_1_00000001"),
            s.sessions.value.map { it["id"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun aPathLikeIdIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store().save(session("../escape", 1)) }
        }
        assertTrue(!File(tmp.root, "escape.json").exists())
    }

    // IdeaForgeApp starts load() asynchronously, so a save can arrive before it. A store
    // that has never read the directory must still not replace a newer build's file.
    @Test
    fun aNeverLoadedStoreRefusesToOverwriteANewerSchemaFile() {
        dir().mkdirs()
        val file = File(dir(), "s_n_00000007.json")
        val bytes = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(session("s_n_00000007", 5) + ("schema" to JsonPrimitive(99))),
        ).toByteArray()
        file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store().save(session("s_n_00000007", 9)) }
        }
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    // A caller cancelled while its write is on disk must not leave the index behind it:
    // the bytes are renamed into place, then the caller is cancelled before the index update.
    @Test
    fun aCancelledSaveLeavesDiskAndIndexInAgreement() = runBlocking {
        var caller: Job? = null
        val s = SessionStore(dir(), writer = { f, b ->
            SessionStore.write(f, b)
            caller?.cancel()
        })
        s.load()
        s.save(session("s_c_00000008", 1))
        val v2 = session("s_c_00000008", 2)
        caller = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { s.save(v2) }
        caller.start()
        caller.join()
        assertTrue(caller.isCancelled)
        val onDisk = Json.parseToJsonElement(File(dir(), "s_c_00000008.json").readText())
        assertEquals(v2, onDisk)
        assertEquals(onDisk, s.get("s_c_00000008"))
        assertEquals(onDisk, s.sessions.value.single())
    }
}

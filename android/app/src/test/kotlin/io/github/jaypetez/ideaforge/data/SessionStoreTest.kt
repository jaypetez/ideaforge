package io.github.jaypetez.ideaforge.data

import androidx.core.util.AtomicFile
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.num
import kotlinx.coroutines.Dispatchers
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

    // A crash between startWrite and finishWrite: the process dies with the half-written
    // `.new` on disk. The next launch must read the old document, not the fragment.
    @Test
    fun aCrashMidWriteLeavesTheOldFile() = runBlocking {
        val old = session("s_a_00000001", 1)
        store().save(old)
        val file = File(dir(), "s_a_00000001.json")
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        out.write("{\"id\":\"s_a_00000001\",\"turns\":[".toByteArray()) // truncated
        out.flush()
        // No finishWrite, no failWrite: the process is gone.
        val fresh = store()
        assertEquals(1, fresh.load())
        assertEquals(old, fresh.get("s_a_00000001"))
    }

    @Test
    fun aFailedWriteLeavesTheOldFile() = runBlocking {
        val old = session("s_a_00000002", 1)
        store().save(old)
        val file = File(dir(), "s_a_00000002.json")
        val before = file.readText()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        out.write("garbage".toByteArray())
        atomic.failWrite(out)
        assertEquals(before, file.readText())
        assertEquals(old, store().also { it.load() }.get("s_a_00000002"))
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
}

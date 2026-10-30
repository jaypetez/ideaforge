package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.jsonQuote
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {
    private fun backupWith(nested: String) =
        """{"format":"ideaforge-backup","version":1,"sessions":[{"id":"s_deep","meta":{"deep":$nested}}]}"""

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    @Test
    fun theSessionLimitRoundTripsButOneMoreIdeaIsRefusedWithoutChangingTheInput() {
        val sessions = JsonArray(List(MAX_BACKUP_SESSIONS) { createSession("s_limit_$it") })
        val text = buildBackup(sessions)
        assertTrue(text.encodeToByteArray().size < MAX_BACKUP_BYTES)
        assertEquals(obj("sessions" to sessions, "skipped" to num(0)), parseBackup(text))

        val oversized = JsonArray(sessions + createSession("s_one_more"))
        val before = oversized.toString()
        val failure = assertFailsWith<BackupException> { buildBackup(oversized); Unit }
        assertTrue(failure.message.orEmpty().contains("more than 500"))
        assertEquals(before, oversized.toString())
    }

    @Test
    fun theCompleteUtf8FileRoundTripsAtTheLimitButOneMoreByteIsRefused() {
        val session = createSession("s_utf8")
        val room = MAX_BACKUP_BYTES - buildBackup(arr(listOf(session))).encodeToByteArray().size
        val unit = "😀é漢\"\\\n"
        val unitBytes = jsonQuote(unit).encodeToByteArray().size - 2
        val draft = unit.repeat(room / unitBytes) + "x".repeat(room % unitBytes)
        val exact = JsonObject(session + ("draftAnswer" to str(draft)))
        val text = buildBackup(arr(listOf(exact)))
        assertEquals(MAX_BACKUP_BYTES, text.encodeToByteArray().size)
        assertTrue(text.length < MAX_BACKUP_BYTES)
        assertTrue(text.endsWith("\n"))
        assertEquals(obj("sessions" to arr(listOf(exact)), "skipped" to num(0)), parseBackup(text))

        val oversized = JsonObject(exact + ("draftAnswer" to str(draft + "x")))
        val failure = assertFailsWith<BackupException> { buildBackup(arr(listOf(oversized))); Unit }
        assertTrue(failure.message.orEmpty().contains("larger than 10 MB"))
        assertEquals(str(draft + "x"), oversized["draftAnswer"])
        assertEquals(str(draft), exact["draftAnswer"])
    }

    // Depth deliberately differs from the web, whose JSON.parse reads a 100,000-deep file.
    // Before the bound, recursive walks ended in StackOverflowError instead of a message.
    @Test
    fun aHostilelyDeepFileIsInvalidJsonNotACrash() {
        val err = assertFailsWith<BackupException> { parseBackup(backupWith(arrays(100_000))) }
        assertEquals("The selected file is not valid JSON.", err.message)
    }

    @Test
    fun theBoundIsExactAndAFileAtItRoundTrips() {
        // root object, sessions array, session, meta: four levels before the nested value.
        val atLimit = backupWith(arrays(MAX_JSON_DEPTH - 4))
        val parsed = parseBackup(atLimit)
        val sessions = parsed["sessions"] as JsonArray
        assertEquals(1, sessions.size)
        val built = buildBackup(sessions)
        assertTrue(built.endsWith("\n"))
        assertTrue(sessionsEqual(sessions[0], parseBackup(built)["sessions"]!!.let { (it as JsonArray)[0] }))

        assertFailsWith<BackupException> { parseBackup(backupWith(arrays(MAX_JSON_DEPTH - 3))) }
    }

    @Test
    fun printingAndComparingRefuseTooDeepInputWithAnErrorNotAStackOverflow() {
        var deep: JsonElement = arr(emptyList())
        repeat(100_000) { deep = arr(listOf(deep)) }
        val session = obj("id" to str("s"), "deep" to deep)
        assertFailsWith<JsonTooDeepException> { buildBackup(arr(listOf(session))) }
        assertFailsWith<JsonTooDeepException> { sessionsEqual(session, session) }
    }
}

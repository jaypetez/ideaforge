package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Depth is the one place the port deliberately differs from the web: V8's JSON.parse reads a
// 100,000-deep file without complaint, so there is no stable JS behaviour to record as a
// vector, and these are Kotlin-only tests. Before the bound, all three recursive walks ended
// in StackOverflowError, which escaped parseBackup as a crash rather than a message.
class BackupTest {
    private fun backupWith(nested: String) =
        """{"format":"ideaforge-backup","version":1,"sessions":[{"id":"s_deep","meta":{"deep":$nested}}]}"""

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

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

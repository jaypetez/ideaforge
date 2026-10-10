package io.github.jaypetez.ideaforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdGenTest {
    // src/store/sessions.js newSessionId: `s_${Date.now().toString(36)}_${uuid.slice(0, 8)}`.
    @Test
    fun idsUseTheWebFormat() {
        val ids = IdGen(Clock { 1_760_000_000_000L })
        val id = ids.newSessionId()
        assertTrue(id, Regex("s_[0-9a-z]+_[0-9a-f]{8}").matches(id))
        assertEquals("s_${1_760_000_000_000L.toString(36)}", id.substringBeforeLast('_'))
        assertNotEquals(id, ids.newSessionId())
    }
}

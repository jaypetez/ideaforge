package io.github.jaypetez.ideaforge.core

import kotlin.test.Test
import kotlin.test.assertEquals

// TEMPORARY: proves a red Kotlin test turns the required ci check red. Reverted in this PR.
class DeliberateFailureTest {
    @Test
    fun failsOnPurpose() {
        assertEquals(1, 2, "deliberate failure to prove the android job gates ci")
    }
}

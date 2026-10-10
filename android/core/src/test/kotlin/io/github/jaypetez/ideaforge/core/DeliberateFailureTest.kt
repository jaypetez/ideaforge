package io.github.jaypetez.ideaforge.core

import kotlin.test.Test
import kotlin.test.fail

// TEMPORARY: proves a red Kotlin test turns the required ci check red. Reverted in this PR.
class DeliberateFailureTest {
    @Test
    fun failsOnPurpose() = fail("deliberate failure to prove the android job gates ci")
}

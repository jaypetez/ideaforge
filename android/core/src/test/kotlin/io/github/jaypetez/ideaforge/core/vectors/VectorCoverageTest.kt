package io.github.jaypetez.ideaforge.core.vectors

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A vector family nobody consumes is a contract nobody checks. This fails when
 * tools/vectors.mjs grows a family the Kotlin side has not registered, when a registered
 * family disappears, or when a family arrives with no cases at all.
 */
class VectorCoverageTest {
    @Test
    fun everyFamilyOnDiskHasAConsumer() {
        val onDisk = Vectors.families().toSet()
        val registered = CONSUMERS.map { it.family }.toSet()
        assertEquals(onDisk, registered, "families on disk vs families with a Kotlin consumer")
        assertEquals(CONSUMERS.size, registered.size, "a family is registered twice")
    }

    @Test
    fun everyFamilyHasCases() {
        for (family in Vectors.families()) {
            assertTrue(Vectors.load(family).cases.isNotEmpty(), "$family has no cases")
        }
    }

    @Test
    fun theHarnessReplaysSomethingRealToday() {
        val ported = CONSUMERS.sumOf { c -> Vectors.load(c.family).cases.count { it.fn in c.ported } }
        assertTrue(ported > 0, "no vector is replayed; the harness would pass while checking nothing")
    }
}

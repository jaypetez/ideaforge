package io.github.jaypetez.ideaforge.core.vectors

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue

/**
 * Replays every golden vector whose function has been ported, one JUnit test per case, so a
 * failure names the family and the case ("turn-prompt › promptHash \"🌌\"").
 */
class VectorSuiteTest {
    @TestFactory
    fun portedCases(): List<DynamicNode> = CONSUMERS.map { consumer ->
        val family = Vectors.load(consumer.family)
        val ported = family.cases.filter { it.fn in consumer.ported && it.name !in consumer.pendingCases }
        DynamicContainer.dynamicContainer(
            consumer.family,
            ported.map { case ->
                DynamicTest.dynamicTest(case.name) { replay(consumer.ported.getValue(case.fn), case) }
            },
        )
    }

    /** A constant still listed as owed but already ported would skip a working case for ever. */
    @Test
    fun noPendingConstantIsAlreadyPorted() {
        val all = allPortedConstants()
        val ported = CONSTANTS_PENDING.keys.filter { name -> name.substringAfter(' ') in all }
        assertTrue(ported.isEmpty(), "ported but still listed as pending: $ported")
    }

    /** Nothing is skipped silently: every recorded function is either ported or owed. */
    @Test
    fun everyRecordedFunctionIsPortedOrOwedToAnIssue() {
        for (consumer in CONSUMERS) {
            val fns = Vectors.load(consumer.family).cases.map { it.fn }.toSet()
            val listed = listOf(consumer.ported.keys, consumer.pending.keys, consumer.portedElsewhere.keys)
            val unaccounted = fns - listed.flatten().toSet()
            assertTrue(unaccounted.isEmpty(), "${consumer.family}: no port and no owning issue for $unaccounted")
            val stale = listed.flatten().toSet() - fns
            assertTrue(stale.isEmpty(), "${consumer.family}: lists functions no vector records: $stale")
            val both = listed.flatten().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue(both.isEmpty(), "${consumer.family}: $both is listed twice (ported, pending, elsewhere)")
            for ((fn, module) in consumer.portedElsewhere) {
                assertTrue(MODULE.matches(module), "${consumer.family}.$fn: ported elsewhere needs a module, got '$module'")
            }
            val names = Vectors.load(consumer.family).cases.map { it.name }.toSet()
            val unknownCases = consumer.pendingCases.keys - names
            assertTrue(unknownCases.isEmpty(), "${consumer.family}: pendingCases names no recorded case: $unknownCases")
            for ((case, issue) in consumer.pendingCases) {
                assertTrue(ISSUE.matches(issue), "${consumer.family} '$case': pending needs an issue, got '$issue'")
            }
            for ((fn, issue) in consumer.pending) {
                assertTrue(ISSUE.matches(issue), "${consumer.family}.$fn: pending needs an issue, got '$issue'")
            }
        }
    }
}

/** A Gradle module path, written ":providers". */
private val MODULE = Regex("^:[a-z]+$")

/** An owning GitHub issue, written "#123". */
private val ISSUE = Regex("^#[0-9]+$")

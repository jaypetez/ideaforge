package io.github.jaypetez.ideaforge.core.vectors

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays every golden vector whose function has been ported, one JUnit test per case, so a
 * failure names the family and the case ("turn-prompt › promptHash \"🌌\"").
 */
class VectorSuiteTest {
    @TestFactory
    fun portedCases(): List<DynamicNode> = CONSUMERS.map { consumer ->
        val family = Vectors.load(consumer.family)
        val ported = family.cases.filter { it.fn in consumer.ported }
        DynamicContainer.dynamicContainer(
            consumer.family,
            ported.map { case ->
                DynamicTest.dynamicTest(case.name) { replay(consumer.ported.getValue(case.fn), case) }
            },
        )
    }

    private fun replay(port: Port, case: Case) {
        val throws = case.expect["throws"]
        if (throws != null) {
            val err = runCatching { port(case) }.exceptionOrNull()
                ?: fail("${case.name}: expected the JS error \"${(throws as JsonPrimitive).content}\", got a value")
            assertEquals((throws as JsonPrimitive).content, err.message, case.name)
            return
        }
        assertEquals(case.expect["value"], port(case), case.name)
    }

    /** Nothing is skipped silently: every recorded function is either ported or owed. */
    @Test
    fun everyRecordedFunctionIsPortedOrOwedToAnIssue() {
        for (consumer in CONSUMERS) {
            val fns = Vectors.load(consumer.family).cases.map { it.fn }.toSet()
            val unaccounted = fns - consumer.ported.keys - consumer.pending.keys
            assertTrue(unaccounted.isEmpty(), "${consumer.family}: no port and no owning issue for $unaccounted")
            val stale = (consumer.ported.keys + consumer.pending.keys) - fns
            assertTrue(stale.isEmpty(), "${consumer.family}: lists functions no vector records: $stale")
            val both = consumer.ported.keys intersect consumer.pending.keys
            assertTrue(both.isEmpty(), "${consumer.family}: $both is both ported and pending")
            for ((fn, issue) in consumer.pending) {
                assertTrue(Regex("""^#\d+$""").matches(issue), "${consumer.family}.$fn: pending needs an issue, got '$issue'")
            }
        }
    }
}

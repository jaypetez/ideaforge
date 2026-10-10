package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.vectors.ProvidersFamily
import io.github.jaypetez.ideaforge.core.vectors.Port
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.core.vectors.json
import io.github.jaypetez.ideaforge.core.vectors.replay
import io.github.jaypetez.ideaforge.core.vectors.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `providers` golden vectors, replayed here because :core cannot depend on this module.
 * :core registers the family and lists these functions as ported in :providers through the
 * shared [ProvidersFamily]; this test asserts the two lists are the same set, so a function
 * cannot be dropped from one side and silently skipped by both.
 */
class ProvidersVectorTest {
    private val family = Vectors.load("providers")

    @TestFactory
    fun portedCases(): List<DynamicTest> =
        family.cases.filter { it.fn in PORTS }.map { case ->
            DynamicTest.dynamicTest(case.name) { replay(PORTS.getValue(case.fn), case) }
        }

    @Test
    fun portsExactlyWhatCoreSaysIsPortedHere() {
        assertEquals(ProvidersFamily.IN_PROVIDERS_MODULE, PORTS.keys)
    }

    @Test
    fun everyRecordedFunctionIsPortedHereOrOwedToAnIssue() {
        val fns = family.cases.map { it.fn }.toSet()
        assertEquals(fns, PORTS.keys + ProvidersFamily.PENDING.keys, "recorded vs ported + pending")
        assertTrue((PORTS.keys intersect ProvidersFamily.PENDING.keys).isEmpty())
    }

    @Test
    fun everyPortedFunctionHasCases() {
        for (fn in PORTS.keys) assertTrue(family.cases.any { it.fn == fn }, "$fn has no recorded case")
    }
}

private fun auth(e: JsonElement?): AuthDescriptor? {
    val o = e as? JsonObject ?: return null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.content
    val scheme = when (s("scheme")) {
        "bearer" -> AuthScheme.BEARER
        "header" -> AuthScheme.HEADER
        "query" -> AuthScheme.QUERY
        else -> AuthScheme.NONE
    }
    val extra = (o["extraHeaders"] as? JsonObject)?.mapValues { str(it.value) } ?: emptyMap()
    return AuthDescriptor(scheme, header = s("header"), prefix = s("prefix"), param = s("param"), extraHeaders = extra)
}

private val PORTS: Map<String, Port> = mapOf(
    "isLoopback" to { c -> json(isLoopback(str(c.args[0]))) },
    "applyAuth" to { c ->
        val opts = c.args.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        val headers = (opts["headers"] as? JsonObject)?.mapValues { str(it.value) } ?: emptyMap()
        val out = applyAuth(str(c.args[0]), auth(opts["auth"]), str(opts["apiKey"]), headers)
        JsonObject(mapOf("url" to json(out.url), "headers" to JsonObject(out.headers.mapValues { json(it.value) })))
    },
    "codeForStatus" to { c -> json(codeForStatus(c.args[0].jsonPrimitive.int)) },
    "isRetryableProviderError" to { c -> json(isRetryable(ProviderException(str(c.args[0]), "x"))) },
    // The JS passes a plain object shaped like an error; anything not a ProviderException is never retried.
    "isRetryable" to { c -> json(isRetryable(c.args[0])) },
    "retryAfterMs" to { c ->
        val headers = c.args[0] as JsonObject
        json(retryAfterMs(headers["retry-after"]?.let(::str), nowMs = 0))
    },
    "extractJson" to { c -> extractJson(c.args.getOrNull(0)?.let { if (it is JsonPrimitive && it.isString) it.content else null }) },
)

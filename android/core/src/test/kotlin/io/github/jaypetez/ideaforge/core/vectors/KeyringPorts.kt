package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.CREDENTIALS_VERSION
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.hasAnyKey
import io.github.jaypetez.ideaforge.core.maskKey
import io.github.jaypetez.ideaforge.core.migrateCredentials
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.core.withTts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private fun Case.arg(i: Int): JsonElement? = args.getOrNull(i)

/**
 * The `keyring` family (src/store/secrets.js). Every recorded `kind` is a string; the port
 * takes it as one, which is all the Settings screen will ever pass.
 */
val KEYRING_PORTS: Map<String, Port> = mapOf(
    "constant" to { c ->
        when (val name = str(c.arg(0))) {
            "CREDENTIALS_VERSION" -> json(CREDENTIALS_VERSION)
            else -> error("unknown keyring constant $name")
        }
    },
    "emptyKeyring" to { _ -> emptyKeyring() },
    "migrateCredentials" to { c -> migrateCredentials(c.arg(0)) },
    "credsFor" to { c -> credsFor(c.arg(0), str(c.arg(1))) },
    "withCreds" to { c -> withCreds(c.arg(0), str(c.arg(1)), c.arg(2)) },
    "withStt" to { c -> withStt(c.arg(0), c.arg(1)) },
    "withTts" to { c -> withTts(c.arg(0), c.arg(1)) },
    "hasAnyKey" to { c -> JsonPrimitive(hasAnyKey(c.arg(0))) },
    "maskKey" to { c -> JsonPrimitive(maskKey(c.arg(0))) },
)

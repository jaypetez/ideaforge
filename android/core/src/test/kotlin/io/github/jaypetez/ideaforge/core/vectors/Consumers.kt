package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.utf8Length

/**
 * Every vector family and how far the port has got with it. Porting a function means moving
 * it from `pending` to `ported` in the same PR that adds the Kotlin code; VectorSuiteTest
 * then checks every one of its recorded cases.
 *
 * `providers` is listed here for completeness and will move to a consumer in :providers.
 */
private fun pending(issue: String, vararg fns: String) = fns.associateWith { issue }

val CONSUMERS: List<Consumer> = listOf(
    Consumer("constants", ported = mapOf("constant" to CONSTANT_PORT), pending = emptyMap(),
        pendingCases = CONSTANTS_PENDING),
    Consumer("session", ported = SESSION_PORTS, pending = emptyMap()),
    Consumer("js", ported = JS_PORTS, pending = emptyMap()),
    Consumer("engine", ported = ENGINE_PORTS, pending = emptyMap()),
    Consumer(
        "turn-prompt",
        ported = TURN_PROMPT_PORTS + mapOf(
            "promptHash" to { c -> json(promptHash(str(c.args[0]))) },
            "utf8Length" to { c -> json(utf8Length(str(c.args[0]))) },
        ),
        pending = emptyMap(),
    ),
    Consumer("run-turn", ported = RUNTIME_PORTS, pending = emptyMap()),
    Consumer("synthesis", ported = SYNTHESIS_PORTS, pending = pending("#75", "buildExport")),
    Consumer("markdown", ported = emptyMap(), pending = pending(
        "#75", "coveragePercent", "exportFilename", "buildExport", "libraryCard", "sessionLibraryStatus",
        "sessionSearchText", "slug", "renderBlocks", "forSpeech", "speechChunks", "availableTags",
        "filterSessions",
    )),
    Consumer("backup", ported = emptyMap(), pending = pending(
        "#76", "sessionsEqual", "backupFilename", "buildBackup", "parseBackup",
    )),
    Consumer("speech", ported = emptyMap(), pending = pending(
        "#77", "parseSpeech", "endsWithTrigger", "stripTrigger", "matchAffirmation", "normalizeTrigger",
        "triggerWarning", "spokenExamples",
    )),
    Consumer("drive-loop", ported = emptyMap(), pending = pending("#77", "driveLoop")),
    Consumer("providers", ported = emptyMap(),
        pending = pending("#78", "isLoopback", "applyAuth", "codeForStatus", "isRetryableProviderError",
            "isRetryable", "retryAfterMs", "extractJson") +
            pending("#79", "resolveTiers", "presetModels", "parseModelList", "request")),
)

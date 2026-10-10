package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.utf8Length

/**
 * Every vector family and how far the port has got with it. Porting a function means moving
 * it from `pending` to `ported` in the same PR that adds the Kotlin code; VectorSuiteTest
 * then checks every one of its recorded cases.
 *
 * `providers` is registered here so the coverage test sees every family on disk, but :core
 * cannot depend on :providers: the #78 functions are replayed by ProvidersVectorTest in that
 * module, which asserts it ports exactly [ProvidersFamily.IN_PROVIDERS_MODULE].
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
    // #75 ported these; a merge in #76 put them back to pending, so for a while the markdown
    // vectors were recorded and never replayed. Restored with the walkthrough family (#85).
    Consumer("synthesis", ported = SYNTHESIS_PORTS + mapOf("buildExport" to BUILD_EXPORT_PORT), pending = emptyMap()),
    Consumer("markdown", ported = MARKDOWN_PORTS, pending = emptyMap()),
    Consumer("backup", ported = BACKUP_PORTS, pending = emptyMap()),
    Consumer("keyring", ported = KEYRING_PORTS, pending = emptyMap()),
    // The trigger-word pair is the settings screen's (#88); the rest is the hands-free port's.
    Consumer("speech", ported = SPEECH_PORTS, pending = pending(
        "#77", "parseSpeech", "endsWithTrigger", "stripTrigger", "matchAffirmation", "spokenExamples",
    )),
    Consumer("drive-loop", ported = emptyMap(), pending = pending("#77", "driveLoop")),
    Consumer("providers", ported = emptyMap(), pending = ProvidersFamily.PENDING,
        portedElsewhere = ProvidersFamily.IN_PROVIDERS_MODULE.associateWith { ProvidersFamily.MODULE }),
    // Also replayed through InterviewViewModel by :app (WalkthroughVectorTest).
    Consumer("walkthrough", ported = WALKTHROUGH_PORTS, pending = emptyMap()),
    // Native speech output's chunking and per-chunk wait (src/voice/speak.js), for the Speaker (#90).
    Consumer("speak", ported = SPEAK_PORTS, pending = emptyMap()),
)

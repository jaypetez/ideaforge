package io.github.jaypetez.ideaforge.probe

import java.util.Locale

/**
 * The pure half of the recognizer probe (issue #89): names for SpeechRecognizer's integer
 * codes and the one line format every callback is logged in. Debug source set only.
 *
 * The codes are copied as literals rather than read from SpeechRecognizer so the names stay
 * right for codes the compile SDK knows but the phone's framework does not, and so an unknown
 * code is printed as a number instead of being mistaken for a known one.
 */
object ProbeFormat {
    private val ERRORS = mapOf(
        1 to "ERROR_NETWORK_TIMEOUT",
        2 to "ERROR_NETWORK",
        3 to "ERROR_AUDIO",
        4 to "ERROR_SERVER",
        5 to "ERROR_CLIENT",
        6 to "ERROR_SPEECH_TIMEOUT",
        7 to "ERROR_NO_MATCH",
        8 to "ERROR_RECOGNIZER_BUSY",
        9 to "ERROR_INSUFFICIENT_PERMISSIONS",
        10 to "ERROR_TOO_MANY_REQUESTS",
        11 to "ERROR_SERVER_DISCONNECTED",
        12 to "ERROR_LANGUAGE_NOT_SUPPORTED",
        13 to "ERROR_LANGUAGE_UNAVAILABLE",
        14 to "ERROR_CANNOT_CHECK_SUPPORT",
        15 to "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS",
    )

    fun errorName(code: Int): String = ERRORS[code] ?: "ERROR_UNKNOWN($code)"

    /**
     * One log line: milliseconds since the trial began on the monotonic clock, the session
     * (one startListening call) it belongs to, and the event. `session` 0 is trial-level.
     */
    fun line(elapsedNanos: Long, session: Int, event: String): String {
        val ms = elapsedNanos / 1_000_000.0
        val tag = if (session > 0) "s$session" else "--"
        return String.format(Locale.ROOT, "%10.1f %-3s %s", ms, tag, event)
    }

    /** A recognition hypothesis list with its confidence scores, side by side. */
    fun hypotheses(texts: List<String>?, scores: FloatArray?): String {
        if (texts.isNullOrEmpty()) return "texts=<none>" + (scores?.let { " scores=${it.toList()}" } ?: "")
        return texts.mapIndexed { i, t ->
            val c = scores?.getOrNull(i)?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"
            "\"$t\"@$c"
        }.joinToString(" | ", prefix = "[", postfix = "]")
    }
}

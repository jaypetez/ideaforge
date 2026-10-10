package io.github.jaypetez.ideaforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The two driving rules that fail silently, held directly rather than only through the
// recorded vectors. A truncated answer looks exactly like a short one to someone watching the
// road, and a command recorded as an answer is capped by RE_REFUSAL without anyone noticing.
class DrivingTest {
    private val allPhrases = COMMANDS.flatMap { (kind, phrases) -> phrases.map { kind to it } }

    @Test
    fun everyCommandPhraseCarriesNoAnswerText() {
        for ((kind, phrase) in allPhrases) {
            for (said in listOf(phrase, "$phrase, over", "$phrase over.", "Ok ${phrase.uppercase()}")) {
                val p = parseSpeech(said)
                assertEquals(kind, p.kind, said)
                assertEquals("", p.text, "a command must never carry answer text: \"$said\"")
            }
        }
    }

    @Test
    fun aCommandInsideASentenceIsAnAnswer() {
        for ((_, phrase) in allPhrases) {
            val said = "I said $phrase to my team yesterday"
            val p = parseSpeech("$said, over")
            assertEquals("answer", p.kind, said)
            assertEquals(said, p.text, said)
            assertTrue(p.stopped, said)
        }
    }

    @Test
    fun overBudgetMidSentenceIsAnAnswerNotAnEnding() {
        val p = parseSpeech("we went over budget")
        assertEquals(SpeechParse("answer", "we went over budget", stopped = false), p)
        assertFalse(endsWithTrigger("we went over budget"))
        assertEquals("we went over budget", stripTrigger("we went over budget"))

        val ended = parseSpeech("we went over budget, over.")
        assertEquals(SpeechParse("answer", "we went over budget", stopped = true), ended)
    }

    @Test
    fun theTriggerMatchesOnlyAtTheEnd() {
        for (said in listOf(
            "over the years we grew", "hand it over to the team", "we went over budget twice",
            "it is overdue", "ending with overture", "moreover it works", "over, and then some",
        )) {
            assertFalse(endsWithTrigger(said), said)
            assertEquals(said, stripTrigger(said), said)
            assertEquals(said, parseSpeech(said).text, "nothing may be cut from \"$said\"")
        }
        for (said in listOf("names over", "names, over", "names — over.", "names over and out", "names over, um")) {
            assertTrue(endsWithTrigger(said), said)
            assertEquals("names", stripTrigger(said), said)
        }
        // A full stop before the trigger is the speaker's own sentence ending, and is kept.
        assertEquals("I couldn't name four.", stripTrigger("I couldn't name four. Over"))
    }

    @Test
    fun aCustomTriggerIsTerminalOnlyToo() {
        assertFalse(endsWithTrigger("we are done with the first part", "done"))
        assertTrue(endsWithTrigger("we are finished, done", "done"))
        assertEquals("all set", parseSpeech("all set done", trigger = "done").text)
        // A trigger with regex metacharacters is matched literally.
        assertFalse(endsWithTrigger("one two", "o.e"))
        assertTrue(endsWithTrigger("that is it, o.e", "o.e"))
    }

    @Test
    fun caseFoldingIsAsciiOnlyAsJsSlashIWithoutU() {
        // JS /k/i does not match the Kelvin sign (U+212A) or /s/i the long s (U+017F);
        // Kotlin's IGNORE_CASE adds UNICODE_CASE and would match both.
        assertFalse(io.github.jaypetez.ideaforge.core.js.jsRegexI("k").containsMatchIn("K"))
        assertFalse(io.github.jaypetez.ideaforge.core.js.jsRegexI("s").containsMatchIn("ſ"))
        assertTrue(io.github.jaypetez.ideaforge.core.js.jsRegexI("over").containsMatchIn("OVER"))
        assertFalse(endsWithTrigger("all set oK", "ok"), "a Kelvin sign is not the trigger's k")
        assertTrue(endsWithTrigger("all set OK", "ok"))
    }

    @Test
    fun theSuggestionsAreTheirOwnSentenceAndAlwaysKeepTheFirstChip() {
        assertEquals("", spokenExamples(emptyList()))
        assertEquals("", spokenExamples(listOf(" ", null)))
        val long = "a chip that is extremely long and goes on and on for many words well past any budget"
        assertEquals("For example: $long.", spokenExamples(listOf(long, "short"), maxWords = 6))
        assertEquals("For example: a, b, or c.", spokenExamples(listOf("a.", "b;", "c")))
    }
}

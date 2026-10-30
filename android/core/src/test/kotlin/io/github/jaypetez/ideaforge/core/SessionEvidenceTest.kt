package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionEvidenceTest {
    private fun partialOutcome(): Session {
        var s = seedTurn(createSession("s_quote"))
        s = submitAnswer(s, str("The workshop should track bicycle repair jobs for volunteers."))
        s = applyCoverage(s, obj("outcome" to obj("level" to str("partial"), "gap" to str("which repairs"))))
        s = askQuestion(s, str("Who will use the workshop tracker first?"), str("audience"))
        return submitAnswer(s, str("The volunteers on Saturday morning will use it."))
    }

    private fun claim(s: Session, evidence: String): Session =
        applyCoverage(s, obj("outcome" to obj("level" to str("covered"), "evidence" to str(evidence))))

    @Test
    fun coverageMustRejectEvidenceNotPresentInAnyUserAnswer() {
        val s = partialOutcome()
        assertEquals(2, answeredTurns(s).size)
        val original = s.toString()
        val rejected = claim(s, "Invented quotation that the user never said")
        assertEquals(s["coverage"]["outcome"], rejected["coverage"]["outcome"])
        for (quote in listOf("bicycle repair jobs", "Saturday morning")) {
            val accepted = claim(s, quote)
            assertEquals("covered", stringOf(accepted["coverage"]["outcome"]["level"]))
            assertEquals(quote, stringOf(accepted["coverage"]["outcome"]["evidence"]))
        }
        assertEquals(original, s.toString())
    }

    @Test
    fun laterTurnsCannotLaunderChipOrUneditedDraftEvidence() {
        val quote = "Three seconds, one thumb"
        for ((source, text, expected) in listOf(
            Triple("typed", quote, "covered"),
            Triple("voice", quote, "covered"),
            Triple("chip", quote, "partial"),
            Triple("draft", quote, "partial"),
            Triple("draft", "$quote, revised by me.", "covered"),
        )) {
            var s = askQuestion(partialOutcome(), str("What are the limits?"), str("constraints"))
            s = setDraftText(s, str(quote))
            s = submitAnswer(s, str(text), source)
            s = askQuestion(s, str("Who else uses it?"), str("audience"))
            s = submitAnswer(s, str("The volunteers at the workshop."))
            val evidence = if (text == quote) quote else "revised by me"
            assertEquals(expected, stringOf(claim(s, evidence)["coverage"]["outcome"]["level"]), "$source: $text")
        }
    }

    @Test
    fun quotesAreExactAndAnInvalidReplacementKeepsExistingEvidence() {
        var s = askQuestion(partialOutcome(), str("What is printed?"), str("voice"))
        s = submitAnswer(s, str("Café 🍵 — “工房”\nDeux vélos,\tSamedi. Cafe\u0301"), "voice")
        for ((quote, expected) in listOf(
            "Café 🍵 — “工房”" to "covered",
            "“工房”\nDeux vélos,\tSamedi." to "covered",
            "Cafe\u0301" to "covered",
            "🍵" to "covered",
            "café 🍵" to "partial",
            "Cafe\u0301 🍵" to "partial",
            "Café 🍵 - \"工房\"" to "partial",
            "“工房” Deux vélos, Samedi." to "partial",
        )) {
            assertEquals(expected, stringOf(claim(s, quote)["coverage"]["outcome"]["level"]), quote)
        }
        val covered = claim(s, "Café 🍵 — “工房”")
        val rejected = claim(covered, "An invented replacement quote")
        assertEquals(covered["coverage"]["outcome"], rejected["coverage"]["outcome"])
    }
}

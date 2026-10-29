package io.github.jaypetez.ideaforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fold and the trigger judge against the callback shapes the #89 emulator spike recorded
 * (FINDINGS-emulator.md, "Raw callback timelines"). Settle is 600 ms throughout.
 */
class NativeTranscriptTest {
    private val settle = 600L
    private fun NativeTranscript.wait() = nativeTriggerWaitMs(snapshot(), "over", settle)

    // Default recogniser, "we went over budget last year": the first partial carries the
    // whole phrase as UNSTABLE_TEXT, and it stood 684 ms, longer than settleMs. Judged as
    // committed text that ends the answer at "we went over".
    @Test
    fun overBudgetFromTheDefaultRecogniserNeverEnds() {
        val t = NativeTranscript()
        t.partial("", "we went over")
        assertEquals("we went over", t.snapshot().text)
        assertEquals(settle * NATIVE_UNSTABLE_SETTLE_FACTOR, t.wait(), "unstable-only trigger waits longer")
        t.partial("we went over", " budget last")
        assertEquals("we went over budget last", t.snapshot().text)
        assertNull(t.wait())
        t.final("we went over budget last year")
        assertNull(t.wait())
        assertTrue(t.snapshot().settled)
        assertFalse(t.final(""), "an empty onResults is no new text")
        assertEquals("we went over budget last year", t.snapshot().finals)
    }

    // On-device never fills UNSTABLE_TEXT, capitalises, and repeats a partial.
    @Test
    fun overBudgetFromOnDeviceArmsThenDisarms() {
        val t = NativeTranscript()
        t.partial("We went over")
        assertEquals(settle, t.wait())
        t.partial("We went over bud")
        assertNull(t.wait())
        t.final("We went over budget last year")
        assertNull(t.wait())
        assertEquals("We went over budget last year", t.snapshot().text)
    }

    @Test
    fun aTerminalTriggerInAFinalEndsAtOnce() {
        val t = NativeTranscript()
        t.partial("I want to make a game like Tetris", " over")
        assertEquals(settle * NATIVE_UNSTABLE_SETTLE_FACTOR, t.wait())
        t.final("I want to make a game like Tetris over")
        assertEquals(0L, t.wait())
        assertEquals("I want to make a game like Tetris", parseSpeech(t.snapshot().text).text)
    }

    // On-device answers stopListening with ERROR_CLIENT and no final: the partial is all
    // there is, and it is not a final the engine stands behind.
    @Test
    fun aSessionEndingOnAPartialKeepsIt() {
        val t = NativeTranscript()
        t.partial("we went over budget")
        t.endSession()
        assertEquals("we went over budget", t.snapshot().finals)
        assertFalse(t.snapshot().settled)
    }

    @Test
    fun aFinalReplacesItsSessionsPartials() {
        val t = NativeTranscript()
        t.partial("skip the")
        t.partial("skip this")
        t.final("skip this")
        assertEquals("skip this", t.snapshot().text)
        assertEquals("skip", parseSpeech(t.snapshot().text).kind)
    }

    @Test
    fun restartsJoinWithoutDuplicatingAReplayedPhrase() {
        val t = NativeTranscript()
        t.final("I want to make a game")
        t.endSession()
        t.partial("make a game like")
        assertEquals("I want to make a game like", t.snapshot().text, "live caption already de-duplicated")
        t.final("make a game like Tetris")
        assertEquals("I want to make a game like Tetris", t.snapshot().text)
        t.endSession()
        t.final("I want to make a game like Tetris")
        assertEquals("I want to make a game like Tetris", t.snapshot().text, "a whole restatement absorbs")
        t.endSession()
        t.final("but slower")
        assertEquals("I want to make a game like Tetris but slower", t.snapshot().text)
    }

    @Test
    fun replayTrimmingKeepsUnreplayedWordsInsideHyphenatedText() {
        val t = NativeTranscript()
        t.final("We need a well")
        t.endSession()
        t.partial("a well-known", " draft")
        assertEquals("We need a well known draft", t.snapshot().text)
        assertEquals("We need a well known", t.snapshot().stableText)
        assertEquals("We need a well", t.snapshot().finals)
        t.partial("a well-known design")
        assertEquals("We need a well known design", t.snapshot().text)
        t.final("a well-known design")
        assertEquals("We need a well known design", t.snapshot().finals)
        assertTrue(t.snapshot().settled)
        t.endSession()
        t.final("known design")
        assertEquals("We need a well known design", t.snapshot().text, "a repeated suffix adds no words")
        t.endSession()
        t.final("We need a well-known design")
        assertEquals("We need a well-known design", t.snapshot().text, "whole-result absorption is unchanged")
    }

    @Test
    fun replayTrimmingPreservesTheRawUnreplayedSuffix() {
        val cases = listOf(
            Triple("We need a well", "a well-Known—Design, Café’s  route!",
                "We need a well Known—Design, Café’s  route!"),
            Triple("I said we're ready", "WE’RE-ready/Préparés…  Let's go!",
                "I said we're ready Préparés…  Let's go!"),
        )
        for ((before, after, expected) in cases) {
            val t = NativeTranscript()
            t.final(before)
            t.endSession()
            t.final(after)
            assertEquals(expected, t.snapshot().text, after)
        }
    }

    @Test
    fun replayTrimmingRetainsLeadingPunctuationOnNewText() {
        val cases = listOf(
            Triple("The budget is", "budget is -5 dollars", "The budget is -5 dollars"),
            Triple("The budget is", "budget\u00a0is\u00a0-5 dollars", "The budget is -5 dollars"),
            Triple("The next words", "next words \"Don't stop!\"", "The next words \"Don't stop!\""),
            Triple("The total is", "total is ( +5 ) dollars", "The total is ( +5 ) dollars"),
        )
        for ((before, after, expected) in cases) {
            val t = NativeTranscript()
            t.final(before)
            t.endSession()
            t.final(after)
            assertEquals(expected, t.snapshot().text, after)
        }
    }

    @Test
    fun aReplayBoundaryInsideANormalizedGlyphKeepsTheWholeGlyphOnce() {
        val cases = listOf(
            Triple(listOf("The amount is 1"), "is ½ cup", "The amount is ½ cup"),
            Triple(listOf("The amount is", "1"), "is ½ cup", "The amount is ½ cup"),
            Triple(listOf("The portions are ¼"), "1 ⅘ trays", "The portions are 1 ⅘ trays"),
        )
        for ((before, after, expected) in cases) {
            val t = NativeTranscript()
            before.forEach { t.final(it) }
            t.endSession()
            t.partial(after)
            assertEquals(expected, t.snapshot().text, after)
            assertEquals(before.joinToString(" "), t.snapshot().finals, "a caption cannot edit the retained finals")
            t.final(after)
            assertEquals(expected, t.snapshot().finals, after)
        }
    }

    @Test
    fun replayTrimmingUsesOriginalOffsetsWhenComparisonTextChangesLength() {
        val cases = listOf(
            Triple("We met at Café", "AT Cafe\u0301-São—Paulo,  demain!",
                "We met at Café São—Paulo,  demain!"),
            Triple("The label is No", "IS №-NEW  label.",
                "The label is No NEW  label."),
            Triple("The ratio is 1 4", "¼-Known  fraction.",
                "The ratio is 1 4 Known  fraction."),
            Triple("We read less than", "less <\u0338 than-Known  text!",
                "We read less than Known  text!"),
            Triple("We need a well", "a ｗｅｌｌ-𐐀lpha’s  design!",
                "We need a well 𐐀lpha’s  design!"),
        )
        for ((before, after, expected) in cases) {
            val t = NativeTranscript()
            t.final(before)
            t.endSession()
            t.final(after)
            assertEquals(expected, t.snapshot().text, after)
        }
    }

    // Nothing is dropped for being shorter: "over" alone after "over the years we grew" and a
    // single repeated word across a restart are both speech, not replays.
    @Test
    fun aSingleWordIsNeverAReplay() {
        val t = NativeTranscript()
        t.final("over the years we grew")
        t.endSession()
        t.final("over")
        assertEquals("over the years we grew over", t.snapshot().text)
        assertTrue(endsWithTrigger(t.snapshot().text))

        val u = NativeTranscript()
        u.final("we went over")
        u.endSession()
        u.final("over")
        assertEquals("we went over over", u.snapshot().text)
    }

    @Test
    fun withinOneSessionOnlyAWholeRestatementIsAbsorbed() {
        val t = NativeTranscript()
        t.final("we need a logo")
        t.final("a logo and a website")
        assertEquals("we need a logo a logo and a website", t.snapshot().text)
    }

    @Test
    fun wordsCompareWithoutCaseOrPunctuation() {
        assertEquals(listOf("were", "done", "café"), transcriptWords("We're done, Café!"))
        assertEquals(emptyList(), transcriptWords("  …  "))
    }
}

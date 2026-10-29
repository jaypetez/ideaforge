package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.jsLower
import io.github.jaypetez.ideaforge.core.js.jsTrim
import java.text.Normalizer

// What one native capture adds up to, and when its trigger word counts. The pure half of
// app voice/Recognizer.kt, kept here so it is tested with no SpeechRecognizer and no clock.
//
// This is the web's assembleTranscript (src/voice/webspeech.js) ported in SPIRIT, not line
// by line, because android.speech.SpeechRecognizer reports differently from Chrome on
// Android (FINDINGS from the #89 emulator spike):
//
//  - Within one recogniser session each onPartialResults is the WHOLE hypothesis so far, so
//    a partial simply replaces the one before it. Chrome's per-index drafts, which needed
//    the web's revise rule, do not happen here.
//  - The default recogniser splits a partial into settled words (RESULTS_RECOGNITION) and
//    the rest (UNSTABLE_TEXT). The caption is both; only the first is a promise.
//  - Finals are marked by final_result=true on a partial, or by arriving in onResults or
//    onSegmentResults. NEVER by confidence: on-device finals all report 0.000, which is the
//    web's "draft" signature and would make every one of them unsettled for ever.
//  - A final replaces its session's partials (the web's sweep, here by construction).
//  - A session ends every ~5 s of silence and the capture restarts. A restarted session can
//    open by replaying what the last one ended on; a result that restates the newest kept
//    results in full ABSORBS them (the web's absorb), and one that opens with the last two
//    or more words of an earlier session has those words dropped.
//  - Nothing is dropped for being shorter: "over" said alone after "…went over" is the word
//    that ends the answer. The partial-overlap trim never treats a single word as a replay,
//    but absorb can: an earlier result that WAS one word ("over") is absorbed by a later
//    result that opens with it ("over budget"), as the web's absorb does.

/**
 * How much longer a trigger that only the UNSTABLE part of a partial carries must stand.
 * The emulator held "we went over" as unstable text for 684 ms before " budget last" arrived,
 * longer than DRIVING_SETTLE_MS. Unstable words are the engine saying it is not sure yet; a
 * terminal "over" it is sure of arrives as settled text or a final within ~300-500 ms. A first
 * guess against TTS-fed audio, like every DRIVING_GATE number: the phone run settles it.
 */
const val NATIVE_UNSTABLE_SETTLE_FACTOR = 2

/** The fewest words a restarted session must replay before they are treated as a replay. */
private const val MIN_REPLAY_WORDS = 2

private val NOT_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
private val APOSTROPHES = Regex("['’]")
private val SPACE_RUN = Regex("\\s+")
private val REPLAY_SPACE = Regex("[$JS_S]+")
private val GRAPHEME = Regex("\\X")
private val CHARACTER = Regex(".", RegexOption.DOT_MATCHES_ALL)

private fun comparisonText(text: String): String =
    APOSTROPHES.replace(jsLower(Normalizer.normalize(text, Normalizer.Form.NFKC)), "")

/**
 * Words for COMPARING results, never for output: the web's wordsOf. On-device capitalises
 * one partial ("We went over") and not the next, so comparison ignores case and punctuation.
 */
fun transcriptWords(text: String?): List<String> {
    return jsTrim(NOT_WORD.replace(comparisonText(text ?: ""), " "))
        .split(' ')
        .filter { it.isNotEmpty() }
}

/** One capture's state as the UI and the trigger judge see it. */
data class TranscriptSnapshot(
    /** Kept results plus the live partial, unstable words included: the caption. */
    val text: String,
    /** Kept results only (finals, plus partials a session ended on). */
    val finals: String,
    /** [text] without the unstable words: what the engine has committed to. */
    val stableText: String,
    /** No live partial, and the newest result is a final the engine sent. */
    val settled: Boolean,
) {
    val hasWords: Boolean get() = transcriptWords(text).isNotEmpty()
}

private data class Seg(
    val text: String,
    val words: List<String>,
    val session: Int,
)

/**
 * Folds one capture's callbacks into text. Not thread-safe: the recogniser calls it from the
 * thread its callbacks arrive on.
 */
class NativeTranscript {
    private val kept = mutableListOf<Seg>()
    private var liveStable = ""
    private var liveUnstable = ""
    private var session = 0
    private var lastWasFinal = false

    /** The recogniser session in progress, counted from 0. */
    val sessionNumber: Int get() = session

    /** A partial: the whole hypothesis of this session so far, replacing the last one. */
    fun partial(stable: String?, unstable: String? = null) {
        liveStable = stable ?: ""
        liveUnstable = unstable ?: ""
        lastWasFinal = false
    }

    /**
     * A final for this session. An empty one (onResults with no texts, as the emulator sent
     * 3.5 s after the real final) means "no new text", never a miss, and changes nothing.
     *
     * @return true when it carried words
     */
    fun final(text: String?): Boolean {
        val words = transcriptWords(text)
        if (words.isEmpty()) return false
        place(kept, Seg(jsTrim(text!!), words, session))
        liveStable = ""
        liveUnstable = ""
        lastWasFinal = true
        return true
    }

    /**
     * The session ended (a restart is coming, or the capture is over). A partial it never
     * finalised is kept as said: on-device answers stopListening with ERROR_CLIENT and no
     * final, and those words exist nowhere else.
     */
    fun endSession() {
        val live = liveText()
        if (transcriptWords(live).isNotEmpty()) {
            place(kept, Seg(live, transcriptWords(live), session))
            lastWasFinal = false
        }
        liveStable = ""
        liveUnstable = ""
        session += 1
    }

    fun snapshot(): TranscriptSnapshot {
        val finals = kept.joinToString(" ") { it.text }
        val live = liveText()
        val text = withLive(live)
        val stable = jsTrim(liveStable)
        return TranscriptSnapshot(
            text = text,
            finals = finals,
            stableText = if (transcriptWords(stable).isEmpty()) finals else withLive(stable),
            settled = live.isEmpty() && lastWasFinal && kept.isNotEmpty(),
        )
    }

    private fun liveText(): String = jsTrim(SPACE_RUN.replace(liveStable + liveUnstable, " "))

    private fun withLive(live: String): String {
        val words = transcriptWords(live)
        if (words.isEmpty()) return kept.joinToString(" ") { it.text }
        val copy = kept.toMutableList()
        place(copy, Seg(live, words, session))
        return copy.joinToString(" ") { it.text }
    }
}

/** How many of the newest kept results [seg] repeats in full, widest first; 0 if none. */
private fun restated(kept: List<Seg>, seg: Seg): Int {
    var widest = 0
    var span = emptyList<String>()
    for (k in 1..kept.size) {
        span = kept[kept.size - k].words + span
        if (span.size > seg.words.size) break
        if (seg.words.subList(0, span.size) == span) widest = k
    }
    return widest
}

/**
 * Add [seg] to [kept]: it absorbs whatever it restates in full, and joins a restarted
 * session's replay at original-text word boundaries.
 */
private fun place(kept: MutableList<Seg>, seg: Seg) {
    var absorbed = false
    var k = restated(kept, seg)
    while (k > 0) {
        repeat(k) { kept.removeAt(kept.size - 1) }
        absorbed = true
        k = restated(kept, seg)
    }
    val last = kept.lastOrNull()
    if (absorbed || last == null || last.session >= seg.session) {
        kept += seg
        return
    }
    val before = kept.flatMap { it.words }
    var overlap = 0
    for (n in minOf(before.size, seg.words.size) downTo MIN_REPLAY_WORDS) {
        if (before.subList(before.size - n, before.size) == seg.words.subList(0, n)) {
            overlap = n
            break
        }
    }
    when {
        overlap == 0 -> kept += seg
        overlap == seg.words.size -> Unit                 // a pure replay adds nothing
        else -> {
            val spans = comparisonWordSpans(seg.text)
            // Prefer keeping the earlier spelling. If a comparison boundary splits a
            // normalized glyph, take that whole glyph from the new result instead.
            for (n in overlap downTo 0) {
                val cut = wordBoundary(seg.text, spans, n) ?: continue
                if (!trimReplayTail(kept, overlap - n)) continue
                val rest = seg.text.substring(cut)
                kept += Seg(rest, transcriptWords(rest), seg.session)
                return
            }
            kept += seg // No loss when neither spelling has a safe splice boundary.
        }
    }
}

private fun comparisonWordSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var inWord = false
    // A whitespace token can contain new words after the replay ("well-known"). Map word
    // starts to original graphemes: NFKC can expand characters or compose a base and mark.
    for (source in GRAPHEME.findAll(text)) {
        for (part in CHARACTER.findAll(comparisonText(source.value))) {
            if (NOT_WORD.matches(part.value)) {
                inWord = false
            } else {
                if (inWord) spans[spans.lastIndex] = spans.last().first..source.range.last
                else spans += source.range
                inWord = true
            }
        }
    }
    return spans
}

private fun wordBoundary(text: String, spans: List<IntRange>, n: Int): Int? {
    if (n == 0) return 0
    if (n == spans.size) return text.length
    val end = spans[n - 1].last + 1
    val start = spans[n].first
    if (end > start) return null
    // Keep a fresh token's leading punctuation ("-5", quoted text), not just its letters.
    val space = REPLAY_SPACE.find(text.substring(end, start))
    return if (space == null) start else end + space.range.last + 1
}

private fun trimReplayTail(kept: MutableList<Seg>, count: Int): Boolean {
    if (count == 0) return true
    var at = kept.size
    var remaining = count
    while (at > 0 && kept[at - 1].words.size <= remaining) {
        at--
        remaining -= kept[at].words.size
    }
    val shortened = if (remaining > 0) {
        val last = kept[at - 1]
        val retain = last.words.size - remaining
        val cut = wordBoundary(last.text, comparisonWordSpans(last.text), retain) ?: return false
        last.copy(text = jsTrim(last.text.substring(0, cut)), words = last.words.take(retain))
    } else null
    while (kept.size > at) kept.removeAt(kept.lastIndex)
    if (shortened != null) kept[at - 1] = shortened
    return true
}

/**
 * Has the speaker finished, and how long must that stand before it counts?
 *
 * null: the caption does not end with the trigger. 0: it does, in a final the engine sent,
 * so end now. Otherwise the milliseconds it must stand unextended: settleMs when the engine
 * has committed to the trigger, longer when only its unstable words carry it.
 *
 * settleMs does two jobs, as on the web: it lets "we went over budget" prove it was not the
 * end, and it gives the engine time to finalise the clause the trigger appeared in.
 */
fun nativeTriggerWaitMs(
    snapshot: TranscriptSnapshot,
    trigger: String? = DEFAULT_TRIGGER,
    settleMs: Long = DRIVING_SETTLE_MS.toLong(),
): Long? {
    if (!endsWithTrigger(snapshot.text, trigger)) return null
    if (snapshot.settled) return 0
    if (endsWithTrigger(snapshot.stableText, trigger)) return settleMs
    return settleMs * NATIVE_UNSTABLE_SETTLE_FACTOR
}

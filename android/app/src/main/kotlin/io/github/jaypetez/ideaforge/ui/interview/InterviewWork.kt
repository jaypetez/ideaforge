package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.SynthesisResult
import io.github.jaypetez.ideaforge.core.runtime.TurnResult
import io.github.jaypetez.ideaforge.core.runtime.resumeSynthesis
import io.github.jaypetez.ideaforge.core.runtime.resumeTurn
import io.github.jaypetez.ideaforge.core.runtime.runSynthesis
import io.github.jaypetez.ideaforge.core.runtime.runTurn
import io.github.jaypetez.ideaforge.core.setStatusField
import io.github.jaypetez.ideaforge.core.setWrapOffered
import io.github.jaypetez.ideaforge.core.wrapAdvisory
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement

/** A turn's outcome, the session to save for it, and the wrap advisory to show (or null). */
internal typealias SettledTurn = Triple<TurnResult, Session, String?>

/** A write-up's outcome and the session saved for it. */
internal typealias SettledSynthesis = Pair<SynthesisResult, Session>

/**
 * One idea's model calls and their writes: shared by the typed interview
 * ([InterviewViewModel]) and the hands-free drive (voice/DriveController), so a voice turn is
 * persisted exactly as a typed one is, once per turn, with the same pending marker.
 *
 * Every call is started synchronously in [writes] (the app scope), tagged at scheduling
 * time: the caller may go away (Back after Send, Pause mid-turn) and the work still settles
 * for its idea, as an already-submitted request does on the web.
 */
internal class InterviewWork(
    private val sessionId: String,
    private val store: SessionStore,
    private val worker: SessionWorker,
    private val clock: Clock,
    private val writes: CoroutineScope,
) {
    fun now(): JsonElement = num(clock.now())

    /**
     * Ask the next question (or re-issue an interrupted one when [resume]). [recordOffer]
     * marks a wrap advisory as made (the typed screen shows it at once); the drive asks its
     * own question about wrapping and records the offer when it reads it out.
     */
    fun turn(session: Session, provider: Provider?, resume: Boolean, recordOffer: Boolean = true): Deferred<SessionWorker.Outcome<SettledTurn>> {
        val tag = worker.tag(sessionId)
        return writes.async {
            worker.run(
                tag,
                work = {
                    val base = withLibraryMetadata(session, store.get(sessionId), now())
                    val out = if (resume) {
                        resumeTurn(base, provider, now())
                    } else {
                        // The answer, with a marker saying a turn is in flight and which
                        // prompt it sent. If the process dies before the reply, reopening
                        // finds the marker and re-issues the byte-identical prompt.
                        store.save(if (provider != null) withPendingTurn(base, now()) else base)
                        runTurn(base, provider, now())
                    }
                    settle(out, recordOffer)
                },
                commit = { (_, settled, _) -> store.save(settled) },
            )
        }
    }

    /**
     * What a turn's result becomes on disk, and the advisory to show: the offer is recorded
     * (setWrapOffered) so it is made once, as the web's nextQuestion does.
     */
    private fun settle(out: TurnResult, recordOffer: Boolean): SettledTurn {
        val advisory = if (out.aborted) null else wrapAdvisory(out.session, out.wrap)
        val settled = if (advisory != null && recordOffer) setWrapOffered(out.session, true, now()) else out.session
        return Triple(out, settled, advisory)
    }

    /** The write-up. A failed one still finishes the interview: the export works without it. */
    fun writeUp(session: Session, provider: Provider?, resume: Boolean): Deferred<SessionWorker.Outcome<SettledSynthesis>> {
        val tag = worker.tag(sessionId)
        return writes.async {
            worker.run(
                tag,
                work = {
                    val base = withLibraryMetadata(session, store.get(sessionId), now())
                    val result = if (resume) resumeSynthesis(base, provider, now()) else runSynthesis(base, provider, now())
                    val settled = if (result.ok || result.aborted) result.session else setStatusField(result.session, "done", now())
                    result to settled
                },
                commit = { (_, settled) -> store.save(settled) },
            )
        }
    }
}

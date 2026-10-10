package io.github.jaypetez.ideaforge.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Serialises work per session and drops results that arrive for a retired epoch.
 *
 * The Kotlin form of `sessionWork` / `voiceEpoch` in src/ui/app.js. A busy flag is not an
 * identity guard: knowing *that* something is in flight says nothing about *whose* it is.
 * Work is tagged with `(sessionId, epoch)` when it is scheduled; [retire] (leaving an idea,
 * pausing or exiting voice, reopening) bumps the epoch, and a result tagged with the old
 * one is discarded instead of being written into whatever the session has become.
 */
class SessionWorker {
    data class Tag(val sessionId: String, val epoch: Long)

    sealed interface Outcome<out T> {
        data class Committed<T>(val value: T) : Outcome<T>
        data object Stale : Outcome<Nothing>
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val epochs = ConcurrentHashMap<String, AtomicLong>()

    private fun counter(id: String) = epochs.getOrPut(id) { AtomicLong(0) }

    /** The tag for work scheduled now. */
    fun tag(sessionId: String): Tag = Tag(sessionId, counter(sessionId).get())

    /** Invalidates every tag issued so far for [sessionId]. Returns the new epoch. */
    fun retire(sessionId: String): Long = counter(sessionId).incrementAndGet()

    fun isCurrent(tag: Tag): Boolean = counter(tag.sessionId).get() == tag.epoch

    /**
     * Runs [work] under the session's lock, then [commit]s its result only if [tag] is still
     * current. Work for one session never overlaps; work for different sessions may.
     * A tag already stale when the lock is acquired skips the work entirely.
     */
    suspend fun <T> run(
        tag: Tag,
        work: suspend () -> T,
        commit: suspend (T) -> Unit = {},
    ): Outcome<T> = locks.getOrPut(tag.sessionId) { Mutex() }.withLock {
        if (!isCurrent(tag)) return@withLock Outcome.Stale
        val value = work()
        if (!isCurrent(tag)) return@withLock Outcome.Stale
        commit(value)
        Outcome.Committed(value)
    }

    /**
     * Runs [work] under the session's lock regardless of epoch: for work that ends the
     * session's life (a delete) rather than belonging to one epoch of it. It waits for any
     * [run] already holding the lock, and every [run] queued behind it sees its result.
     */
    suspend fun <T> exclusive(sessionId: String, work: suspend () -> T): T =
        locks.getOrPut(sessionId) { Mutex() }.withLock { work() }
}

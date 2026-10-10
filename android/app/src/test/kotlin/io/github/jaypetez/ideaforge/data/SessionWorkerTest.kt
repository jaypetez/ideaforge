package io.github.jaypetez.ideaforge.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkerTest {
    // The bug this guards: a turn finishes after the user left the idea (or paused voice),
    // and its result is written into a session that has moved on.
    @Test
    fun aResultForARetiredEpochIsDropped() = runTest {
        val worker = SessionWorker()
        val gate = CompletableDeferred<String>()
        val committed = mutableListOf<String>()
        val tag = worker.tag("s_1")
        val job = async { worker.run(tag, work = { gate.await() }, commit = { committed += it }) }
        yield()
        worker.retire("s_1")
        gate.complete("late answer")
        assertEquals(SessionWorker.Outcome.Stale, job.await())
        assertTrue(committed.isEmpty())
    }

    @Test
    fun aCurrentResultIsCommitted() = runTest {
        val worker = SessionWorker()
        val committed = mutableListOf<String>()
        val out = worker.run(worker.tag("s_1"), work = { "ok" }, commit = { committed += it })
        assertEquals(SessionWorker.Outcome.Committed("ok"), out)
        assertEquals(listOf("ok"), committed)
    }

    @Test
    fun retiringOneSessionLeavesAnotherAlone() = runTest {
        val worker = SessionWorker()
        val other = worker.tag("s_2")
        worker.retire("s_1")
        assertEquals(SessionWorker.Outcome.Committed(1), worker.run(other, work = { 1 }))
    }

    @Test
    fun workForOneSessionNeverOverlaps() = runTest {
        val worker = SessionWorker()
        var inFlight = 0
        var maxInFlight = 0
        val jobs = (1..5).map {
            async {
                worker.run(worker.tag("s_1"), work = {
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    yield()
                    inFlight--
                })
            }
        }
        jobs.forEach { it.await() }
        assertEquals(1, maxInFlight)
    }
}

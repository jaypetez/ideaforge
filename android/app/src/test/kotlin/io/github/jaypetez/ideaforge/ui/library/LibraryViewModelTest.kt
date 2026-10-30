package io.github.jaypetez.ideaforge.ui.library

import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ioThread = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        ioThread.shutdownNow()
    }

    private fun idea(id: String, at: Long) =
        submitAnswer(seedTurn(createSession(id, num(at)), num(at)), str("an idea"), now = num(at))

    // A delete queued behind another write must not be undone by a rename queued behind it.
    // Before the fix: delete waited on the store's mutex without the session's lock, the
    // rename (tagged after the retire, so current) read the still-indexed idea, queued its
    // save behind the delete, and wrote the idea straight back after the file was gone.
    @Test
    fun aRenameQueuedBehindADeleteDoesNotResurrectTheIdea() {
        val dir = File(tmp.root, "sessions")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = SessionStore(dir, io = ioThread.asCoroutineDispatcher(), writer = { file, bytes ->
            if (file.name.startsWith("s_x_")) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            SessionStore.write(file, bytes)
        })
        val target = "s_a_00000001"
        runBlocking { store.save(idea(target, 1)) }
        val worker = SessionWorker()
        val vm = LibraryViewModel(
            store, IdGen(Clock { 9 }), Clock { 9 }, worker, CoroutineScope(Dispatchers.Unconfined),
            sink = io.github.jaypetez.ideaforge.ui.result.DocumentSink { error("unexpected document write") },
        )

        // Another idea's save holds the store while the delete and the rename arrive.
        val blocker = CoroutineScope(Dispatchers.Default).launch { store.save(idea("s_x_00000002", 2)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vm.delete(target)
        vm.saveDetails(target, "renamed", "")
        release.countDown()

        runBlocking {
            blocker.join()
            // Barriers: everything queued on the session's lock, then on the store, has run.
            worker.run(worker.tag(target), work = {})
            store.load()
        }
        assertNull(store.get(target))
        assertFalse(File(dir, "$target.json").exists())
    }

    // A rename that found nothing to write (the idea was deleted first) must not say saved.
    @Test
    fun aRenameThatWritesNothingShowsNoNotice() {
        val store = SessionStore(File(tmp.root, "sessions"), io = Dispatchers.Unconfined)
        runBlocking { store.save(idea("s_a_00000001", 1)) }
        val vm = LibraryViewModel(
            store, IdGen(Clock { 9 }), Clock { 9 }, SessionWorker(), CoroutineScope(Dispatchers.Unconfined),
            sink = io.github.jaypetez.ideaforge.ui.result.DocumentSink { error("unexpected document write") },
        )
        vm.delete("s_a_00000001")
        assertEquals(LibraryNotice.Deleted, vm.notice.value)
        vm.noticeShown()
        vm.saveDetails("s_a_00000001", "renamed", "")
        vm.setArchived("s_a_00000001", true)
        assertNull(vm.notice.value)
        vm.saveDetails("s_missing_00000002", "renamed", "")
        assertNull(vm.notice.value)
    }
}

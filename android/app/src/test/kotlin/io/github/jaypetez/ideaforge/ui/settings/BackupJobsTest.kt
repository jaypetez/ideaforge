package io.github.jaypetez.ideaforge.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An import outlives its screen. Both tests hold the first import inside its first write
 * (the store's writer seam), which is where Back is most likely to land: after the plan,
 * before the last row is saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupJobsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_760_300_000_000L
    private val uri: Uri = Uri.parse("content://test/web.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val gateOpen = AtomicBoolean(false)

    @After
    fun tearDown() = release.countDown()

    /** A store whose first write waits for [release]. */
    private fun gatedStore() = SessionStore(File(tmp.root, "sessions"), writer = { file, bytes ->
        if (gateOpen.compareAndSet(false, true)) {
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        SessionStore.write(file, bytes)
    })

    private val docs = FakeDocuments().apply {
        file(uri, File(fixturesDir, "backup-from-web.json").readBytes())
    }

    private fun factory(store: SessionStore, jobs: BackupJobs) = viewModelFactory {
        initializer { BackupViewModel(store, docs, Clock { now }, IdGen(Clock { now }), jobs) }
    }

    // Back mid-import, then Settings again: the old ViewModel is cleared and a new one made.
    // Before the fix the new one owned fresh state, so it said nothing was happening and lost
    // the outcome, inviting a second import of the same file.
    @Test
    fun aScreenReopenedMidImportShowsItRunningAndThenItsOutcome() {
        val store = gatedStore()
        val jobs = BackupJobs(scope)

        val firstScreen = ViewModelStore()
        ViewModelProvider.create(firstScreen, factory(store, jobs))[BackupViewModel::class].import(uri)
        assertTrue("the import never reached its first write", entered.await(20, TimeUnit.SECONDS))
        firstScreen.clear()

        val reopened = ViewModelProvider.create(ViewModelStore(), factory(store, jobs))[BackupViewModel::class]
        assertTrue("a running import reads as idle", reopened.state.value.busy)
        release.countDown()

        val state = reopened.settled()
        assertFalse(state.message, state.failed)
        assertEquals("3 imported · 0 kept as copies · 0 duplicates skipped · 1 invalid rows skipped", state.message)
    }

    // The same file imported again while the first import is still writing. Planned from a
    // snapshot that missed the first import's rows, the second made "(imported)" copies of
    // them and then failed on a row that "appeared during the import". Queued, it finds them
    // all already there.
    @Test
    fun aSecondImportWaitsForTheFirstAndAddsNoDuplicates() {
        val store = gatedStore()
        val jobs = BackupJobs(scope)
        val first = ViewModelProvider.create(ViewModelStore(), factory(store, jobs))[BackupViewModel::class]
        first.import(uri)
        assertTrue("the import never reached its first write", entered.await(20, TimeUnit.SECONDS))

        val second = ViewModelProvider.create(ViewModelStore(), factory(store, jobs))[BackupViewModel::class]
        second.import(uri)
        assertTrue(second.state.value.busy)
        // Unqueued, the second import reads the file at once and parks on the store's lock
        // behind the gated write, so its snapshot lands between the first import's writes.
        // Give it the chance to get there; queued (the fix), it never opens the file yet.
        val deadline = System.currentTimeMillis() + 2_000
        while (docs.opens < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(300)
        release.countDown()

        val state = second.settled()
        assertFalse(state.message, state.failed)
        assertEquals("0 imported · 0 kept as copies · 3 duplicates skipped · 1 invalid rows skipped", state.message)
        assertEquals(3, store.sessions.value.size)
        assertTrue(store.sessions.value.none { stringOf(it["name"]).orEmpty().endsWith("(imported)") })
    }
}

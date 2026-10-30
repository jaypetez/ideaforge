package io.github.jaypetez.ideaforge.ui.library

import android.net.Uri
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.addContentReport
import io.github.jaypetez.ideaforge.core.archiveSession
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.exportFilename
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.result.DocumentSink
import io.github.jaypetez.ideaforge.ui.result.ResultVectors
import io.github.jaypetez.ideaforge.ui.result.exportFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryExportsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val written = mutableMapOf<Uri, ByteArrayOutputStream>()
    private lateinit var store: SessionStore
    private lateinit var vm: LibraryViewModel
    private var failure: Exception? = null
    private var closeFails = false
    private var writeFails = false
    private var sessionWriteFails = false

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = SessionStore(File(tmp.root, "sessions"), io = dispatcher, writer = { file, bytes ->
            if (sessionWriteFails) throw IOException("controlled session write refusal")
            SessionStore.write(file, bytes)
        })
        vm = LibraryViewModel(store, IdGen(Clock { 9 }), Clock { 9 }, SessionWorker(),
            CoroutineScope(dispatcher), sink = DocumentSink { uri ->
                failure?.let { throw it }
                val bytes = ByteArrayOutputStream().also { written[uri] = it }
                object : OutputStream() {
                    override fun write(value: Int) {
                        if (writeFails) throw IOException("private write path")
                        bytes.write(value)
                    }
                    override fun close() { if (closeFails) throw IOException("private document path must not appear in UI") }
                }
            }, io = dispatcher)
        idle()
    }

    @After fun tearDown() = Dispatchers.resetMain()
    private fun idle() = scheduler.advanceUntilIdle()
    private fun persist(session: Session) {
        CoroutineScope(dispatcher).launch { store.save(session) }
        idle()
    }
    private fun active(id: String = "s_export_active") =
        submitAnswer(seedTurn(createSession(id, num(1)), num(1)),
            str("Café notes — keep <script>literal</script> and a newline.\nSecond line."), now = num(2))
    private fun id(session: Session) = checkNotNull(stringOf(session["id"]))
    private val uri = Uri.parse("content://documents/owned.md")

    @Test fun savedBytesAreCanonicalForActiveDraftFailedDoneArchivedAndReportedIdeas() {
        val active = active()
        val draft = setDraftAnswer(active("s_export_draft"), str("This is still unsent, not a transcript answer."), num(3))
        val reported = addContentReport(ResultVectors.written, str("r_export"),
            reportableOutput(ResultVectors.written, "result")!!, str("other"), str("Private local note."), num(4))
        for (source in listOf(active, draft, ResultVectors.unwritten, ResultVectors.written,
            archiveSession(ResultVectors.written, num(5)), reported)) {
            persist(source)
            val snapshot = checkNotNull(vm.exports.beginSave(id(source)))
            assertEquals(id(source), snapshot.ideaId)
            assertEquals(exportFilename(source), snapshot.filename)
            vm.exports.saveResult(uri)
            idle()
            assertEquals(LibraryExportResult.Saved, vm.exports.state.value.notice!!.result)
            assertArrayEquals(exportFor(source).encodeToByteArray(), written.getValue(uri).toByteArray())
            assertEquals(source, store.get(id(source)))
            if (source == ResultVectors.written) assertEquals(
                ResultVectors.expected(ResultVectors.markdown("buildExport final")), snapshot.markdown)
            if (source == ResultVectors.unwritten) assertTrue(snapshot.markdown.contains("_Not generated."))
            if (source == reported) assertTrue(snapshot.markdown.contains("## Content reports (saved locally)"))
        }
    }

    @Test fun delayedPickerUsesItsClickedSnapshotAfterRenameFilterAndDeletionWithoutResurrection() {
        val source = active()
        val other = active("s_export_other")
        persist(source); persist(other)
        val snapshot = checkNotNull(vm.exports.beginSave(id(source)))
        assertNull("a second picker cannot replace the pending source", vm.exports.beginSave(id(other)))
        assertNull("sharing cannot replace the pending source", vm.exports.share(id(other)))
        persist(renameSession(source, str("A newer name that was not clicked"), num(5)))
        vm.setQuery("other")
        vm.delete(id(source))
        idle()
        vm.exports.saveResult(uri)
        idle()
        assertArrayEquals(exportFor(source).encodeToByteArray(), written.getValue(uri).toByteArray())
        assertEquals(snapshot.title, vm.exports.state.value.notice!!.title)
        assertNull(store.get(id(source)))
        assertEquals(other, store.get(id(other)))
        assertEquals("other", vm.state.value.filters.query)
        assertEquals("the export result did not clear the newer mutation notice", LibraryNotice.Deleted, vm.notice.value)
    }

    @Test fun cancellationMissingPickerAndLostProcessSourceWriteNothingAndDoNotEraseANewerError() {
        val source = active()
        persist(source)
        vm.exports.beginSave(id(source))
        vm.exports.saveResult(null)
        assertEquals(LibraryExportResult.Cancelled, vm.exports.state.value.notice!!.result)
        val request = checkNotNull(vm.exports.beginSave(id(source)))
        vm.exports.saveUnavailable(request)
        assertEquals(LibraryExportResult.SaveUnavailable, vm.exports.state.value.notice!!.result)
        vm.exports.saveResult(uri)
        assertEquals("a stray result cannot erase the existing error", LibraryExportResult.SaveUnavailable,
            vm.exports.state.value.notice!!.result)
        assertTrue(written.isEmpty())
        vm.exports.dismissNotice()
        vm.exports.saveResult(uri)
        assertEquals(LibraryExportResult.SourceLost, vm.exports.state.value.notice!!.result)
        assertEquals(source, store.get(id(source)))
    }

    @Test fun aLateExportCompletionKeepsANewerMutationErrorAndAMissingSourceNeverExportsAnotherIdea() {
        val source = active()
        persist(source)
        vm.exports.beginSave(id(source))
        sessionWriteFails = true
        vm.saveDetails(id(source), "This rename must fail", "")
        idle()
        assertEquals(LibraryNotice.Failed, vm.notice.value)
        vm.exports.saveResult(uri)
        idle()
        assertEquals("the newer failure remains owned by the mutation", LibraryNotice.Failed, vm.notice.value)
        assertEquals(source, store.get(id(source)))
        assertArrayEquals(exportFor(source).encodeToByteArray(), written.getValue(uri).toByteArray())
        assertNull(vm.exports.share("s_deleted_before_click"))
        assertEquals(LibraryExportResult.SourceLost, vm.exports.state.value.notice!!.result)
        assertEquals(source, store.get(id(source)))
    }

    @Test fun openWriteAndCloseFailuresRemainFailuresWithoutChangingSessionsOrLeakingProviderDetails() {
        val source = active()
        persist(source)
        for (error in listOf(IOException("private path"), SecurityException("private URI"), IllegalArgumentException("bad provider URI"))) {
            failure = error
            vm.exports.beginSave(id(source))
            vm.exports.saveResult(uri)
            idle()
            assertEquals(LibraryExportNotice(sessionDisplayTitle(source), LibraryExportResult.SaveFailed),
                vm.exports.state.value.notice)
            assertFalse(vm.exports.state.value.busy)
            assertEquals(source, store.get(id(source)))
        }
        failure = null
        writeFails = true
        vm.exports.beginSave(id(source))
        vm.exports.saveResult(uri)
        idle()
        assertEquals(LibraryExportResult.SaveFailed, vm.exports.state.value.notice!!.result)
        writeFails = false
        closeFails = true
        vm.exports.beginSave(id(source))
        vm.exports.saveResult(uri)
        idle()
        assertEquals("failed close is not a successful save", LibraryExportResult.SaveFailed,
            vm.exports.state.value.notice!!.result)
        assertEquals(source, store.get(id(source)))
    }
}

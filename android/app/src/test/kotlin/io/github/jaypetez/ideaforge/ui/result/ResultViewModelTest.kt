package io.github.jaypetez.ideaforge.ui.result

import android.net.Uri
import io.github.jaypetez.ideaforge.core.archiveSession
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.restoreSession
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.ui.interview.InterviewViewModel
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.interview.ScriptedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun idle() = scheduler.advanceUntilIdle()

    private fun harness(vararg sessions: JsonObject) = ResultHarness(tmp.newFolder(), dispatcher, *sessions)

    private fun savedBytes(h: ResultHarness, vm: ResultViewModel): ByteArray {
        val uri = Uri.parse("content://documents/export.md")
        vm.save(uri)
        idle()
        assertEquals(ResultNotice.Saved, vm.state.value.notice)
        return h.sink.outputs.getValue(uri).toByteArray()
    }

    // The issue's acceptance criterion: what Save .md writes is the buildExport vector, byte
    // for byte. The vector's expected string came out of the JS core, so this holds the
    // Android save path to the web's Download.
    @Test
    fun savedBytesAreTheBuildExportVector() {
        val h = harness(ResultVectors.written)
        val vm = h.vm()
        idle()
        val expected = ResultVectors.expected(ResultVectors.markdown("buildExport final"))
        assertArrayEquals(expected.encodeToByteArray(), savedBytes(h, vm))
        assertEquals(expected, vm.state.value.export)
    }

    // The web's exportFor switches to checklist mode with its note when there is no refined
    // prompt. A ViewModel that always passed the defaults would still export, with a meta line
    // claiming "synthesised by Claude" for a prompt that was never written.
    @Test
    fun aFailedSynthesisSavesTheChecklistExportVector() {
        val h = harness(ResultVectors.unwritten)
        val vm = h.vm()
        idle()
        val expected = ResultVectors.expected(ResultVectors.markdown("buildExport after a failed synthesis"))
        val saved = savedBytes(h, vm).decodeToString()
        assertEquals(expected, saved)
        assertTrue(saved.contains("_Not generated."))
        assertTrue(saved.contains("> $NO_PROMPT_NOTE"))
        assertTrue(vm.state.value.synthesisFailed)
    }

    // Each walkthrough scenario ends with the export as src/ui/app.js builds it after a
    // wrap-up (exportFor); the result screen must produce the same bytes from that session.
    @Test
    fun everyWalkthroughExportIsWhatTheResultSaves() {
        for (case in ResultVectors.walkthrough) {
            val out = case.expect["value"] as JsonObject
            val session = out["session"] as JsonObject
            val h = harness(session)
            val vm = h.vm(stringOf(session["id"])!!)
            idle()
            assertEquals(case.name, (out["export"] as JsonPrimitive).content, savedBytes(h, vm).decodeToString())
        }
    }

    @Test
    fun theDownloadNameIsTheWebsFilenameRule() {
        val h = harness(ResultVectors.unwritten)
        val vm = h.vm()
        idle()
        assertEquals(ResultVectors.expected(ResultVectors.markdown("exportFilename after a failed synthesis")), vm.state.value.filename)
        val named = harness(ResultVectors.written).vm().also { idle() }
        assertEquals(ResultVectors.expected(ResultVectors.markdown("exportFilename final")), named.state.value.filename)
    }

    @Test
    fun aSaveThatFailsSaysSoAndWritesNothing() {
        val h = harness(ResultVectors.written)
        h.sink.fail = IOException("disk full")
        val vm = h.vm()
        idle()
        vm.save(Uri.parse("content://documents/export.md"))
        idle()
        assertEquals(ResultNotice.SaveFailed("disk full"), vm.state.value.notice)
        assertTrue(h.sink.outputs.isEmpty())
    }

    // renderDone's #done-meta: answered-or-skipped turns, coverage, and the checklist suffix.
    @Test
    fun theMetaLineCountsLikeTheWeb() {
        val written = ResultVectors.written
        assertEquals(DoneMeta(questions = 8, coverage = 79, degraded = false), doneMeta(written))
        val turns = written["turns"] as JsonArray
        val banked = JsonObject(written + ("turns" to JsonArray(turns.mapIndexed { i, t ->
            if (i == 3) JsonObject(t as JsonObject + ("questionSource" to str("bank"))) else t
        })))
        assertTrue(doneMeta(banked).degraded)
    }

    // "Ask me more" is the web's b-reopen: reopen (the vector), then back to the interview.
    @Test
    fun askMoreReopensLikeTheWebAndSaves() {
        val case = ResultVectors.session("reopen a written-up session keeps its prompt, stale")
        val h = harness(ResultVectors.written)
        val vm = h.vm(now = (case.args[1] as JsonPrimitive).content.toLong())
        idle()
        vm.askMore()
        idle()
        assertTrue(vm.state.value.reopened)
        assertEquals(case.expect["value"], h.stored())
        val s = h.stored()!!
        assertEquals("interviewing", stringOf(s["status"]))
        assertTrue("the prompt is kept, marked stale", truthy(s["synthesis"]["stale"]))
        assertEquals(ResultVectors.written.synthesisText(), s.synthesisText())
    }

    // The web's b-reopen also restores an archived idea, so it is back in the library it is
    // being worked on from.
    @Test
    fun askMoreRestoresAnArchivedIdea() {
        val archived = archiveSession(ResultVectors.written, num(1_760_000_006_000))
        val h = harness(archived)
        val vm = h.vm()
        idle()
        vm.askMore()
        idle()
        assertEquals(0.0, (h.stored()!!["archivedAt"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals("interviewing", stringOf(h.stored()!!["status"]))
    }

    // A rename made in the library while the result was open is kept, not overwritten by the
    // copy the screen loaded (the web's withLibraryMetadata concern).
    @Test
    fun askMoreBuildsOnTheLatestStoredCopy() {
        val h = harness(ResultVectors.written)
        val vm = h.vm()
        idle()
        kotlinx.coroutines.runBlocking { h.store.save(renameSession(h.stored()!!, str("Renamed meanwhile"), num(5))) }
        vm.askMore()
        idle()
        assertEquals("Renamed meanwhile", stringOf(h.stored()!!["name"]))
    }

    // After Ask me more the interview opens on a new question rather than bouncing straight
    // back to the result: what the web's nextQuestion does after reopen.
    @Test
    fun theReopenedIdeaOpensItsInterviewOnANewQuestion() {
        val h = harness(ResultVectors.written)
        val vm = h.vm()
        idle()
        vm.askMore()
        idle()
        val model = ScriptedModel(fail = ProviderException("network", "offline"))
        val interview = InterviewViewModel(
            "s_walkthrough", h.store, h.worker, Clock { 1_760_000_009_000 },
            ring = { emptyKeyring() }, providers = ProviderSource { model }, writes = h.writes,
        )
        idle()
        assertFalse("not finished: it asks more", interview.state.value.finished)
        assertNotNull(interview.state.value.openTurn)
        assertEquals(9, (h.stored()!!["turns"] as JsonArray).size)
        assertNotNull(openTurn(h.stored()!!))
    }

    @Test
    fun anIdeaNotInTheStoreSaysSo() {
        val h = harness()
        val vm = h.vm("s_gone")
        idle()
        assertTrue(vm.state.value.missing)
        vm.save(Uri.parse("content://documents/x.md"))
        vm.askMore()
        idle()
        assertTrue(h.sink.outputs.isEmpty())
    }

    @Test
    fun restoreSessionIsTheVector() {
        // The reducer Ask me more uses on an archived idea, held to its vector here as well
        // as in :core, since this is the slice that put it on a user path.
        val case = ResultVectors.session("restoreSession")
        assertEquals(case.expect["value"], restoreSession(case.args[0] as JsonObject, case.args[1]))
    }
}

package io.github.jaypetez.ideaforge.ui.result

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.app.Application
import android.os.Build
import java.io.File
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser
import io.github.jaypetez.ideaforge.R

// Tall, so the whole export composes and every action is on screen.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class ResultScreenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var backs = 0
    private var askedMore = 0
    private val realContentUri = ResultActions.contentUri

    // FileProvider joins canonical paths with `/` and so finds no root for any file under
    // Robolectric on a Windows host (see ResultActions.contentUri). There only, the URI is
    // built the way FileProvider builds it for this paths file; Linux runs the real thing.
    @Before
    fun windowsHostFileProvider() {
        if (File.separatorChar == '\\') {
            ResultActions.contentUri = { c, f ->
                val root = File(c.cacheDir, ResultActions.SHARE_DIR)
                f.relativeTo(root).invariantSeparatorsPath.split('/')
                    .fold(Uri.Builder().scheme("content").authority(ResultActions.authority(c)).appendPath(ResultActions.SHARE_DIR)) { b, seg -> b.appendPath(seg) }
                    .build()
            }
        }
    }

    @After
    fun restore() {
        ResultActions.contentUri = realContentUri
    }

    private fun show(session: JsonObject, synthesisFailed: Boolean = false): Pair<ResultHarness, ResultViewModel> {
        val h = ResultHarness(tmp.newFolder(), Dispatchers.Unconfined, session)
        val vm = h.vm(stringOf(session["id"])!!)
        compose.setContent {
            IdeaForgeTheme {
                ResultScreen(vm, onBack = { backs++ }, onAskMore = { askedMore++ }, synthesisFailed = synthesisFailed)
            }
        }
        compose.waitUntil(5_000) { vm.state.value.session != null }
        compose.waitForIdle()
        return h to vm
    }

    private fun startedIntent(): Intent {
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("an activity was started", intent)
        return intent
    }

    @Test
    fun theExportIsRenderedUnderTheDisplayNameAndMeta() {
        show(renameSession(ResultVectors.written, str("Name tags"), num(1)))
        compose.onNodeWithTag(ResultTags.TITLE).assertTextEquals("Name tags")
        compose.onNodeWithTag(ResultTags.META).assertTextEquals("8 questions · coverage 79%")
        // Rendered, not raw: the heading's text without its `##` marker.
        compose.onNode(hasText("Refined prompt")).assertExists()
        compose.onNode(hasText("## Refined prompt")).assertDoesNotExist()
        compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
    }

    @Test
    fun theTitleFallsBackLikeTheWebsDoneTitle() {
        // No name; the model's title is the display name. The vector's title is its own.
        val (_, vm) = show(ResultVectors.written)
        val expected = stringOf(ResultVectors.written["title"])!!
        compose.onNodeWithTag(ResultTags.TITLE).assertTextEquals(expected)
        assertEquals(expected, vm.state.value.title("fallback"))
    }

    // The issue's third criterion: with the wrap-up failed the result still works, its export
    // says `_Not generated._`, and the screen says what happened.
    @Test
    fun aFailedSynthesisShowsNotGeneratedAndTheMessage() {
        val (_, vm) = show(ResultVectors.unwritten)
        assertTrue(vm.state.value.export.contains("_Not generated. The transcript and open questions below are still complete._"))
        compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertTextContains(NO_PROMPT_NOTE)
        compose.onNode(hasText("Not generated.", substring = true)).assertExists()
        compose.onNodeWithTag(ResultTags.SHARE).performClick()
        assertEquals(vm.state.value.export, startedIntent().getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun theFlagFromTheInterviewShowsTheMessageToo() {
        show(ResultVectors.written, synthesisFailed = true)
        compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertExists()
    }

    // The issue's second criterion: the share sheet opens with text/markdown. The stream is a
    // FileProvider content URI with a read grant, and the text rides along for targets that
    // ignore streams.
    @Test
    fun shareSendsMarkdownThroughTheFileProvider() {
        val (_, vm) = show(ResultVectors.written)
        compose.onNodeWithTag(ResultTags.SHARE).performClick()
        val chooser = startedIntent()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/markdown", send.type)
        assertEquals(vm.state.value.export, send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue("read grant", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.exports", uri.authority)
        assertTrue("named by the web's filename rule", uri.lastPathSegment == vm.state.value.filename)
        assertEquals("the grant travels on ClipData through the chooser", uri, send.clipData!!.getItemAt(0).uri)

        // The file behind the URI is the export, byte for byte, inside cache/exports only.
        val file = fileFor(uri)
        assertTrue(file.canonicalPath.startsWith(File(app.cacheDir, ResultActions.SHARE_DIR).canonicalPath))
        assertEquals(ResultVectors.expected(ResultVectors.markdown("buildExport final")), file.readText())
    }

    /** The file a share URI names: `exports` is the cache-path root in export_paths.xml. */
    private fun fileFor(uri: Uri): File = File(app.cacheDir, uri.path!!.removePrefix("/"))

    private fun shared(session: JsonObject, now: Long): Pair<Uri, String> {
        val export = exportFor(session)
        val intent = ResultActions.shareIntent(app, export, io.github.jaypetez.ideaforge.core.exportFilename(session), "t", now)
        return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!! to export
    }

    // A target may read its URI long after the sheet closes (a queued message, an upload).
    // Shares used to delete every other file and rewrite a same-named one in place, and the
    // name is only per idea per day: the next share killed or truncated the earlier one.
    @Test
    fun anEarlierShareStillReadsItsOwnBytesAfterLaterShares() {
        val t = 1_760_000_000_000
        val (first, firstBytes) = shared(ResultVectors.written, t)
        val (again, _) = shared(ResultVectors.written, t + 1_000)
        assertTrue("same idea, same visible name", first.lastPathSegment == again.lastPathSegment)
        assertTrue("but its own URI", first != again)
        assertEquals("the first share survives a second of the same idea", firstBytes, fileFor(first).readText())

        val (other, otherBytes) = shared(ResultVectors.unwritten, t + 2_000)
        assertEquals("and a share of a different idea", firstBytes, fileFor(first).readText())
        assertEquals(firstBytes, fileFor(again).readText())
        assertEquals(otherBytes, fileFor(other).readText())
    }

    // Only shares past the age limit are cleaned up, at share time and at app start; the
    // directory being written is never one of them.
    @Test
    fun shareDirectoriesPastADayAreCleanedUp() {
        val t = 1_760_000_000_000
        val root = File(app.cacheDir, ResultActions.SHARE_DIR)
        val old = File(root, "${t - ResultActions.SHARE_TTL_MS - 1}-0ld").apply { mkdirs() }
        File(old, "ideaforge-old-2025-01-01.md").writeText("old")
        val young = File(root, "${t - 60 * 60 * 1000}-y0ung").apply { mkdirs() }
        File(young, "ideaforge-young-2025-01-01.md").writeText("young")

        val (uri, bytes) = shared(ResultVectors.written, t)
        assertFalse("past a day: removed", old.exists())
        assertTrue("an hour old: kept", young.exists())
        assertEquals(bytes, fileFor(uri).readText())

        // App start, a day and an hour later: the hour-old share is now past the limit too.
        ResultActions.pruneShares(app, t + ResultActions.SHARE_TTL_MS + 1)
        assertFalse(young.exists())
        assertFalse(fileFor(uri).exists())
    }

    @Test
    fun copyPutsExactlyTheExportOnTheClipboard() {
        val (_, vm) = show(ResultVectors.written)
        compose.onNodeWithTag(ResultTags.COPY).performClick()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(1, clip.itemCount)
        assertEquals(vm.state.value.export, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertFalse(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true))
        // Android 13+ confirms a copy itself; the app adds no second confirmation.
        compose.waitForIdle()
        compose.onNodeWithText("Copied.").assertDoesNotExist()
    }

    @Test
    fun onlyAndroid12AndBelowConfirmTheCopyInApp() {
        assertTrue(ResultActions.confirmsCopyItself(Build.VERSION_CODES.S_V2))
        assertTrue(ResultActions.confirmsCopyItself(Build.VERSION_CODES.Q))
        assertFalse(ResultActions.confirmsCopyItself(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun saveOpensTheDocumentPickerWithTheWebsFilename() {
        val (_, vm) = show(ResultVectors.written)
        compose.onNodeWithTag(ResultTags.SAVE).performClick()
        val intent = startedIntent()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals(vm.state.value.filename, intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun askMoreReopensAndLeavesForTheInterview() {
        val (h, _) = show(ResultVectors.written)
        compose.onNodeWithTag(ResultTags.ASK_MORE).performClick()
        compose.waitUntil(5_000) { askedMore == 1 }
        assertEquals("interviewing", stringOf(h.stored()!!["status"]))
        assertEquals(0, backs)
    }

    @Test
    fun backIsBack() {
        show(ResultVectors.written)
        compose.onNodeWithTag(ResultTags.BACK).performClick()
        assertEquals(1, backs)
        assertEquals(0, askedMore)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    // The provider is private and grants per URI, and its paths file exposes the one cache
    // subdirectory Share writes to: no session file and no keyring is reachable through it.
    @Test
    fun theFileProviderExposesOnlyCacheExports() {
        val info = app.packageManager.resolveContentProvider("${app.packageName}.exports", PackageManager.GET_META_DATA)
        assertNotNull("provider registered under the authority ResultActions uses", info)
        assertEquals("androidx.core.content.FileProvider", info!!.name)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)

        val roots = mutableListOf<String>()
        app.resources.getXml(R.xml.export_paths).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name != "paths") {
                    roots += "${xml.name}:${xml.getAttributeValue(null, "path")}"
                }
            }
        }
        assertEquals(listOf("cache-path:${ResultActions.SHARE_DIR}/"), roots)
    }
}

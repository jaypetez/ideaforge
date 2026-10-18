package io.github.jaypetez.ideaforge.ui.result

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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

    /** Whether the real FileProvider resolved a share URI on this host (see [fileProvider]). */
    private var realProvider = false

    // Two things stand between Robolectric and the real FileProvider.
    //
    // 1. FileProvider caches each authority's roots in a static map (`sCache`) the first time
    //    it is asked. Robolectric gives every test a fresh data directory but keeps the class
    //    loader, so from the second test on the cached root is the FIRST test's cache dir and
    //    every share throws "Failed to find configured root". That is what failed on Linux CI;
    //    a device never sees it because its cache dir cannot move under a running process.
    //    The map is cleared before each test.
    //
    // 2. FileProvider matches roots with `/`-joined canonical paths, so on a Windows host it
    //    finds no root at all. Rather than guess from the OS, ask: share a probe file through
    //    the real provider, and only if that fails build the URI the way FileProvider would.
    //    theRealFileProviderRunsOffWindows makes sure the fallback cannot hide a real failure
    //    anywhere else.
    @Before
    fun fileProvider() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()

        val probe = File(File(app.cacheDir, ResultActions.SHARE_DIR), "probe/probe.md")
        probe.parentFile!!.mkdirs()
        probe.writeText("probe")
        realProvider = runCatching { realContentUri(app, probe) }.isSuccess
        probe.parentFile!!.deleteRecursively()
        if (!realProvider) {
            ResultActions.contentUri = { c, f ->
                val root = File(c.cacheDir, ResultActions.SHARE_DIR)
                f.relativeTo(root).invariantSeparatorsPath.split('/')
                    .fold(Uri.Builder().scheme("content").authority(ResultActions.authority(c)).appendPath(ResultActions.SHARE_DIR)) { b, seg -> b.appendPath(seg) }
                    .build()
            }
        }
    }

    @Test
    fun theRealFileProviderRunsOffWindows() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            assertTrue("the real FileProvider must resolve shares on this host", realProvider)
        }
        // Whatever the host, the provider's cached root is this test's cache dir, not one
        // left over from an earlier test (the probe above populated it on every OS).
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        val strategies = (cache.get(null) as Map<*, *>).values
        assertEquals(1, strategies.size)
        val roots = strategies.first()!!.javaClass.getDeclaredField("mRoots").apply { isAccessible = true }
            .get(strategies.first()) as Map<*, *>
        assertEquals(File(app.cacheDir, ResultActions.SHARE_DIR).canonicalFile, roots["exports"])
    }

    @After
    fun restore() {
        ResultActions.contentUri = realContentUri
    }

    private fun show(session: JsonObject, synthesisFailed: Boolean = false,
        clipboardUnavailable: Boolean = false): Pair<ResultHarness, ResultViewModel> {
        val h = ResultHarness(tmp.newFolder(), Dispatchers.Unconfined, session)
        val vm = h.vm(stringOf(session["id"])!!)
        compose.setContent {
            IdeaForgeTheme {
                val actual = LocalContext.current
                val actionContext = remember(actual, clipboardUnavailable) {
                    if (clipboardUnavailable) object : ContextWrapper(actual) {
                        override fun getSystemService(name: String): Any? =
                            if (name == Context.CLIPBOARD_SERVICE) null else super.getSystemService(name)
                    } else actual
                }
                CompositionLocalProvider(LocalContext provides actionContext) {
                    ResultScreen(vm, onBack = { backs++ }, onAskMore = { askedMore++ }, synthesisFailed = synthesisFailed)
                }
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

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun expandedResultGivesCopyThePrimaryActionAndKeepsSaveVisible() {
        show(ResultVectors.written)
        compose.onNode(hasTestTag(ResultTags.COPY) and hasAnyAncestor(hasTestTag("result-primary-action"))).assertExists()
        compose.onNodeWithTag(ResultTags.SAVE).assertExists()
    }

    @Test
    fun compactResultGivesShareThePrimaryActionAndKeepsCopyVisible() {
        show(ResultVectors.written)
        compose.onNode(hasTestTag(ResultTags.SHARE) and hasAnyAncestor(hasTestTag("result-primary-action"))).assertExists()
        compose.onNodeWithTag(ResultTags.COPY).assertExists()
    }

    @Test
    fun unavailableClipboardIsAnExplicitFailureRatherThanAnUncaughtClick() {
        show(ResultVectors.written, clipboardUnavailable = true)
        compose.onNodeWithTag(ResultTags.COPY).performClick()
        compose.onNodeWithText("could not be copied", substring = true).assertExists()
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

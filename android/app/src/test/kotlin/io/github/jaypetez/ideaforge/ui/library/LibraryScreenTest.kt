package io.github.jaypetez.ideaforge.ui.library

import android.content.Intent
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.view.View
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.archiveSession
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.setSessionTags
import io.github.jaypetez.ideaforge.core.setStatusField
import io.github.jaypetez.ideaforge.core.setTitle
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import io.github.jaypetez.ideaforge.ui.result.ResultActions
import io.github.jaypetez.ideaforge.ui.result.ResultVectors
import io.github.jaypetez.ideaforge.ui.result.exportFor
import io.github.jaypetez.ideaforge.core.exportFilename

// A tall compact phone: the lazy list only composes what fits, and every card must fit.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private lateinit var dir: File
    private lateinit var store: SessionStore
    private val now = 5_000_000L
    private val opened = mutableListOf<LibraryItem>()
    private var newIdeas = 0
    private var inputMode: InputModeManager? = null
    private var owner: View? = null
    private val realContentUri = ResultActions.contentUri

    @Before
    fun setUp() {
        dir = File(tmp.root, "sessions")
        store = SessionStore(dir, io = Dispatchers.Unconfined)
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After fun restoreShareProvider() { ResultActions.contentUri = realContentUri }

    private fun prepareShareProvider() {
        val app = RuntimeEnvironment.getApplication()
        val probe = File(File(app.cacheDir, ResultActions.SHARE_DIR), "probe/probe.md")
        probe.parentFile!!.mkdirs()
        probe.writeText("probe")
        try {
            realContentUri(app, probe)
        } catch (error: IllegalArgumentException) {
            assertTrue("the real FileProvider is required off Windows",
                System.getProperty("os.name").orEmpty().startsWith("Windows"))
            ResultActions.contentUri = { context, file ->
                file.relativeTo(File(context.cacheDir, ResultActions.SHARE_DIR)).invariantSeparatorsPath.split('/')
                    .fold(Uri.Builder().scheme("content").authority(ResultActions.authority(context)).appendPath(ResultActions.SHARE_DIR)) {
                        builder, segment -> builder.appendPath(segment)
                    }.build()
            }
        } finally { probe.parentFile!!.deleteRecursively() }
    }

    /** An idea as the interview leaves it: the seed question answered, so `opening` is set. */
    private fun idea(
        id: String,
        at: Long,
        opening: String,
        name: String? = null,
        title: String? = null,
        tags: List<String> = emptyList(),
        archived: Boolean = false,
        done: Boolean = false,
    ): Session {
        var s = submitAnswer(seedTurn(createSession(id, num(at)), num(at)), str(opening), now = num(at))
        if (title != null) s = setTitle(s, str(title), num(at))
        if (name != null) s = renameSession(s, str(name), num(at))
        if (tags.isNotEmpty()) s = setSessionTags(s, arr(tags.map(::str)), num(at))
        if (done) s = setStatusField(s, "done", num(at))
        if (archived) s = archiveSession(s, num(at))
        return s
    }

    private fun show(vararg sessions: Session, shareFailure: RuntimeException? = null): LibraryViewModel {
        runBlocking { sessions.forEach { store.save(it) } }
        // Unconfined writes: a click's save has landed on disk by the time performClick returns.
        val vm = LibraryViewModel(
            store, IdGen(Clock { now }), Clock { now }, SessionWorker(), CoroutineScope(Dispatchers.Unconfined),
            sink = io.github.jaypetez.ideaforge.ui.result.DocumentSink { error("unexpected document write") },
        )
        compose.setContent {
            IdeaForgeTheme {
                inputMode = LocalInputModeManager.current
                owner = LocalView.current
                val actual = LocalContext.current
                val context = if (shareFailure != null) object : ContextWrapper(actual) {
                    override fun startActivity(intent: Intent) {
                        if (intent.action == Intent.ACTION_CHOOSER) throw shareFailure
                        super.startActivity(intent)
                    }
                } else actual
                CompositionLocalProvider(LocalContext provides context) {
                    LibraryScreen(vm, open = { opened += it }, newIdea = { newIdeas++ }, onboarding = {})
                }
            }
        }
        return vm
    }

    private val garden = "s_a_00000001"
    private val podcast = "s_b_00000002"
    private val recipes = "s_c_00000003"
    private val tax = "s_d_00000004"

    private fun library() = arrayOf(
        idea(garden, 4_000, "A planner for my vegetable garden", tags = listOf("home", "outdoors")),
        idea(podcast, 3_000, "A podcast about tomatoes and soil", title = "Soil talk", tags = listOf("media")),
        idea(recipes, 2_000, "Turn family recipes into a book", name = "Grandma's cookbook", tags = listOf("home")),
        idea(tax, 1_000, "Automate my tax receipts", archived = true, tags = listOf("money")),
    )

    private fun cardCount() = compose.onAllNodesWithTag(LibraryTags.CARD).fetchSemanticsNodes().size

    private fun shown(tag: String) = runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun inDialog(text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun stored(id: String) = store.get(id)

    private fun filters() {
        compose.onNodeWithTag(LibraryTags.FILTERS).performClick()
    }

    private fun details(id: String) {
        compose.onNodeWithTag(LibraryTags.more(id)).performClick()
        compose.onNodeWithTag(LibraryTags.details(id)).performClick()
    }

    // name -> title -> opening, the one display order the web and the export share.
    @Test
    fun cardsShowTheDisplayNameInNameTitleOpeningOrder() {
        show(*library())
        compose.onNodeWithText("A planner for my vegetable garden").assertExists()
        compose.onNodeWithText("Soil talk").assertExists()
        compose.onNodeWithText("Grandma's cookbook").assertExists()
        compose.onNodeWithText("Turn family recipes into a book").assertDoesNotExist()
        compose.onNodeWithText("3 of 4 ideas").assertExists()
    }

    @Test
    fun anEmptyLibraryShowsTheZeroStateNotTheNoMatchState() {
        show()
        compose.onNodeWithTag(LibraryTags.ZERO).assertIsDisplayed()
        compose.onNodeWithText("No ideas yet. Start one with New idea.").assertExists()
        compose.onNodeWithTag(LibraryTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.NEW).performClick()
        assertEquals(1, newIdeas)
    }

    @Test
    fun newIdeaHasAnAccessibleLabelAndTheExistingClickAction() {
        show()
        compose.onNodeWithTag(LibraryTags.NEW)
            .assert(hasContentDescription("New idea") and hasClickAction())
            .performClick()
        assertEquals(1, newIdeas)
    }

    @Test
    fun unavailableStorageIsNotAnEmptyLibraryAndRetryReadsThePreservedRows() {
        dir.writeText("owned inaccessible storage")
        show()
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryTags.UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText("Ideas unavailable").assertExists()
        compose.onNodeWithTag(LibraryTags.ZERO).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.RETRY).performClick()
        compose.waitForIdle()
        assertEquals("owned inaccessible storage", dir.readText())
        compose.onNodeWithTag(LibraryTags.UNAVAILABLE).assertIsDisplayed()
        assertTrue(dir.delete())
        assertTrue(dir.mkdirs())
        val saved = idea(garden, 1, "A preserved garden idea")
        File(dir, "$garden.json").writeBytes(SessionStore.encode(saved))
        compose.onNodeWithTag(LibraryTags.RETRY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryTags.UNAVAILABLE).assertDoesNotExist()
        compose.onNodeWithText("A preserved garden idea").assertExists()
        assertEquals(saved, store.get(garden))
    }

    @Test
    fun searchMatchesAnswersAndShowsTheNoMatchState() {
        val vm = show(*library())
        assertEquals(3, cardCount())
        // "tomatoes" is only in the podcast's answer, never its title.
        compose.onNodeWithTag(LibraryTags.SEARCH).performTextInput("TOMATOES")
        compose.waitForIdle()
        assertEquals(listOf(podcast), vm.state.value.items.map { it.id })
        assertEquals(1, cardCount())
        compose.onNodeWithText("Soil talk").assertExists()

        compose.onNodeWithTag(LibraryTags.SEARCH).performTextReplacement("submarine")
        compose.waitForIdle()
        assertEquals(0, cardCount())
        compose.onNodeWithTag(LibraryTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("No ideas match these filters.").assertExists()
        compose.onNodeWithTag(LibraryTags.ZERO).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTags.SEARCH).performTextClearance()
        compose.waitForIdle()
        assertEquals(3, cardCount())
    }

    @Test
    fun aTagChipFiltersAndTogglesOff() {
        show(*library())
        filters()
        compose.onNodeWithTag(LibraryTags.tagChip("home")).performClick()
        compose.waitForIdle()
        assertEquals(2, cardCount())
        compose.onNodeWithText("Soil talk").assertDoesNotExist()
        compose.onNodeWithText("1 of 4 ideas").assertDoesNotExist()
        compose.onNodeWithText("2 of 4 ideas").assertExists()

        compose.onNodeWithTag(LibraryTags.tagChip("home")).performClick()
        compose.waitForIdle()
        assertEquals(3, cardCount())
    }

    @Test
    fun theArchivedToggleShowsOnlyArchivedIdeas() {
        show(*library())
        filters()
        compose.onNodeWithTag(LibraryTags.ARCHIVED).assertIsOff()
        compose.onNodeWithTag(LibraryTags.ARCHIVED).assert(hasContentDescription("Show archived"))
        compose.onNodeWithText("Automate my tax receipts").assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.ARCHIVED).performClick()
        compose.waitForIdle()
        assertEquals(1, cardCount())
        compose.onNodeWithText("Automate my tax receipts").assertExists()
    }

    @Test
    fun archiveAndRestoreGoThroughTheReducersAndBumpUpdatedAt() {
        show(*library())
        compose.onNodeWithTag(LibraryTags.more(garden)).performClick()
        compose.onNodeWithText("Archive").performClick()
        compose.waitForIdle()
        val archived = stored(garden)!!
        assertEquals(now.toDouble(), archived["archivedAt"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(now.toDouble(), archived["updatedAt"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(2, cardCount())
        compose.onNodeWithText("A planner for my vegetable garden").assertDoesNotExist()

        filters()
        compose.onNodeWithTag(LibraryTags.ARCHIVED).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryTags.more(garden)).performClick()
        compose.onNodeWithText("Restore").performClick()
        compose.waitForIdle()
        assertEquals(0.0, stored(garden)!!["archivedAt"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun renameAndRetagSaveTheNameOverrideAndMoveTheIdeaToTheTop() {
        val vm = show(*library())
        val before = stored(podcast)!!
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithText("Rename or tag").performClick()
        compose.onNodeWithTag(LibraryTags.NAME).assert(hasAnyAncestor(isDialog()))
        compose.onNodeWithTag(LibraryTags.NAME).performTextInput("  The   soil   show ")
        compose.onNodeWithTag(LibraryTags.TAGS).performTextReplacement("Media, audio, media")
        compose.onNodeWithText("Save details").performClick()
        compose.waitForIdle()

        val after = stored(podcast)!!
        // normalizeSessionName collapses the spaces; normalizeTags drops the case-blind repeat.
        assertEquals("The soil show", after["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("Media", "audio"), (after["tags"] as JsonArray).map { it.jsonPrimitive.content })
        // The model's title is untouched: name is the override, not a replacement.
        assertEquals("Soil talk", after["title"]!!.jsonPrimitive.content)
        assertEquals(now.toDouble(), after["updatedAt"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertTrue(after["rev"]!!.jsonPrimitive.content.toDouble() > before["rev"]!!.jsonPrimitive.content.toDouble())
        assertEquals(podcast, vm.state.value.items.first().id)
        compose.waitForIdle()
        compose.onNodeWithText("The soil show").assertExists()
        compose.onNodeWithText("Soil talk").assertDoesNotExist()
        filters()
        compose.onNodeWithTag(LibraryTags.tagChip("audio")).assertExists()
    }

    @Test
    fun cancellingARenameKeepsTheStoredIdeaAndReturnsToTheList() {
        show(*library())
        val before = stored(recipes)
        compose.runOnIdle {
            assertTrue(checkNotNull(inputMode).requestInputMode(InputMode.Keyboard))
            checkNotNull(owner).requestFocus()
        }
        val origin = compose.onNodeWithTag(LibraryTags.more(recipes))
        origin.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        origin.assertIsFocused().performClick()
        compose.onNodeWithText("Rename or tag").performClick()
        compose.onNodeWithTag(LibraryTags.NAME).performTextReplacement("Do not save this")
        inDialog("Cancel").performClick()
        compose.onNodeWithTag(LibraryTags.EDITOR).assertDoesNotExist()
        origin.assertIsDisplayed().assertIsFocused()
        assertEquals(before, stored(recipes))
    }

    @Test
    fun backDismissesTheRenameWithoutMutatingTheIdea() {
        show(*library())
        val before = stored(podcast)
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithText("Rename or tag").performClick()
        compose.onNodeWithTag(LibraryTags.NAME).performTextReplacement("Do not save this either")
        compose.runOnIdle {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog() as? OnBackPressedDispatcherOwner)
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryTags.EDITOR).assertDoesNotExist()
        assertEquals(before, stored(podcast))
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithText("Rename or tag").performClick()
        compose.onNodeWithTag(LibraryTags.EDITOR).assertIsDisplayed()
        inDialog("Cancel").performClick()
    }

    @Test
    fun noMatchOffersOneActionThatClearsEveryFilter() {
        val vm = show(*library())
        filters()
        compose.onNodeWithTag(LibraryTags.tagChip("home")).performClick()
        compose.onNodeWithTag(LibraryTags.ARCHIVED).performClick()
        compose.onNodeWithTag(LibraryTags.SEARCH).performTextInput("submarine")
        compose.onNodeWithTag(LibraryTags.CLEAR).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(LibraryFilters(), vm.state.value.filters)
        assertEquals(3, cardCount())
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp", fontScale = 2f)
    fun enlargedRowsGrowAndTheirMoreTargetsRemainReachableWithoutFocusingSearch() {
        show(*library())
        compose.onNodeWithTag(LibraryTags.SEARCH).assertIsNotFocused()
        compose.onAllNodesWithTag(LibraryTags.CARD).onFirst().assertHeightIsAtLeast(96.dp)
        compose.onNodeWithTag(LibraryTags.more(garden)).performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp", fontScale = 2f)
    fun enlargedRenameDialogKeepsItsEditorsAndFullyPaintedActionsReachable() {
        show(idea(garden, 1_000, "A planner with a complete accessible title"))
        val before = stored(garden)
        compose.onNodeWithTag(LibraryTags.more(garden)).performClick()
        compose.onNodeWithText("Rename or tag").performClick()
        for (tag in listOf(LibraryTags.NAME, LibraryTags.TAGS)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        for (label in listOf("Save details", "Cancel")) {
            inDialog(label).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("the actual action text was measured", layouts.isNotEmpty())
            for (layout in layouts) {
                // Use the recorder's painted-line checks; MultiParagraph retains the max constraint.
                val lines = (0 until layout.lineCount).toList()
                val width = lines.maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
                val height = lines.maxOfOrNull(layout::getLineBottom) ?: 0f
                assertTrue("$label is fully painted at font scale 2.0: ${width}x$height in ${layout.size}",
                    width <= layout.size.width + 1f && height <= layout.size.height + 1f &&
                        !layout.multiParagraph.didExceedMaxLines && lines.none(layout::isLineEllipsized))
            }
        }
        inDialog("Cancel").performClick()
        assertEquals(before, stored(garden))
    }

    @Test
    fun deleteAsksFirstAndCancelKeepsTheIdea() {
        show(*library())
        compose.onNodeWithTag(LibraryTags.more(recipes)).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Permanently delete “Grandma's cookbook”?").assertExists()
        inDialog("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(stored(recipes) != null)
        assertTrue(File(dir, "$recipes.json").exists())
        assertEquals(3, cardCount())
    }

    @Test
    fun confirmedDeleteRemovesTheIdeaFromDiskAndTheList() {
        show(*library())
        compose.onNodeWithTag(LibraryTags.more(recipes)).performClick()
        compose.onNodeWithText("Delete").performClick()
        inDialog("Delete").performClick()
        compose.waitForIdle()
        assertNull(stored(recipes))
        assertFalse(File(dir, "$recipes.json").exists())
        assertEquals(2, cardCount())
        compose.onNodeWithText("Grandma's cookbook").assertDoesNotExist()
    }

    // Every idea archived is "nothing matches", not "no ideas yet": the ideas exist.
    @Test
    fun allIdeasArchivedIsTheNoMatchState() {
        show(idea(tax, 1_000, "Automate my tax receipts", archived = true))
        compose.onNodeWithTag(LibraryTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTags.ZERO).assertDoesNotExist()
    }

    // A compact width: one pane at a time.
    @Test
    fun aCompactWindowShowsOnePaneAndMoreOpensTheSecondaryDetail() {
        show(*library())
        assertTrue(shown(LibraryTags.LIST))
        assertFalse(shown(LibraryTags.DETAIL))
        details(podcast)
        compose.waitForIdle()
        assertTrue(shown(LibraryTags.DETAIL))
        assertFalse(shown(LibraryTags.LIST))
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf(podcast), opened.map { it.id })
    }

    @Test
    fun aCompactIdeaOpensItsPrimaryActionWithoutVisitingDetails() {
        show(*library())
        compose.onNodeWithText("Soil talk").performClick()
        assertEquals(listOf(podcast), opened.map { it.id })
    }

    @Test
    fun saveFromARowUsesThatIdeaWithoutOpeningTheInterviewOrSelectingDetails() {
        val source = library()[1]
        show(*library())
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithText("Save .md").performClick()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals(io.github.jaypetez.ideaforge.core.exportFilename(source), intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(opened.isEmpty())
        assertTrue(shown(LibraryTags.LIST))
        assertFalse(shown(LibraryTags.DETAIL))
        assertEquals(source, stored(podcast))
    }

    @Test
    fun sharingEveryApplicableStateUsesExactCanonicalBytesNamesMimeAndReadGrants() {
        prepareShareProvider()
        fun copyId(source: Session, id: String) = kotlinx.serialization.json.JsonObject(source + ("id" to str(id)))
        val active = idea(garden, 1, "Café notes — <script>literal</script>\nSecond line.")
        val draft = io.github.jaypetez.ideaforge.core.setDraftAnswer(idea(podcast, 2, "An unfinished interview"),
            str("Unsent draft stays on this device"), num(3))
        val failed = copyId(ResultVectors.unwritten, "s_export_failed")
        val archived = archiveSession(copyId(ResultVectors.written, "s_export_archived"), num(now))
        val reported = io.github.jaypetez.ideaforge.core.addContentReport(copyId(ResultVectors.written, "s_export_reported"),
            str("r_library"), io.github.jaypetez.ideaforge.core.reportableOutput(ResultVectors.written, "result")!!,
            str("other"), str("A private local report."), num(now))
        val sources = listOf(active, draft, failed, ResultVectors.written, archived, reported)
        val vm = show(*sources.toTypedArray())
        val previous = mutableListOf<Pair<File, ByteArray>>()
        for (source in sources) {
            val id = source["id"]!!.jsonPrimitive.content
            compose.runOnIdle {
                vm.clearFilters()
                vm.setShowArchived(io.github.jaypetez.ideaforge.core.js.truthy(source["archivedAt"]))
            }
            val list = compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction() and
                hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
            list.performScrollToNode(hasTestTag(LibraryTags.more(id)))
            compose.onNodeWithTag(LibraryTags.more(id)).performClick()
            compose.onNodeWithTag(LibraryTags.share(id)).performClick()
            val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/markdown", send.type)
            assertEquals(exportFor(source), send.getStringExtra(Intent.EXTRA_TEXT))
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("content", uri.scheme)
            assertEquals(ResultActions.authority(RuntimeEnvironment.getApplication()), uri.authority)
            assertEquals(exportFilename(source), uri.lastPathSegment)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            val file = File(RuntimeEnvironment.getApplication().cacheDir, uri.path!!.removePrefix("/"))
            val bytes = exportFor(source).encodeToByteArray()
            org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
            previous += file to bytes
            previous.forEach { (prior, expected) -> org.junit.Assert.assertArrayEquals(expected, prior.readBytes()) }
            assertEquals(source, stored(id))
            assertTrue(opened.isEmpty())
            assertFalse(shown(LibraryTags.DETAIL))
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp")
    fun exportingAnUnselectedRowKeepsTheSelectedIdeaAndSiblingControls() {
        prepareShareProvider()
        show(*library())
        compose.onNodeWithText("Grandma's cookbook").performClick()
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithTag(LibraryTags.share(podcast)).performClick()
        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(exportFor(library()[1]), send.getStringExtra(Intent.EXTRA_TEXT))
        compose.onNode(hasText("Grandma's cookbook") and hasAnyAncestor(hasTestTag(LibraryTags.DETAIL))).assertExists()
        compose.onNodeWithTag(LibraryTags.share(recipes)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTags.save(recipes)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTags.more(podcast)).assert(
            androidx.compose.ui.test.SemanticsMatcher("no clickable row ancestor") { node ->
                generateSequence(node.parent) { it.parent }.none { it.config.contains(SemanticsActions.OnClick) }
            })
        assertTrue(opened.isEmpty())
    }

    @Test
    fun anUnavailableShareIsNamedPersistentAndDoesNotExposePrivateExceptionDetails() {
        prepareShareProvider()
        show(*library(), shareFailure = ActivityNotFoundException("private exception detail"))
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithTag(LibraryTags.share(podcast)).performClick()
        compose.onNodeWithTag(LibraryTags.EXPORT_NOTICE).assertExists()
        compose.onNodeWithText("Cannot open Share .md for “Soil talk”. Try Save .md.").assertExists()
        compose.onNodeWithText("private exception detail", substring = true).assertDoesNotExist()
        assertEquals(library()[1], stored(podcast))
        assertTrue(opened.isEmpty())
        compose.onNodeWithTag(LibraryTags.EXPORT_DISMISS).performClick()
        compose.onNodeWithTag(LibraryTags.EXPORT_NOTICE).assertDoesNotExist()
    }

    @Test
    fun aBinderSizeFailureIsAnExplicitShareErrorRatherThanAnUncaughtClick() {
        prepareShareProvider()
        show(*library(), shareFailure = RuntimeException(android.os.TransactionTooLargeException("private oversized payload")))
        compose.onNodeWithTag(LibraryTags.more(podcast)).performClick()
        compose.onNodeWithTag(LibraryTags.share(podcast)).performClick()
        compose.onNodeWithTag(LibraryTags.EXPORT_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("private oversized payload", substring = true).assertDoesNotExist()
        assertEquals(library()[1], stored(podcast))
        assertTrue(opened.isEmpty())
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp")
    fun aFailureFromTheLastLibraryRowIsVisibleWithoutScrollingBackToTheTop() {
        prepareShareProvider()
        val sources = (1..30).map { idea("s_export_row_$it", it.toLong(), "Local conversation $it") }
        show(*sources.toTypedArray(), shareFailure = ActivityNotFoundException("controlled missing app"))
        val lastId = "s_export_row_1"
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
            .performScrollToNode(hasTestTag(LibraryTags.more(lastId)))
        compose.onNodeWithTag(LibraryTags.more(lastId)).performClick()
        compose.onNodeWithTag(LibraryTags.share(lastId)).performClick()
        compose.onNodeWithTag(LibraryTags.EXPORT_NOTICE).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTags.EXPORT_DISMISS).assertIsDisplayed()
        assertEquals(sources.first(), stored(lastId))
        assertTrue(opened.isEmpty())
        assertFalse(shown(LibraryTags.DETAIL))
    }

    @Test
    @Config(qualifiers = "w390dp-h720dp", fontScale = 2f)
    fun aMaximumNameFailureKeepsItsCompleteMessageAndLibraryControlsReachable() {
        prepareShareProvider()
        val name = "A deliberately long idea name ".padEnd(120, 'x')
        show(idea(garden, 1, "A local conversation", name = name),
            shareFailure = RuntimeException(android.os.TransactionTooLargeException("controlled Binder limit")))
        compose.onNodeWithTag(LibraryTags.more(garden)).performScrollTo().performClick()
        compose.onNodeWithTag(LibraryTags.share(garden)).performScrollTo().performClick()
        val message = compose.onNodeWithText("Share .md failed for “$name”. Your idea is unchanged.")
        message.assertIsDisplayed()
        val node = message.fetchSemanticsNode()
        assertTrue("the named error is fully visible, not a clipped live region",
            node.layoutInfo.coordinates.size.height <= node.boundsInRoot.height + 1)
        compose.onNodeWithTag(LibraryTags.EXPORT_DISMISS).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(LibraryTags.more(garden)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aDelayedPickerCancellationPreservesNewerSearchFocusAndFilters() {
        val vm = show(*library())
        compose.runOnIdle { vm.exports.beginSave(podcast) }
        compose.onNodeWithTag(LibraryTags.SEARCH).performTextInput("soil")
        compose.onNodeWithTag(LibraryTags.SEARCH).assertIsFocused()
        compose.runOnIdle { vm.exports.saveResult(null) }
        compose.onNodeWithTag(LibraryTags.SEARCH).assertIsFocused()
        assertEquals("soil", vm.state.value.filters.query)
        assertEquals(library()[1], stored(podcast))
        assertTrue(opened.isEmpty())
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp", fontScale = 2f)
    fun enlargedExportMenuAndSelectedDetailKeepNativeTargetsReachable() {
        show(idea(garden, 1, "A local conversation", name = "A deliberately long idea name ".repeat(4)))
        compose.onNodeWithTag(LibraryTags.more(garden)).performClick()
        for (tag in listOf(LibraryTags.share(garden), LibraryTags.save(garden), LibraryTags.details(garden))) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithTag(LibraryTags.details(garden)).performClick()
        for (tag in listOf(LibraryTags.share(garden), LibraryTags.save(garden))) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNode(hasText("Continue") and hasClickAction()).performScrollTo().assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aSeedWithoutAnyRecordedAnswerDoesNotOfferAnEmptyExport() {
        show(seedTurn(createSession(garden, num(1)), num(1)))
        compose.onNodeWithTag(LibraryTags.more(garden)).performClick()
        compose.onNodeWithTag(LibraryTags.share(garden)).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.save(garden)).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.details(garden)).assertExists()
        assertTrue(opened.isEmpty())
    }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp")
    fun anActivePreviewShowsConversationWithoutInventingAFailedWriteUp() {
        val draft = "<script>literal unsent text, not markup</script>"
        val session = io.github.jaypetez.ideaforge.core.setDraftAnswer(library()[1], str(draft), num(now))
        val before = io.github.jaypetez.ideaforge.core.buildExport(session)
        show(session)
        compose.onNodeWithText("Soil talk").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No write-up yet").assertIsDisplayed()
        compose.onNodeWithText("A podcast about tomatoes and soil").assertExists()
        compose.onNodeWithText("Unsent draft").assertExists()
        compose.onNodeWithText(draft).assertExists()
        compose.onNode(hasText("Not generated.", substring = true)).assertDoesNotExist()
        assertEquals(before, io.github.jaypetez.ideaforge.core.buildExport(stored(podcast)))
    }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp")
    fun filtersCannotLeaveAnUnrelatedIdeaInTheSelectedPane() {
        show(*library())
        compose.onNodeWithText("Soil talk").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryTags.SEARCH).performTextInput("vegetable")
        compose.waitForIdle()
        compose.onNodeWithText("Select an idea to see its summary.").assertIsDisplayed()
        compose.onNode(hasText("Soil talk") and hasAnyAncestor(hasTestTag(LibraryTags.DETAIL)))
            .assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp")
    fun anExpandedWindowShowsTheListAndTheDetailSideBySide() {
        show(*library())
        assertTrue(shown(LibraryTags.LIST))
        assertTrue(shown(LibraryTags.DETAIL))
        compose.onNodeWithText("Select an idea to see its summary.").assertIsDisplayed()

        compose.onNodeWithText("Soil talk").performClick()
        compose.waitForIdle()
        // Both panes stay: the list keeps its cards while the detail shows the export preview.
        assertTrue(shown(LibraryTags.LIST))
        assertTrue(shown(LibraryTags.DETAIL))
        assertEquals(3, cardCount())
        // The headline and the export's "# Soil talk".
        compose.onAllNodes(hasText("Soil talk") and hasAnyAncestor(hasTestTag(LibraryTags.DETAIL))).onFirst().assertIsDisplayed()
        compose.onNode(hasText("Transcript") and hasAnyAncestor(hasTestTag(LibraryTags.DETAIL))).assertExists()
    }

    // sessionLibraryStatus says "archived" before "completed", so the badge cannot decide the
    // button: a finished idea that was archived still opens its result, as on the web.
    @Test
    fun aFinishedArchivedIdeaOpensItsResult() {
        show(idea(tax, 1_000, "Automate my tax receipts", archived = true, done = true))
        filters()
        compose.onNodeWithTag(LibraryTags.ARCHIVED).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("View result").performClick()
        assertEquals(listOf(tax), opened.map { it.id })
        assertTrue(opened.single().done)
        assertTrue(opened.single().archived)
    }
}

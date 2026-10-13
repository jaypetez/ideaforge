package io.github.jaypetez.ideaforge.ui.library

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// A tall compact phone: the lazy list only composes what fits, and every card must fit.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp")
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

    @Before
    fun setUp() {
        dir = File(tmp.root, "sessions")
        store = SessionStore(dir, io = Dispatchers.Unconfined)
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

    private fun show(vararg sessions: Session): LibraryViewModel {
        runBlocking { sessions.forEach { store.save(it) } }
        // Unconfined writes: a click's save has landed on disk by the time performClick returns.
        val vm = LibraryViewModel(
            store, IdGen(Clock { now }), Clock { now }, SessionWorker(), CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            IdeaForgeTheme {
                LibraryScreen(vm, open = { opened += it }, newIdea = { newIdeas++ }, onboarding = {})
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
        // The editor lives in the detail pane; back on the list, the card has the new name.
        compose.onNodeWithText("All ideas").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The soil show").assertExists()
        compose.onNodeWithText("Soil talk").assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.tagChip("audio")).assertExists()
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
    fun aCompactWindowShowsOnePaneAndACardOpensTheDetail() {
        show(*library())
        assertTrue(shown(LibraryTags.LIST))
        assertFalse(shown(LibraryTags.DETAIL))
        compose.onNodeWithText("Soil talk").performClick()
        compose.waitForIdle()
        assertTrue(shown(LibraryTags.DETAIL))
        assertFalse(shown(LibraryTags.LIST))
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf(podcast), opened.map { it.id })
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
        compose.onNodeWithTag(LibraryTags.ARCHIVED).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Automate my tax receipts").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("View result").performClick()
        assertEquals(listOf(tax), opened.map { it.id })
        assertTrue(opened.single().done)
        assertTrue(opened.single().archived)
    }
}

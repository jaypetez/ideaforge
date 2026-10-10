package io.github.jaypetez.ideaforge

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import io.github.jaypetez.ideaforge.ui.onboarding.hasUsableProvider
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The README walkthrough, end to end on a real device: MainActivity launched with
 * `--es provider scripted`, the interview answered through the screen (typed answers and a
 * chip), Write it up, and the Result screen showing the export. WalkthroughVectorTest proves
 * the same run byte for byte under Robolectric; this proves the real app, on a real Android
 * runtime, actually gets there. CI runs it on a Gradle managed emulator
 * (.github/workflows/android-device.yml).
 *
 * A degraded interview looks like a healthy one: a failed model call is answered from the
 * built-in checklist and the run still reaches a result. So every question is compared with
 * the scripted reply for its turn, and the checklist marker must never appear.
 *
 * Onboarding is passed by seeding the keyring before launch, through the app's own
 * KeyringStore, rather than by any hook in the app. On a device that already has a usable
 * provider nothing is written; otherwise the original keyring is put back afterwards, and the
 * idea this run created is deleted, so a shared emulator is left as it was found. That
 * cleanup is @After, so it needs the instrumentation alive: an app crash kills the
 * instrumentation process with it, and then neither runs. A device that had no usable
 * provider is left holding the dummy Groq key, and the interrupted idea stays in the library.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WalkthroughDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (target.applicationContext as IdeaForgeApp).graph

    private var scenario: ActivityScenario<MainActivity>? = null
    private var restore: (suspend () -> Unit)? = null
    // Null until @Before has read the store: a throw before then leaves nothing to delete.
    private var knownBefore: Set<String>? = null

    @Before
    fun seedAUsableProvider() = runBlocking {
        val keyring = graph.keyringStore
        val state = keyring.load()
        check(state.status != KeyringState.Status.UNREADABLE) { "the keyring on this device is unreadable: ${state.problem}" }
        if (!hasUsableProvider(state.ring)) {
            val original = state.ring
            val hadOne = state.status == KeyringState.Status.STORED
            // Any provider that counts as usable will do: the scripted provider replaces the
            // interview's model, so this key is never sent anywhere.
            keyring.save(withCreds(original, "groq", JsonObject(mapOf("apiKey" to JsonPrimitive("gsk_instrumented_walkthrough")))))
            restore = { if (hadOne) keyring.save(original) else keyring.forget() }
        }
        graph.sessionStore.load()
        knownBefore = graph.sessionStore.knownIds()
    }

    // The keyring restore runs whatever else throws: closing the activity and deleting the
    // idea are tidiness, but a dummy key left behind changes how the device's app opens.
    @After
    fun leaveTheDeviceAsFound() = runBlocking {
        try {
            runCatching { scenario?.close() }
            knownBefore?.let { before ->
                runCatching {
                    for (id in graph.sessionStore.knownIds() - before) runCatching { graph.sessionStore.delete(id) }
                }
            }
        } finally {
            restore?.invoke()
        }
        Unit
    }

    @Test
    fun theScriptedWalkthroughReachesTheResultScreen() {
        // The vector case with typed answers and a chip; the script is the one the debug
        // build's ScriptedProvider replays, read from the same debug asset.
        val vector = Json.parseToJsonElement(target.assets.open(ScriptedProvider.ASSET).use { it.readBytes().decodeToString() })
        val case = (vector["cases"] as JsonArray).single { stringOf(it["name"]) == "a chip answer" }
        val script = ScriptedProvider.loadScript(target)
        val steps = case["input"]["steps"] as JsonArray
        assertTrue("the case answers with a chip", steps.any { it["chip"] != null })

        val intent = Intent(target, MainActivity::class.java).putExtra("provider", "scripted")
        scenario = ActivityScenario.launch(intent)

        // The library, not onboarding: the seeded keyring is usable.
        compose.waitUntilAtLeastOneExists(hasTestTag(BOTTOM_BAR), TIMEOUT)
        compose.onNode(hasText("New idea") and hasAnyAncestor(hasTestTag(BOTTOM_BAR))).performClick()

        var turn = 1
        for (step in steps) {
            val expected = if (turn == 1) SEED_QUESTION else stringOf(script["turns"][turn.toString()]["question"])
            awaitOpenQuestion()
            assertEquals("turn $turn is the scripted question, not the checklist", expected, text(compose.onNodeWithTag("question")))
            compose.onNodeWithTag("degraded").assertDoesNotExist()

            when {
                truthy(step["skip"]) -> compose.onNodeWithTag("skip").performClick()
                step["chip"] != null -> {
                    compose.onNodeWithTag("chip-${toNumber(step["chip"]).toInt()}").performClick()
                    compose.onNodeWithTag("send").performClick()
                }
                else -> {
                    compose.onNodeWithTag("answer").performTextInput(stringOf(step["text"])!!)
                    compose.onNodeWithTag("send").performClick()
                }
            }
            turn++
            // The next question, or the offer to wrap up: whichever the run reaches first.
            compose.waitUntil(TIMEOUT) {
                exists("wrap-offer") || (!exists("interview-busy") && exists("question") && questionText() != expected)
            }
            if (exists("wrap-offer")) break
        }
        assertTrue("the run reached past the first few turns ($turn)", turn > 4)

        // Accept the offer, or press "Write it up" when the answers ran out first.
        compose.onNodeWithTag(if (exists("wrap-offer")) "wrap-accept" else "write-up").performClick()

        compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.SCREEN), TIMEOUT)
        compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.OUTPUT), TIMEOUT)
        val title = stringOf(script["synthesis"]["title"])!!
        assertEquals(title, text(compose.onNodeWithTag(ResultTags.TITLE)))
        compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()

        // The export's `## ` sections, rendered as headings: the markdown source the vector
        // recorded for this case names them, so the test cannot drift from the export format.
        val export = stringOf(case["expect"]["value"]["export"])!!
        val headings = export.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
        assertTrue("the export has ## headings", headings.isNotEmpty())
        val output = hasAnyAncestor(hasTestTag(ResultTags.OUTPUT))
        for (heading in headings) {
            compose.onNode(hasText(heading) and output).assertExists("the result shows the \"## $heading\" section")
        }
    }

    private fun exists(tag: String) =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun questionText(): String? =
        compose.onAllNodes(hasTestTag("question")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun awaitOpenQuestion() = compose.waitUntil(TIMEOUT) {
        !exists("interview-busy") && exists("question")
    }

    private fun text(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private companion object {
        // Generous: an emulator in CI runs on software rendering.
        const val TIMEOUT = 30_000L
    }
}

package io.github.jaypetez.ideaforge.ui.onboarding

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.providers.httpError
import io.github.jaypetez.ideaforge.usableRing
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private val library = hasText("No ideas yet. Start one with New idea.")

    @Test
    fun aHostedKeyReachesTheLibraryInTwoSteps() {
        val check = FakeCheck()
        val graph = installTestGraph(check)
        launch()

        // Step 1: the provider cards, with no bottom bar to escape through.
        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithText("Step 1 of 2").assertExists()
        compose.onNodeWithText("Ideas").assertDoesNotExist()
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()

        // Step 2: the key, pasted with the trailing newline a clipboard usually adds.
        compose.onNodeWithText("Step 2 of 2").assertExists()
        compose.onNodeWithTag("api-key").performTextInput("  gsk_live_test_key_1234\n")
        compose.onNodeWithTag("check-key").performClick()

        waitFor(library)
        compose.onNodeWithText("Ideas").assertExists()
        assertEquals(listOf("groq"), check.calls.map { it.first })
        assertEquals("gsk_live_test_key_1234", check.calls.single().second.apiKey)

        val state = graph.keyringStore.state.value
        assertEquals(KeyringState.Status.STORED, state.status)
        assertEquals(JsonPrimitive("groq"), state.ring["active"])
        assertEquals(JsonPrimitive("gsk_live_test_key_1234"), credsFor(state.ring, "groq")["apiKey"])

        // Onboarding is off the back stack: Back from the library leaves the app.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        scenario!!.onActivity { assertTrue("Back returned to onboarding", it.isFinishing) }
    }

    @Test
    fun aRefusedKeyShowsTheMappedMessageAndStays() {
        // The fake throws what the real adapter would: httpError's reading of a 401.
        val check = FakeCheck { _, _ -> throw httpError(unauthorized(), "api.groq.com") }
        val graph = installTestGraph(check)
        launch()

        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithTag("api-key").performTextInput("gsk_wrong")
        compose.onNodeWithTag("check-key").performClick()

        waitFor(hasTestTag("check-failure"))
        compose.onNodeWithText("That key was refused", substring = true).assertExists()
        compose.onNodeWithText("api.groq.com 401: Invalid API Key — check the API key in Settings").assertExists()
        // Still on step 2, nothing saved, and the library never appeared.
        compose.onNodeWithText("Step 2 of 2").assertExists()
        compose.onNodeWithTag("api-key").assertExists()
        compose.onNode(library).assertDoesNotExist()
        assertEquals(KeyringState.Status.NONE, graph.keyringStore.state.value.status)
    }

    @Test
    fun aLocalServerDiscoversModelsAndAsksForNoKey() {
        val check = FakeCheck { _, _ -> listOf("llama3.2:3b", "qwen2.5:7b-instruct") }
        val graph = installTestGraph(check)
        launch()

        waitFor(hasTestTag("provider-ollama"))
        compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()

        compose.onNodeWithTag("api-key").assertDoesNotExist()
        compose.onNodeWithText("API key").assertDoesNotExist()
        compose.onNodeWithText("http://localhost:11434/v1").assertExists()
        compose.onNodeWithText("so its server is http://10.0.2.2:11434/v1", substring = true).assertExists()

        compose.onNodeWithTag("find-models").performClick()
        waitFor(hasText("2 models installed."))
        compose.onNodeWithTag("model-qwen2.5:7b-instruct").performScrollTo().performClick()
        compose.onNodeWithTag("finish-local").performScrollTo().performClick()

        waitFor(library)
        val config = check.calls.single().second
        assertEquals("ollama", config.kind)
        assertEquals("", config.apiKey)
        assertEquals("http://localhost:11434/v1", config.baseUrl)

        val creds = credsFor(graph.keyringStore.state.value.ring, "ollama")
        assertEquals(JsonPrimitive("qwen2.5:7b-instruct"), creds["model"])
        assertEquals(JsonPrimitive("http://localhost:11434/v1"), creds["baseUrl"])
        assertEquals(JsonPrimitive(""), creds["apiKey"])
    }

    @Test
    fun aReturningUserOpensOnTheLibrary() {
        installTestGraph(ring = usableRing())
        launch()
        waitFor(library)
        compose.onNodeWithTag("provider-groq").assertDoesNotExist()
        compose.onNodeWithText("Step 1 of 2").assertDoesNotExist()
    }

    private fun unauthorized(): Response = Response.Builder()
        .request(Request.Builder().url("https://api.groq.com/openai/v1/models").build())
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .body("""{"error":{"message":"Invalid API Key","code":"invalid_api_key"}}""".toResponseBody())
        .build()
}

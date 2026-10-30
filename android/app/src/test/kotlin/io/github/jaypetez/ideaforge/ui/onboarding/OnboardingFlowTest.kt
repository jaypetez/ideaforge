package io.github.jaypetez.ideaforge.ui.onboarding

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.ui.settings.MemoryDataStore
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.installUnreadableGraph
import io.github.jaypetez.ideaforge.providers.httpError
import io.github.jaypetez.ideaforge.usableRing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    private fun cancelModeChoice() {
        waitFor(hasTestTag("new-idea-choice"))
        compose.onNodeWithTag("new-idea-voice").assertIsDisplayed()
        compose.onNodeWithTag("new-idea-type").assertIsDisplayed()
        compose.onNodeWithTag("new-idea-cancel").performClick()
        waitFor(library)
    }

    private fun expectRetainedKey() {
        compose.onNodeWithText("Show").performClick()
        assertEquals("scripted-unsaved-provider-key",
            compose.onNodeWithTag("api-key").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Hide").performClick()
    }

    @Test
    fun onlyRecordedProviderValuesCanBeCalledSavedAndTheyAreNotACheck() {
        val check = FakeCheck()
        val graph = installTestGraph(check, preferences = PrefsStore(MemoryDataStore()))
        launch()
        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithText("Settings for this provider are saved", substring = true).assertDoesNotExist()
        compose.onNodeWithText("A previous connection check succeeded.", substring = true).assertDoesNotExist()
        runBlocking { graph.keyringStore.save(usableRing()) }
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithText("Settings for this provider are saved", substring = true).assertExists()
        compose.onNodeWithText("A previous connection check succeeded.", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("check-key").performScrollTo().assertIsNotEnabled()
        assertTrue("saved values never run a new check", check.calls.isEmpty())
    }

    @Test
    fun backAndChangeKeepTheUnsubmittedFieldsWithoutCheckingOrSavingThem() {
        val check = FakeCheck()
        val graph = installTestGraph(check, preferences = PrefsStore(MemoryDataStore()))
        launch()
        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithTag("api-key").performTextInput("scripted-unsaved-provider-key")
        scenario!!.recreate()
        waitFor(hasTestTag("api-key"))
        expectRetainedKey()
        compose.onNodeWithText("Settings for this provider are saved", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        expectRetainedKey()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
        compose.onNodeWithTag("base-url").performTextReplacement("http://192.168.1.30:11434/v1")
        compose.onNodeWithTag("model").performScrollTo().performTextInput("scripted-unsaved-model")
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        expectRetainedKey()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
        assertEquals("http://192.168.1.30:11434/v1",
            compose.onNodeWithTag("base-url").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("scripted-unsaved-model",
            compose.onNodeWithTag("model").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(check.calls.isEmpty())
        assertEquals(KeyringState.Status.NONE, graph.keyringStore.state.value.status)
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun expandedOnboardingHasASeparateSupportingInformationPane() {
        installTestGraph(FakeCheck())
        launch()
        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag("onboarding-support").assertIsDisplayed()
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-support").assertIsDisplayed()
        compose.onNodeWithTag("api-key").assertIsDisplayed()
        val field = compose.onNodeWithTag("api-key").getUnclippedBoundsInRoot()
        val supporting = compose.onNodeWithTag("onboarding-support").getUnclippedBoundsInRoot()
        assertTrue("required fields keep a bounded reading width", field.right - field.left <= IdeaForgeWidths.form)
        assertTrue("support is beside, not over, the required form", supporting.left >= field.right)
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp", fontScale = 2f)
    fun enlargedExpandedFormsUseOneScrollableColumnWhenBothPanesCannotFit() {
        installTestGraph(FakeCheck())
        launch()
        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag("onboarding-support").assertDoesNotExist()
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithTag("api-key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("inference-consent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("check-key").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun privacyPolicyFooterOpensFromBothOnboardingStepsWithEnlargedText() {
        val check = FakeCheck()
        installTestGraph(check)
        launch()
        waitFor(hasTestTag("provider-groq"))

        for (step in 1..2) {
            compose.onNodeWithText("Step $step of 2").assertExists()
            compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).performClick()
            scenario!!.onActivity {
                val intent = shadowOf(it).nextStartedActivity
                assertNotNull("step $step opens an external activity", intent)
                assertEquals(Intent.ACTION_VIEW, intent?.action)
                assertEquals("https://jaypetez.github.io/ideaforge/guide/privacy-policy.html", intent?.dataString)
            }
            if (step == 1) compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        }
        assertTrue("reading the policy never checks a provider", check.calls.isEmpty())
    }

    @Test
    fun aHostedKeyOffersAModeChoiceAfterTwoConnectionSteps() {
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
        compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
        compose.onNodeWithTag("check-key").performScrollTo().performClick()

        cancelModeChoice()
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
        compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
        compose.onNodeWithTag("check-key").performScrollTo().performClick()

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

        compose.onNodeWithTag("find-models").performScrollTo().performClick()
        waitFor(hasText("2 models installed."))
        compose.onNodeWithTag("model-qwen2.5:7b-instruct").performScrollTo().performClick()
        compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
        compose.onNodeWithTag("finish-local").performScrollTo().performClick()

        cancelModeChoice()
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
    fun aMalformedCatalogueIsNotProofAndAValidEmptyCatalogueCanBeRetried() {
        val reply = AtomicReference("<html>This is not a model API</html>")
        CatalogueCheck { reply.get() }.use { catalogue ->
            val graph = installTestGraph(catalogue.checker, preferences = PrefsStore(MemoryDataStore()))
            launch()
            waitFor(hasTestTag("provider-ollama"))
            compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
            compose.onNodeWithTag("find-models").performScrollTo().performClick()
            waitFor(hasTestTag("check-failure") or hasText("A previous connection check succeeded."))
            assertEquals("/v1/models", catalogue.requests.single().url.encodedPath)
            assertEquals("GET", catalogue.requests.single().method)
            assertEquals(null, catalogue.requests.single().header("authorization"))
            compose.onNodeWithText("The server answered, but not in a way this app understands.").assertExists()
            compose.onNodeWithText("A previous connection check succeeded.").assertDoesNotExist()
            compose.onNodeWithText("That server answered, but it has no models. Pull one first.").assertDoesNotExist()
            assertEquals(KeyringState.Status.NONE, graph.keyringStore.state.value.status)

            reply.set("""{"data":[]}""")
            compose.onNodeWithTag("find-models").performScrollTo().performClick()
            waitFor(hasText("That server answered, but it has no models. Pull one first."))
            compose.onNodeWithText("A previous connection check succeeded.").assertExists()
            compose.onNodeWithTag("check-failure").assertDoesNotExist()
            assertEquals(2, catalogue.requests.size)
            assertEquals(KeyringState.Status.NONE, graph.keyringStore.state.value.status)
        }
    }

    @Test
    fun aMalformedHostedCatalogueCannotSaveAKeyOrFinishOnboarding() {
        CatalogueCheck { """{"choices":[]}""" }.use { catalogue ->
            val graph = installTestGraph(catalogue.checker, preferences = PrefsStore(MemoryDataStore()))
            launch()
            waitFor(hasTestTag("provider-groq"))
            compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
            compose.onNodeWithTag("api-key").performTextInput("scripted-catalogue-key")
            compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
            compose.onNodeWithTag("check-key").performScrollTo().performClick()
            waitFor(hasTestTag("check-failure") or hasTestTag("new-idea-choice"))
            assertEquals("https://api.groq.com/openai/v1/models", catalogue.requests.single().url.toString())
            assertEquals("GET", catalogue.requests.single().method)
            assertEquals(KeyringState.Status.NONE, graph.keyringStore.state.value.status)
            compose.onNodeWithText("The server answered, but not in a way this app understands.").assertExists()
            compose.onNodeWithText("A previous connection check succeeded.").assertDoesNotExist()
            compose.onNodeWithText("Step 2 of 2").assertExists()
            compose.onNodeWithTag("new-idea-choice").assertDoesNotExist()
        }
    }

    @Test
    fun aRetiredCatalogueResponseCannotReplaceAnotherProvidersProof() {
        val release = CountDownLatch(1)
        CatalogueCheck { request ->
            if (request.url.port == 11434) {
                if (!release.await(10, TimeUnit.SECONDS)) throw IOException("the retired catalogue was not released")
                "<html>Late malformed response</html>"
            } else """{"data":[{"id":"replacement-model"}]}"""
        }.use { catalogue ->
            try {
                installTestGraph(catalogue.checker, preferences = PrefsStore(MemoryDataStore()))
                launch()
                waitFor(hasTestTag("provider-ollama"))
                compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
                compose.onNodeWithTag("find-models").performScrollTo().performClick()
                compose.waitUntil(10_000) { catalogue.requests.size == 1 }
                compose.onNodeWithText("Back").performScrollTo().performClick()
                compose.onNodeWithTag("provider-lmstudio").performScrollTo().performClick()
                compose.onNodeWithTag("find-models").performScrollTo().performClick()
                waitFor(hasTestTag("model-replacement-model"))
                release.countDown()
                compose.waitUntil(10_000) { catalogue.idle }
                compose.waitForIdle()
                assertEquals(listOf(11434, 1234), catalogue.requests.map { it.url.port })
                compose.onNodeWithText("A previous connection check succeeded.").assertExists()
                compose.onNodeWithTag("model-replacement-model").assertExists()
                compose.onNodeWithTag("check-failure").assertDoesNotExist()
                compose.onNodeWithText("That server answered, but it has no models. Pull one first.").assertDoesNotExist()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun aReturningUserOpensOnTheLibrary() {
        installTestGraph(ring = usableRing())
        launch()
        waitFor(library)
        compose.onNodeWithTag("provider-groq").assertDoesNotExist()
        compose.onNodeWithText("Step 1 of 2").assertDoesNotExist()
    }

    @Test
    fun aLocalAddressTheAppWouldRefuseIsNeverSaved() {
        // The real check: createProvider refuses a public host before any request is made.
        val graph = installTestGraph(ProviderCheck.Default)
        launch()

        waitFor(hasTestTag("provider-ollama"))
        compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
        compose.onNodeWithTag("base-url").performTextClearance()
        compose.onNodeWithTag("base-url").performTextInput("https://api.example.com/v1")
        compose.onNodeWithTag("find-models").performClick()
        waitFor(hasTestTag("check-failure"))
        compose.onNodeWithText("api.example.com is not on a private network", substring = true).assertExists()

        // Typing a model after the refusal must not make the address acceptable:
        // the keyring would then count as usable and every interview would fail.
        compose.onNodeWithTag("model").performTextInput("qwen2.5:7b-instruct")
        compose.onNodeWithTag("finish-local").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("inference-consent").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Enter a valid local server address before agreeing", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNode(library).assertDoesNotExist()
        assertEquals(KeyringState.Status.NONE, graph.keyringStore.state.value.status)
    }

    @Test
    fun anUnreadableKeyringIsExplainedAndARetryThatWorksOpensTheLibrary() {
        val (graph, flaky, lost) = installUnreadableGraph(usableRing())
        launch()

        waitFor(hasTestTag("keyring-problem"))
        compose.onNodeWithText("Your saved keys could not be read").assertExists()
        compose.onNodeWithText("is missing", substring = true).assertExists()
        compose.onNodeWithTag("provider-groq").assertDoesNotExist()

        flaky.keys[0] = lost // the Keystore answers this time
        compose.onNodeWithTag("keyring-retry").performClick()
        waitFor(library)
        assertEquals(KeyringState.Status.STORED, graph.keyringStore.state.value.status)
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land", fontScale = 2f)
    fun unreadableKeyRecoveryKeepsBothActionsReachableWithEnlargedTextInAShortWindow() {
        installUnreadableGraph(usableRing())
        launch()
        waitFor(hasTestTag("keyring-problem"))
        for (tag in listOf("keyring-retry", "keyring-setup")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun settingUpOverAnUnreadableKeyringReplacesItOnlyWhenConfirmed() {
        val (graph, _, _) = installUnreadableGraph(usableRing())
        val before = graph.keyringStore.file.readBytes()
        launch()

        waitFor(hasTestTag("keyring-setup"))
        compose.onNodeWithTag("keyring-setup").performClick()
        waitFor(hasTestTag("provider-anthropic"))
        compose.onNodeWithTag("provider-anthropic").performScrollTo().performClick()
        compose.onNodeWithTag("api-key").performTextInput("sk-ant-new-key")
        compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
        compose.onNodeWithTag("check-key").performScrollTo().performClick()

        // The reload before the save still failed: nothing is written until the user says so.
        waitFor(hasTestTag("unreadable-keyring"))
        compose.onNode(library).assertDoesNotExist()
        assertArrayEquals("the unreadable keyring was overwritten", before, graph.keyringStore.file.readBytes())

        compose.onNodeWithTag("replace-unreadable").performScrollTo().performClick()
        cancelModeChoice()
        val ring = graph.keyringStore.state.value.ring
        assertEquals(JsonPrimitive("anthropic"), ring["active"])
        assertEquals(JsonPrimitive("sk-ant-new-key"), credsFor(ring, "anthropic")["apiKey"])
    }

    // The Keystore recovers while the user is in onboarding: the save re-reads the keyring and
    // builds on it, so the other provider's key survives and no confirmation is needed.
    @Test
    fun aKeyringThatRecoversBeforeTheSaveKeepsItsOtherRecords() {
        val (graph, flaky, lost) = installUnreadableGraph(usableRing())
        launch()

        waitFor(hasTestTag("keyring-setup"))
        compose.onNodeWithTag("keyring-setup").performClick()
        waitFor(hasTestTag("provider-anthropic"))
        compose.onNodeWithTag("provider-anthropic").performScrollTo().performClick()
        compose.onNodeWithTag("api-key").performTextInput("sk-ant-new-key")
        flaky.keys[0] = lost
        compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
        compose.onNodeWithTag("check-key").performScrollTo().performClick()

        cancelModeChoice()
        val ring = graph.keyringStore.state.value.ring
        assertEquals(JsonPrimitive("anthropic"), ring["active"])
        assertEquals(JsonPrimitive("gsk_returning_user_key_0001"), credsFor(ring, "groq")["apiKey"])
    }

    private fun unauthorized(): Response = Response.Builder()
        .request(Request.Builder().url("https://api.groq.com/openai/v1/models").build())
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .body("""{"error":{"message":"Invalid API Key","code":"invalid_api_key"}}""".toResponseBody())
        .build()
}

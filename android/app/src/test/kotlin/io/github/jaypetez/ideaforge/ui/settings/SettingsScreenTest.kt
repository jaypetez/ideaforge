package io.github.jaypetez.ideaforge.ui.settings

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.BuildConfig
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withTts
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SoftwareKey
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.ui.onboarding.CatalogueCheck
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.usableRing
import io.github.jaypetez.ideaforge.voice.DeviceVoice
import io.github.jaypetez.ideaforge.voice.FakeEngine
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.TtsEngine
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** The settings screen inside the real app shell, with a software keyring key. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun openSettings(ring: JsonObject, key: SoftwareKey = SoftwareKey(),
        engine: TtsEngine = FakeEngine(), preferences: PrefsStore? = null,
        checker: ProviderCheck = FakeCheck()): AppGraph {
        val graph = installTestGraph(check = checker, ring = ring, key = key, ttsEngine = engine, preferences = preferences)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasTestTag(NAVIGATION))
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
        waitFor(hasTestTag(SettingsTags.ACTIVE))
        return graph
    }

    private fun pick(id: String) {
        compose.onNodeWithTag(SettingsTags.PROVIDER).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.provider(id)).performClick()
        compose.waitForIdle()
    }

    private fun localRing() = withCreds(
        emptyKeyring(), "ollama",
        JsonObject(mapOf("baseUrl" to JsonPrimitive("http://10.0.2.2:11434/v1"), "model" to JsonPrimitive("qwen2.5:7b-instruct"))),
    )

    @Test
    fun aMalformedCatalogueShowsAnErrorInsteadOfSuccessfulConnectionStatus() {
        val reply = AtomicReference("<html>This is not a model API</html>")
        CatalogueCheck { reply.get() }.use { catalogue ->
            val ring = localRing()
            val graph = openSettings(ring, checker = catalogue.checker, preferences = PrefsStore(MemoryDataStore()))
            compose.onNodeWithTag(SettingsTags.MODEL).performScrollTo().performTextClearance()
            compose.onNodeWithTag(SettingsTags.MODEL).performTextInput("retained-model")
            compose.onNodeWithTag(SettingsTags.CHECK).performScrollTo().performClick()
            waitFor(hasTestTag(SettingsTags.STATUS) and (
                hasText("The server answered, but not in a way this app understands.", substring = true) or
                    hasText("That server answered, but it has no models. Pull one first.")
                ))
            assertEquals("GET", catalogue.requests.single().method)
            assertEquals("/v1/models", catalogue.requests.single().url.encodedPath)
            compose.onNodeWithTag(SettingsTags.STATUS)
                .assertTextContains("Error: The server answered, but not in a way this app understands.", substring = true)
            compose.onNodeWithTag(SettingsTags.MODEL).assertTextContains("retained-model")
            assertEquals(ring, graph.keyringStore.state.value.ring)

            reply.set("""{"models":[]}""")
            compose.onNodeWithTag(SettingsTags.CHECK).performScrollTo().performClick()
            waitFor(hasText("That server answered, but it has no models. Pull one first."))
            compose.onNodeWithTag(SettingsTags.STATUS)
                .assertTextContains("That server answered, but it has no models. Pull one first.")
            compose.onNodeWithTag(SettingsTags.MODEL).assertTextContains("retained-model")
            assertEquals(2, catalogue.requests.size)
            assertEquals(ring, graph.keyringStore.state.value.ring)
        }
    }

    // A local server has no key: the field is not hidden, it is not there at all, so nothing
    // can read a value back out of it. Switching to a hosted provider brings it back.
    @Test
    fun aLocalProviderNeverShowsAKeyField() {
        openSettings(localRing())
        compose.onNodeWithTag(SettingsTags.ACTIVE).assertTextContains("Ollama", substring = true)
        compose.onNodeWithTag(SettingsTags.KEY).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.BASE_URL).assertExists()
        compose.onNodeWithTag(SettingsTags.MODEL).assertExists()

        pick("groq")
        compose.onNodeWithTag(SettingsTags.KEY).assertExists()
        compose.onNodeWithTag(SettingsTags.BASE_URL).assertDoesNotExist()

        pick("custom")
        compose.onNodeWithTag(SettingsTags.KEY).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.BASE_URL).assertExists()
    }

    // The saved key appears masked, never in full.
    @Test
    fun theActiveKeyIsMasked() {
        openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.ACTIVE).assertTextContains("gsk_re…0001", substring = true)
        compose.onNode(hasText("gsk_returning_user_key_0001", substring = true)).assertDoesNotExist()
    }

    @Test
    fun allFiveSectionsHaveUsefulActualStateSummaries() {
        openSettings(usableRing())
        for (section in listOf("connection", "voice", "appearance", "data", "about")) {
            compose.onNodeWithTag("settings-section-$section").assertExists()
            compose.onNodeWithTag("settings-summary-$section").assertExists()
        }
        compose.onNodeWithTag("settings-summary-connection").assertTextContains("Groq", substring = true)
        compose.onNodeWithTag("settings-summary-appearance").assertTextContains("system", substring = true, ignoreCase = true)
        compose.onNodeWithTag("settings-summary-about").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
    }

    @Test
    fun aDeviceVoiceSelectorOffersTheEngineDefault() {
        openSettings(usableRing())
        compose.onNodeWithTag("settings-device-voice").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-device-voice-default").assertIsDisplayed()
            .assertTextContains("Engine default", substring = true)
    }

    @Test
    fun selectingADeviceVoicePersistsThroughSettingsRecreationAndConfiguresTheNextNativeSpeak() {
        val id = "en-gb-x-rjs-network"
        val engine = FakeEngine().apply { details = listOf(DeviceVoice(id, "en-GB", true)) }
        val prefs = PrefsStore(MemoryDataStore(mutablePreferencesOf(
            booleanPreferencesKey("hostedSpeechAllowed") to true,
            booleanPreferencesKey("hostedTranscriptionAllowed") to true,
        )))
        val ring = withTts(usableRing(), JsonObject(mapOf(
            "kind" to JsonPrimitive("openai"), "apiKey" to JsonPrimitive("separate-speech-key"),
            "voice" to JsonPrimitive("cedar"), "verified" to JsonPrimitive(true),
        )))
        val graph = openSettings(ring, engine = engine, preferences = prefs)
        val generation = graph.keyringStore.generation.value
        val before = runBlocking { prefs.current() }
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.deviceVoice(id)).performScrollTo().assertIsDisplayed()
            .assertTextContains("en-GB", substring = true).performClick()
        compose.waitUntil(10_000) { runBlocking { prefs.current().speechVoice } == id }
        scenario!!.recreate()
        waitFor(hasTestTag(SettingsTags.DEVICE_VOICE))
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().assertTextContains(id, substring = true)

        assertTrue("Settings only enumerates; it never speaks", engine.spoken.isEmpty())
        assertEquals("enumeration never configures a voice", null, engine.voice)
        assertNull(graph.driveController.state.value.mode)
        assertEquals(ring, graph.keyringStore.state.value.ring)
        assertEquals(generation, graph.keyringStore.generation.value)
        assertEquals(before.copy(speechVoice = id), runBlocking { prefs.current() })
        val spoken = runBlocking { graph.nativeSpeaker.speak("The next native question.") }
        assertEquals(SpeakStatus.SPOKEN, spoken.status)
        assertEquals(id, spoken.voice)
        assertEquals(id, engine.voice)

        engine.installed = emptyList()
        engine.details = emptyList()
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_RELOAD).performScrollTo().performClick()
        waitFor(hasTestTag(SettingsTags.DEVICE_VOICE_MISSING))
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_MISSING).performScrollTo()
            .assertTextContains("choice is kept", substring = true)
        val fallback = runBlocking { graph.nativeSpeaker.speak("A later native question.") }
        assertTrue(fallback.voiceMissing)
        assertEquals(id, fallback.requestedVoice)
        assertEquals("", fallback.voice)
        assertEquals(id, runBlocking { prefs.current().speechVoice })

        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_DEFAULT).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { prefs.current().speechVoice }.isEmpty() }
        assertFalse(runBlocking { graph.nativeSpeaker.speak("Using the default.") }.voiceMissing)
        assertEquals(ring, graph.keyringStore.state.value.ring)
    }

    @Test
    fun loadingFailedAndEmptyCataloguesRetainTheSavedChoiceAndOfferRecovery() {
        val saved = "temporarily-unavailable-voice"
        val prefs = PrefsStore(MemoryDataStore(mutablePreferencesOf(stringPreferencesKey("speechVoice") to saved)))
        val initialized = CompletableDeferred<Boolean>()
        var reads = 0
        val fake = FakeEngine().apply { installed = emptyList() }
        val engine = object : TtsEngine by fake {
            override suspend fun awaitReady() = if (++reads == 1) initialized.await() else true
        }
        openSettings(usableRing(), engine = engine, preferences = prefs)
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_STATUS).performScrollTo()
            .assertTextContains("Loading device voices", substring = true)
        assertEquals(saved, runBlocking { prefs.current().speechVoice })
        initialized.complete(false)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(SettingsTags.DEVICE_VOICE_STATUS) and hasText("did not start", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_STATUS).assertTextContains("saved choice is kept", substring = true)
        assertEquals(saved, runBlocking { prefs.current().speechVoice })
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_RELOAD).performScrollTo().performClick()
        waitFor(hasTestTag(SettingsTags.DEVICE_VOICE_MISSING))
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_STATUS).performScrollTo()
            .assertTextContains("no installed voices", substring = true)
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_DEFAULT).performScrollTo().assertIsDisplayed()
        assertEquals(saved, runBlocking { prefs.current().speechVoice })
        assertTrue(fake.spoken.isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
    fun compactDeviceVoiceChoicesWrapAndKeepFullNativeTargetsAt200Percent() = requireReadableDeviceVoiceChoices()

    @Test
    @Config(qualifiers = "w1280dp-h900dp", fontScale = 2f)
    fun expandedDeviceVoiceChoicesWrapAndKeepFullNativeTargetsAt200Percent() = requireReadableDeviceVoiceChoices()

    private fun requireReadableDeviceVoiceChoices() {
        val id = "en-GB-installed-voice-with-a-deliberately-long-engine-name-for-readable-wrapping-and-selection"
        openSettings(usableRing(), engine = FakeEngine().apply { details = listOf(DeviceVoice(id, "en-GB", true)) })
        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().assertIsDisplayed().performClick()
        for (tag in listOf(SettingsTags.DEVICE_VOICE_DEFAULT, SettingsTags.deviceVoice(id), SettingsTags.DEVICE_VOICE_CLOSE)) {
            val control = compose.onNodeWithTag(tag)
            if (tag != SettingsTags.DEVICE_VOICE_CLOSE) control.performScrollTo()
            control.assertIsDisplayed()
            val node = control.fetchSemanticsNode()
            val density = node.layoutInfo.density.density
            assertTrue("$tag touch width", node.touchBoundsInRoot.width / density >= 48f)
            assertTrue("$tag touch height", node.touchBoundsInRoot.height / density >= 48f)
            assertTrue("$tag is fully visible", node.boundsInRoot.height >= node.layoutInfo.coordinates.size.height - 1f)
            fun checkLabels(n: SemanticsNode) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.runOnUiThread { n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) }
                for (text in results) {
                    val lines = (0 until text.lineCount).toList()
                    val inkWidth = lines.maxOfOrNull { text.getLineRight(it) - text.getLineLeft(it) } ?: 0f
                    val inkHeight = lines.maxOfOrNull(text::getLineBottom) ?: 0f
                    assertTrue("$tag clipped text: $inkWidth x $inkHeight in ${text.size}",
                        inkWidth <= text.size.width + 1f && inkHeight <= text.size.height + 1f)
                    assertFalse("$tag ellipsized text", text.multiParagraph.didExceedMaxLines || lines.any(text::isLineEllipsized))
                }
                n.children.forEach(::checkLabels)
            }
            checkLabels(node)
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun expandedIndexKeepsTheFormBoundedAndNavigatesToItsSection() {
        openSettings(usableRing())
        compose.onNodeWithTag("settings-index").assertIsDisplayed()
        val index = compose.onNodeWithTag("settings-index").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithTag(SettingsTags.KEY).getUnclippedBoundsInRoot()
        assertTrue(index.right <= field.left)
        assertTrue(field.right - field.left <= IdeaForgeWidths.form)
        compose.onNodeWithTag("settings-index-appearance").performClick()
        compose.onNodeWithTag("settings-section-appearance").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.theme(Prefs.THEME_SYSTEM)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp", fontScale = 2f)
    fun enlargedSettingsUsesOneColumnAndKeepsEverySectionReachable() {
        openSettings(usableRing())
        compose.onNodeWithTag("settings-index").assertDoesNotExist()
        for (id in listOf("connection", "voice", "appearance", "data", "about")) {
            compose.onNodeWithTag("settings-section-$id").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag(SettingsTags.FORGET).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
    fun bothHostedVoiceChoicesRemainReachableAtEnlargedText() {
        openSettings(usableRing())
        compose.onNodeWithTag(HostedSpeechTags.HOSTED).performScrollTo().performClick()
        for (tag in listOf(HostedSpeechTags.VOICE_MARIN, HostedSpeechTags.VOICE_CEDAR,
            HostedSpeechTags.CONSENT_SYNTHETIC, HostedSpeechTags.CONSENT_TEXT, HostedSpeechTags.CONSENT_METERED)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp", fontScale = 2f)
    fun voiceHitAreasHaveTheirOwnFullNativeHeightInsteadOfOffscreenExpansion() {
        openSettings(usableRing())
        compose.onNodeWithText("Hands-free").assertDoesNotExist()
        for (tag in listOf(SettingsTags.TRIGGER, SettingsTags.PACE, SettingsTags.DEVICE_VOICE)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun theTriggerWarningAppearsForRiskyWordsAndGoesForSafeOnes() {
        openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.TRIGGER).performScrollTo()
        compose.onNodeWithTag(SettingsTags.TRIGGER_NOTE, useUnmergedTree = true).assertExists()

        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextClearance()
        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextInput("the")
        compose.onNodeWithTag(SettingsTags.TRIGGER_WARNING, useUnmergedTree = true)
            .assertTextContains("is common enough that you will say it mid-answer", substring = true)

        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextClearance()
        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextInput("bananas")
        compose.onNodeWithTag(SettingsTags.TRIGGER_WARNING, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.TRIGGER_NOTE, useUnmergedTree = true).assertExists()
    }

    // Acceptance: Forget all, confirmed, deletes the keyring file and both aliases, and with
    // no provider left the app is back at onboarding with no bottom bar to escape through.
    @Test
    fun forgetAllRoutesToOnboarding() {
        val key = SoftwareKey()
        val graph = openSettings(usableRing(), key)
        key.keys[1] = SoftwareKey.fresh()

        compose.onNodeWithTag(SettingsTags.FORGET).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.FORGET_CONFIRM).performClick()

        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag(NAVIGATION).assertDoesNotExist()
        assertFalse("the keyring file is gone", graph.keyringStore.file.exists())
        assertNull(key.keys[0])
        assertNull(key.keys[1])
    }

    @Test
    fun cancellingForgetKeepsTheBlobAndReturnsFocusToItsOwnAction() {
        val graph = openSettings(usableRing())
        val before = graph.keyringStore.file.readBytes()
        val generation = graph.keyringStore.generation.value
        compose.onNodeWithTag(SettingsTags.FORGET).performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(SettingsTags.FORGET_CONFIRM)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(SettingsTags.FORGET).assertIsDisplayed().assertIsFocused()
        assertArrayEquals(before, graph.keyringStore.file.readBytes())
        assertEquals(generation, graph.keyringStore.generation.value)
    }

    @Test
    fun choosingDarkStoresTheTheme() {
        val graph = openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.theme(Prefs.THEME_DARK)).performScrollTo().performClick()
        // The radio reads the stored value back through the DataStore flow, so it turns on
        // only once the write has landed. (Polling DataStore reads from the test thread
        // instead starves the write.)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(SettingsTags.theme(Prefs.THEME_DARK)) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(Prefs.THEME_DARK, runBlocking { graph.prefsStore.current().theme })
    }

    @Test
    fun theVersionIsShown() {
        openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.VERSION).performScrollTo().assertIsDisplayed()
            .assertTextContains(BuildConfig.VERSION_NAME, substring = true)
    }

    @Test
    fun privacyPolicyInAboutOpensThePublishedPolicyWithAViewIntent() {
        openSettings(usableRing())
        compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().performClick()
        scenario!!.onActivity {
            val intent = shadowOf(it).nextStartedActivity
            assertNotNull("the policy opens an external activity", intent)
            assertEquals(Intent.ACTION_VIEW, intent?.action)
            assertEquals("https://jaypetez.github.io/ideaforge/guide/privacy-policy.html", intent?.dataString)
        }
    }
}

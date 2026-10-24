package io.github.jaypetez.ideaforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import io.github.jaypetez.ideaforge.BuildConfig
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.maskKey
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.ui.GUIDE_URL
import io.github.jaypetez.ideaforge.ui.PrivacyPolicyLink
import io.github.jaypetez.ideaforge.ui.InferenceAgreement
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField as OutlinedTextField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton as OutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton as TextButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.voice.hostedSpeechReady
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

object SettingsTags {
    const val PROVIDER = "settings-provider"
    const val KEY = "settings-key"
    const val BASE_URL = "settings-baseurl"
    const val MODEL = "settings-model"
    const val WRAP_MODEL = "settings-wrapmodel"
    const val CHECK = "settings-check"
    const val SAVE = "settings-save"
    const val STATUS = "settings-status"
    const val ACTIVE = "settings-active"
    const val TRIGGER = "settings-trigger"
    const val TRIGGER_NOTE = "settings-trigger-note"
    const val TRIGGER_WARNING = "settings-trigger-warning"
    const val PACE = "settings-pace"
    const val DEVICE_VOICE = "settings-device-voice"
    const val DEVICE_VOICE_DEFAULT = "settings-device-voice-default"
    const val DEVICE_VOICE_STATUS = "settings-device-voice-status"
    const val DEVICE_VOICE_MISSING = "settings-device-voice-missing"
    const val DEVICE_VOICE_RELOAD = "settings-device-voice-reload"
    const val DEVICE_VOICE_CLOSE = "settings-device-voice-close"
    const val DEVICE_VOICE_SAVE_PROBLEM = "settings-device-voice-save-problem"
    fun deviceVoice(id: String) = "settings-device-voice-$id"
    const val FORGET = "settings-forget"
    const val FORGET_CONFIRM = "settings-forget-confirm"
    const val VERSION = "settings-version"
    fun provider(id: String) = "settings-provider-$id"
    fun theme(value: String) = "settings-theme-$value"
}

private enum class SettingsPart(val title: Int, val id: String) {
    Connection(R.string.settings_connection, "connection"),
    Voice(R.string.settings_listening, "voice"),
    Appearance(R.string.settings_appearance, "appearance"),
    Data(R.string.settings_data, "data"),
    About(R.string.settings_about, "about"),
}

/**
 * Settings: Connection, Voice, Appearance, Your data, About. [onNoProvider] runs once Forget
 * has left no provider that could start an interview; the shell sends the user to onboarding.
 */
@Composable
fun SettingsScreen(
    onNoProvider: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = (LocalContext.current.applicationContext as IdeaForgeApp).graph
    val sessions by graph.sessionStore.sessions.collectAsStateWithLifecycle()
    val library by graph.sessionStore.loadState.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val offsets = remember { mutableStateMapOf<SettingsPart, Int>() }
    val focus = remember { SettingsPart.entries.associateWith { FocusRequester() } }
    val prefs = state.prefs
    val voice = if (prefs != null && hostedSpeechReady(state.ring, prefs))
        stringResource(R.string.settings_voice_hosted_summary, prefs.effectiveTrigger)
    else if (prefs != null) stringResource(R.string.settings_voice_native_summary, prefs.effectiveTrigger)
    else stringResource(R.string.settings_loading)
    val theme = when (prefs?.theme) {
        Prefs.THEME_SYSTEM -> stringResource(R.string.settings_theme_system)
        Prefs.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
        Prefs.THEME_DARK -> stringResource(R.string.settings_theme_dark)
        else -> stringResource(R.string.settings_loading)
    }
    val data = when {
        library.problem != null -> stringResource(R.string.settings_data_unavailable)
        !library.ready -> stringResource(R.string.settings_loading)
        else -> stringResource(R.string.settings_data_summary, sessions.size)
    }
    val summaries = mapOf(
        SettingsPart.Connection to (state.active?.label ?: stringResource(R.string.settings_no_provider)),
        SettingsPart.Voice to voice,
        SettingsPart.Appearance to theme,
        SettingsPart.Data to data,
        SettingsPart.About to stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
    )
    LaunchedEffect(state.needsOnboarding) { if (state.needsOnboarding) onNoProvider() }
    // A finish word typed and then left by navigating away is still kept.
    DisposableEffect(vm) { onDispose { vm.commitTrigger() } }

    val expanded = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val indexWidth = IdeaForgeWidths.sectionIndex * scale
        val splitBound = IdeaForgeWidths.form + indexWidth + IdeaForgeSpacing.section
        val indexed = expanded && minOf(maxWidth, splitBound) >=
            IdeaForgeWidths.formMinimum * scale + indexWidth + IdeaForgeSpacing.section
        Row(
            Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = if (indexed) splitBound else IdeaForgeWidths.settings).imePadding(),
            horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.section),
        ) {
            if (indexed) {
                Column(Modifier.width(indexWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(IdeaForgeSpacing.content).testTag("settings-index"),
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
                    for (part in SettingsPart.entries) {
                        TextButton(onClick = {
                            val offset = offsets[part]
                            if (offset != null) scope.launch {
                                scroll.scrollTo(offset)
                                focus.getValue(part).requestFocus()
                            }
                        }, enabled = part in offsets, modifier = Modifier.fillMaxWidth().testTag("settings-index-${part.id}")) {
                            Text(stringResource(part.title))
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(IdeaForgeSpacing.content)
                    .testTag("settings-content"),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.section),
            ) {
                Text(stringResource(R.string.screen_settings), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                for (part in SettingsPart.entries) {
                    Section(part, summaries.getValue(part), focus.getValue(part),
                        Modifier.onGloballyPositioned { offsets[part] = it.positionInParent().y.roundToInt() }) {
                        when (part) {
                            SettingsPart.Connection -> Connection(state, vm)
                            SettingsPart.Voice -> { Listening(state, vm); DeviceVoiceSettings(state, vm); HostedSpeechSection() }
                            SettingsPart.Appearance -> Appearance(state, vm)
                            SettingsPart.Data -> { BackupSection(); if (state.canForget) Forget(state, vm) }
                            SettingsPart.About -> About()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(part: SettingsPart, summary: String, focus: FocusRequester,
    modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
        HorizontalDivider()
        Text(stringResource(part.title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("settings-section-${part.id}").focusRequester(focus).focusable()
                .semantics { heading() })
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings-summary-${part.id}"))
        content()
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Connection(state: SettingsState, vm: SettingsViewModel) {
    val active = state.active
    val creds = state.activeCreds
    val summary = when {
        active == null -> stringResource(R.string.settings_no_provider)
        active.needsKey -> stringResource(R.string.settings_active, active.label) + " · " +
            stringResource(R.string.settings_active_key, maskKey(stringOf(creds?.get("apiKey"))))
        else -> stringResource(R.string.settings_active, active.label) + " · " + stringResource(
            R.string.settings_active_local,
            stringOf(creds?.get("baseUrl")).orEmpty().ifEmpty { active.defaultBaseUrl.orEmpty() },
            stringOf(creds?.get("model")).orEmpty(),
        )
    }
    Text(summary, Modifier.testTag(SettingsTags.ACTIVE), style = MaterialTheme.typography.bodyLarge)

    val e = state.editor ?: return
    val choice = e.choice

    var picking by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { picking = true }, enabled = !state.checking, busy = state.checking, modifier = Modifier.testTag(SettingsTags.PROVIDER)) {
            Text(stringResource(R.string.settings_provider) + ": " + choice.label + "  ▾")
        }
        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
            for (c in vm.choices) {
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = { picking = false; vm.pickProvider(c.id) },
                    modifier = Modifier.testTag(SettingsTags.provider(c.id)),
                )
            }
        }
    }
    choice.note?.let { Hint(it) }
    InferenceAgreement(choice, state.inferenceDestination, state.inferenceAgreed,
        state.inferenceProblem, vm::setAgreement)

    // Only the fields this provider declares are composed at all, so none can hold a value
    // the save would read back (the web's hidden-field bugs). A local server has no key.
    if (choice.needsKey) {
        OutlinedTextField(
            value = e.apiKey,
            onValueChange = vm::setApiKey,
            label = { Text(stringResource(R.string.onboarding_key_label)) },
            placeholder = {
                Text(
                    if (e.savedKey.isNotEmpty()) stringResource(R.string.settings_key_saved, maskKey(e.savedKey))
                    else stringResource(R.string.onboarding_key_placeholder),
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            supportingText = { Text(stringResource(R.string.settings_key_hint, choice.label)) },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.KEY),
        )
    }
    if (choice.local) {
        OutlinedTextField(
            value = e.baseUrl,
            onValueChange = vm::setBaseUrl,
            label = { Text(stringResource(R.string.onboarding_base_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.BASE_URL),
        )
    }
    if (choice.picksModel) {
        Text(stringResource(R.string.settings_model_options), style = MaterialTheme.typography.labelLarge)
        Hint(stringResource(R.string.settings_model_options_body))
        ModelField(
            value = e.model,
            onChange = vm::setModel,
            label = stringResource(R.string.settings_model),
            // The placeholder is what runs when the box is blank: the preset's own tier.
            placeholder = choice.models?.default ?: stringResource(R.string.settings_model_placeholder_none),
            hint = stringResource(R.string.settings_model_hint),
            models = e.models,
            tag = SettingsTags.MODEL,
        )
        ModelField(
            value = e.wrapModel,
            onChange = vm::setWrapModel,
            label = stringResource(R.string.settings_wrap_model),
            placeholder = choice.models?.complex ?: stringResource(R.string.settings_wrap_model_placeholder),
            hint = stringResource(R.string.settings_wrap_model_hint),
            models = e.models,
            tag = SettingsTags.WRAP_MODEL,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        OutlinedButton(onClick = { vm.check() }, enabled = !state.checking, busy = state.checking, modifier = Modifier.testTag(SettingsTags.CHECK)) {
            Text(stringResource(if (choice.needsKey) R.string.settings_check_key else R.string.settings_check_connection))
        }
        Button(onClick = { vm.save() }, enabled = !state.checking, busy = state.checking, modifier = Modifier.testTag(SettingsTags.SAVE)) {
            Text(stringResource(R.string.settings_save))
        }
    }
    Status(state)

    state.unreadable?.let { problem ->
        AlertDialog(
            onDismissRequest = vm::dismissUnreadable,
            title = { Text(stringResource(R.string.keyring_unreadable_title)) },
            text = { Text(stringResource(R.string.keyring_unreadable_replace_body) + if (problem.isNotEmpty()) "\n\n$problem" else "") },
            confirmButton = { TextButton(onClick = { vm.replaceUnreadable() }) { Text(stringResource(R.string.keyring_unreadable_replace)) } },
            dismissButton = { TextButton(onClick = vm::dismissUnreadable) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Status(state: SettingsState) {
    val failure = state.failure
    val text = when {
        state.checking -> stringResource(R.string.onboarding_checking)
        failure != null -> listOf(failure.lead, failure.detail).filter { it.isNotEmpty() }.joinToString(" ")
        else -> state.notice
    } ?: return
    IdeaForgeStatusText(
        text,
        tone = if (failure != null && !state.checking) StatusTone.Error else StatusTone.Neutral,
        modifier = Modifier.testTag(SettingsTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ModelField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    hint: String,
    models: List<String>,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        supportingText = { Text(hint) },
        trailingIcon = if (models.isEmpty()) null else {
            {
                Box {
                    val choose = stringResource(R.string.settings_pick_model)
                    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = choose }.testTag("$tag-pick")) {
                        Text("▾")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (m in models) {
                            DropdownMenuItem(text = { Text(m) }, onClick = { open = false; onChange(m) })
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Listening(state: SettingsState, vm: SettingsViewModel) {
    val prefs = state.prefs ?: return
    val warning = state.triggerWarning
    OutlinedTextField(
        value = state.trigger,
        onValueChange = vm::setTriggerDraft,
        label = { Text(stringResource(R.string.settings_trigger)) },
        singleLine = true,
        isError = warning != null,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.commitTrigger() }),
        // Advisory, never enforced: someone who genuinely wants "right" can have it, they
        // just deserve to be told first (src/ui/app.js applyTrigger).
        supportingText = {
            if (warning != null) {
                IdeaForgeStatusText(warning, Modifier.testTag(SettingsTags.TRIGGER_WARNING), tone = StatusTone.Warning, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.settings_trigger_hint), Modifier.testTag(SettingsTags.TRIGGER_NOTE))
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.TRIGGER).onFocusChanged { if (!it.isFocused) vm.commitTrigger() },
    )

    // Dragging updates the label; only the release is written, not every step of the drag.
    var pace by remember(prefs.speechRate) { mutableFloatStateOf(prefs.speechRate.toFloat()) }
    val interaction = remember { MutableInteractionSource() }
    val paceLabel = stringResource(R.string.settings_pace, String.format(Locale.ROOT, "%.2f", pace))
    Text(paceLabel, style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = pace,
        onValueChange = { pace = it },
        onValueChangeFinished = { vm.setSpeechRate(Math.round(pace * 20) / 20.0) },
        valueRange = Prefs.MIN_RATE.toFloat()..Prefs.MAX_RATE.toFloat(),
        steps = 9,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(interactionSource = interaction, modifier = Modifier.height(IdeaForgeSpacing.control))
        },
        modifier = Modifier.testTag(SettingsTags.PACE).heightIn(min = IdeaForgeSpacing.control)
            .semantics { contentDescription = paceLabel },
    )
}

@Composable
private fun Appearance(state: SettingsState, vm: SettingsViewModel) {
    val prefs = state.prefs ?: return
    val labels = mapOf(
        Prefs.THEME_SYSTEM to R.string.settings_theme_system,
        Prefs.THEME_LIGHT to R.string.settings_theme_light,
        Prefs.THEME_DARK to R.string.settings_theme_dark,
    )
    Column(Modifier.selectableGroup()) {
        for (theme in Prefs.THEMES) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = IdeaForgeSpacing.control)
                    .selectable(selected = prefs.theme == theme, onClick = { vm.setTheme(theme) }, role = Role.RadioButton)
                    .padding(vertical = IdeaForgeSpacing.tight)
                    .testTag(SettingsTags.theme(theme)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = prefs.theme == theme, onClick = null)
                Text(stringResource(labels.getValue(theme)), Modifier.padding(start = IdeaForgeSpacing.related), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Forget(state: SettingsState, vm: SettingsViewModel) {
    val focus = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.confirmingForget, restoreFocus) {
        if (!state.confirmingForget && restoreFocus) {
            restoreFocus = false
            focus.requestFocus()
        }
    }
    fun cancel() {
        restoreFocus = true
        vm.cancelForget()
    }
    Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.settings_remove_credentials), style = MaterialTheme.typography.titleMedium)
        Hint(stringResource(R.string.settings_forget_hint))
        OutlinedButton(
            onClick = vm::askForget,
            enabled = !state.forgetting,
            contentColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SettingsTags.FORGET).focusRequester(focus),
        ) { Text(stringResource(R.string.settings_forget)) }
    }
    if (state.confirmingForget) {
        AlertDialog(
            onDismissRequest = ::cancel,
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(onClick = { vm.forget() }, modifier = Modifier.testTag(SettingsTags.FORGET_CONFIRM)) {
                    Text(stringResource(R.string.settings_forget), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), Modifier.testTag(SettingsTags.VERSION))
    TextButton(onClick = { runCatching { uri.openUri(GUIDE_URL) } }) { Text(stringResource(R.string.settings_guide)) }
    PrivacyPolicyLink()
}

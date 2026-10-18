package io.github.jaypetez.ideaforge.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.providers.hostOf
import io.github.jaypetez.ideaforge.ui.PrivacyPolicyLink
import io.github.jaypetez.ideaforge.ui.InferenceAgreement
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField as OutlinedTextField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton as OutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeSuggestionChip as SuggestionChip
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton as TextButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** First run: pick a provider, then a key (hosted) or an address and a model (local). */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler(enabled = state.step == 2) { vm.back() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.choice?.id) { scroll.scrollTo(0) }
    val expanded = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val supporting = expanded &&
            maxWidth >= (IdeaForgeWidths.formMinimum + IdeaForgeWidths.support) * scale + IdeaForgeSpacing.section
        val supportWidth = IdeaForgeWidths.support * scale
        val width = if (supporting) IdeaForgeWidths.form + supportWidth + IdeaForgeSpacing.section else IdeaForgeWidths.form
        Row(
            Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = width).imePadding(),
            horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.section),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(IdeaForgeSpacing.content),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
            ) {
                Text(stringResource(R.string.onboarding_step, state.step),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.hasSavedSettings) IdeaForgeStatusText(stringResource(R.string.onboarding_saved_details))
                if (state.hasSuccessfulCheck) IdeaForgeStatusText(stringResource(R.string.onboarding_successful_check))
                val choice = state.choice
                if (choice == null) PickProvider(vm.choices, vm::pick)
                else if (choice.local) LocalServer(choice, state, vm, inlineHelp = !supporting)
                else HostedKey(choice, state, vm, inlineHelp = !supporting)
                PrivacyPolicyLink()
            }
            if (supporting) {
                Column(
                    Modifier.width(supportWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(IdeaForgeSpacing.content).testTag("onboarding-support"),
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
                ) {
                    Text(stringResource(R.string.onboarding_support_title), style = MaterialTheme.typography.titleLarge)
                    state.choice?.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Text(stringResource(R.string.onboarding_support_body), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun PickProvider(choices: List<ProviderChoice>, pick: (String) -> Unit) {
    Text(stringResource(R.string.onboarding_pick_title), style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.onboarding_pick_body), style = MaterialTheme.typography.bodyLarge)

    Section(stringResource(R.string.onboarding_hosted))
    Text(
        stringResource(R.string.onboarding_hosted_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (c in choices.filter { !it.local }) ProviderCard(c, pick)

    Section(stringResource(R.string.onboarding_local))
    for (c in choices.filter { it.local }) ProviderCard(c, pick)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.padding(top = IdeaForgeSpacing.micro))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ProviderCard(c: ProviderChoice, pick: (String) -> Unit) {
    Column {
        Surface(
            onClick = { pick(c.id) },
            modifier = Modifier.fillMaxWidth().testTag("provider-${c.id}").semantics { role = Role.Button },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(vertical = IdeaForgeSpacing.related, horizontal = IdeaForgeSpacing.tight),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro)) {
                Text(c.label, style = MaterialTheme.typography.titleMedium)
                describe(c)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * A hosted card says what it costs or which models it runs: the registry's own note where it
 * has one, else its tier map (which model asks the questions and which writes the prompt). A
 * local card says what it needs; Ollama's and LM Studio's notes are setup steps for step 2.
 */
@Composable
private fun describe(c: ProviderChoice): String? {
    if (c.local) return if (c.defaultBaseUrl == null) c.note else stringResource(R.string.onboarding_local_card, c.label.substringBefore(" ("))
    c.note?.let { return it }
    val t = c.models ?: return null
    val q = t.default ?: t.quick ?: return null
    val w = t.complex
    return if (w == null || w == q) stringResource(R.string.onboarding_tiers_one, q) else stringResource(R.string.onboarding_tiers, q, w)
}

@Composable
private fun HostedKey(c: ProviderChoice, s: OnboardingState, vm: OnboardingViewModel, inlineHelp: Boolean) {
    Text(stringResource(R.string.onboarding_key_title, c.label), style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() })
    if (inlineHelp) c.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    c.keyUrl?.let { url ->
        val uri = LocalUriHandler.current
        TextButton(onClick = { runCatching { uri.openUri(url) } }) {
            Text(stringResource(R.string.onboarding_get_key, hostOf(url)))
        }
    }

    var shown by rememberSaveable(c.id) { mutableStateOf(false) }
    OutlinedTextField(
        value = s.apiKey,
        onValueChange = vm::setApiKey,
        modifier = Modifier.fillMaxWidth().testTag("api-key"),
        label = { Text(stringResource(R.string.onboarding_key_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_key_placeholder)) },
        singleLine = true,
        enabled = !s.checking,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        // Password keyboard: no suggestions, no learning the key into the dictionary. Paste
        // still works, which is how a key is entered in practice.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { vm.checkKey() }),
        trailingIcon = {
            TextButton(onClick = { shown = !shown }) {
                Text(stringResource(if (shown) R.string.onboarding_key_hide else R.string.onboarding_key_show))
            }
        },
    )
    Text(
        stringResource(R.string.onboarding_key_privacy, c.defaultBaseUrl?.let(::hostOf) ?: c.label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    InferenceAgreement(c, s.destination, s.agreed, null, vm::setAgreement)
    Failure(s.failure)
    s.unreadable?.let { UnreadableKeyring(it, vm::replaceUnreadable) }
    Actions(vm::back) {
        Button(onClick = vm::checkKey, enabled = !s.checking && s.agreed, busy = s.checking, modifier = Modifier.testTag("check-key")) {
            Text(stringResource(if (s.checking) R.string.onboarding_checking else R.string.onboarding_check_key))
        }
    }
}

@Composable
private fun LocalServer(c: ProviderChoice, s: OnboardingState, vm: OnboardingViewModel, inlineHelp: Boolean) {
    Text(stringResource(R.string.onboarding_local_title, c.label), style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() })
    if (inlineHelp) c.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }

    OutlinedTextField(
        value = s.baseUrl,
        onValueChange = vm::setBaseUrl,
        modifier = Modifier.fillMaxWidth().testTag("base-url"),
        label = { Text(stringResource(R.string.onboarding_base_label)) },
        placeholder = { Text("http://192.168.1.20:11434/v1") },
        singleLine = true,
        enabled = !s.checking,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { vm.findModels() }),
    )
    val port = (s.baseUrl.toHttpUrlOrNull() ?: c.defaultBaseUrl?.toHttpUrlOrNull())?.port
    Text(
        if (port != null) stringResource(R.string.onboarding_base_hint, port) else stringResource(R.string.onboarding_base_hint_generic),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    emulatorAddress(s.baseUrl)?.let { swapped ->
        SuggestionChip(onClick = { vm.setBaseUrl(swapped) }, label = { Text(stringResource(R.string.onboarding_use_emulator)) })
    }

    OutlinedButton(onClick = vm::findModels, enabled = !s.checking, busy = s.checking, modifier = Modifier.testTag("find-models")) {
        Text(stringResource(if (s.checking) R.string.onboarding_checking else R.string.onboarding_find_models))
    }
    s.models?.let { names ->
        Text(
            if (names.isEmpty()) stringResource(R.string.onboarding_no_models)
            else androidx.compose.ui.res.pluralStringResource(R.plurals.onboarding_models_found, names.size, names.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(Modifier.selectableGroup()) {
            for (name in names) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = IdeaForgeSpacing.control).testTag("model-$name")
                        .selectable(selected = s.model == name, onClick = { vm.setModel(name) }, role = Role.RadioButton)
                        .padding(vertical = IdeaForgeSpacing.micro),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.model == name, onClick = null)
                    Text(name, Modifier.padding(start = IdeaForgeSpacing.related), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    OutlinedTextField(
        value = s.model,
        onValueChange = vm::setModel,
        modifier = Modifier.fillMaxWidth().testTag("model"),
        label = { Text(stringResource(R.string.onboarding_model_label)) },
        singleLine = true,
        enabled = !s.checking,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    InferenceAgreement(c, s.destination, s.agreed, null, vm::setAgreement)
    Failure(s.failure)
    s.unreadable?.let { UnreadableKeyring(it, vm::replaceUnreadable) }
    Actions(vm::back) {
        Button(onClick = vm::finishLocal, enabled = s.canFinishLocal, modifier = Modifier.testTag("finish-local")) {
            Text(stringResource(R.string.onboarding_continue))
        }
    }
}

/**
 * `localhost` on an emulator is the emulator. The development machine is 10.0.2.2, so a
 * default address is one tap from the one that works there. (On a phone, `adb reverse`
 * keeps localhost correct instead, which is why this is offered, never applied.)
 */
internal fun emulatorAddress(base: String): String? {
    val url = base.trim().toHttpUrlOrNull() ?: return null
    if (url.host != "localhost" && url.host != "127.0.0.1") return null
    return url.newBuilder().host("10.0.2.2").build().toString().trimEnd('/')
}

@Composable
internal fun NewIdeaChoice(onVoice: () -> Unit, onType: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = IdeaForgeWidths.form)
            .imePadding().verticalScroll(rememberScrollState()).padding(IdeaForgeSpacing.content).testTag("new-idea-choice"),
        verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
    ) {
        Text(stringResource(R.string.new_idea_title), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.new_idea_body), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onVoice,
            modifier = Modifier.fillMaxWidth().heightIn(min = IdeaForgeSpacing.voice).testTag("new-idea-voice")) {
            Text(stringResource(R.string.new_idea_voice))
        }
        OutlinedButton(onClick = onType, modifier = Modifier.fillMaxWidth().testTag("new-idea-type")) {
            Text(stringResource(R.string.new_idea_type))
        }
        Text(stringResource(R.string.new_idea_permissions), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onCancel, modifier = Modifier.testTag("new-idea-cancel")) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun Failure(f: CheckFailure?) {
    if (f == null) return
    OutlinedCard(
        Modifier.fillMaxWidth().testTag("check-failure").semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(IdeaForgeSpacing.content), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro)) {
            Text(f.lead, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (f.detail.isNotEmpty()) {
                Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Shown instead of saving over a keyring that could not be read even on a second try. */
@Composable
private fun UnreadableKeyring(problem: String, replace: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().testTag("unreadable-keyring"),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(IdeaForgeSpacing.content), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            Text(stringResource(R.string.keyring_unreadable_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.keyring_unreadable_replace_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (problem.isNotEmpty()) {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            OutlinedButton(onClick = replace, modifier = Modifier.testTag("replace-unreadable")) {
                Text(stringResource(R.string.keyring_unreadable_replace))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(back: () -> Unit, primary: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        TextButton(onClick = back) { Text(stringResource(R.string.onboarding_back)) }
        primary()
    }
}

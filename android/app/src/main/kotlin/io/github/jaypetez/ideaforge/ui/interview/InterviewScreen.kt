package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.data.InferencePermit
import io.github.jaypetez.ideaforge.core.DIMENSIONS
import io.github.jaypetez.ideaforge.core.HARD_TURN_CEILING
import io.github.jaypetez.ideaforge.core.coveragePercent
import io.github.jaypetez.ideaforge.core.getDimension
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField as OutlinedTextField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeSuggestionChip as SuggestionChip
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton as TextButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeSnackbarHost as SnackbarHost
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTonalButton as FilledTonalButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeShape
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.ui.theme.statusColors
import io.github.jaypetez.ideaforge.ui.voice.VoiceStage
import io.github.jaypetez.ideaforge.ui.voice.VoicePermissionDialog
import io.github.jaypetez.ideaforge.ui.voice.beginVoice
import io.github.jaypetez.ideaforge.ui.voice.hasPermission
import io.github.jaypetez.ideaforge.ui.voice.voicePermissions
import io.github.jaypetez.ideaforge.ui.voice.voicePermissionsHeld
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.VoiceMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One coverage pill: a dimension, its level, and whether it is still being asked about. */
data class CoveragePill(val id: String, val label: String, val level: String, val status: String)

/** One settled exchange in the thread. */
data class ThreadEntry(val n: Int, val question: String, val answer: String?, val fromChip: Boolean)

internal fun pillsOf(state: InterviewState): List<CoveragePill> = DIMENSIONS.map { d ->
    val id = stringOf(d["id"]).orEmpty()
    val c = state.session?.get("coverage")?.get(id)
    CoveragePill(id, stringOf(d["label"]).orEmpty(), stringOf(c["level"]) ?: "thin", stringOf(c["status"]) ?: "probing")
}

internal fun threadOf(state: InterviewState): List<ThreadEntry> =
    (state.session?.get("turns") as? JsonArray).orEmpty().mapNotNull { t ->
        val skipped = truthy(t["skipped"])
        val answer = stringOf(t["answer"]).orEmpty()
        if (!skipped && answer.isEmpty()) return@mapNotNull null
        ThreadEntry(
            n = (t["n"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0,
            question = stringOf(t["question"]).orEmpty(),
            answer = if (skipped) null else answer,
            fromChip = stringOf(t["answerSource"]) == "chip",
        )
    }

/**
 * The current question and keyboard-safe composer, with optional earlier conversation
 * and factual coverage detail.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterviewScreen(
    vm: InterviewViewModel,
    onFinished: (synthesisFailed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    drive: DriveController? = null,
    onBack: (() -> Unit)? = null,
    onConsent: (() -> Unit)? = null,
    voiceOnEntry: Boolean = false,
    onVoiceEntryConsumed: () -> Unit = {},
    isCurrentView: () -> Boolean = { true },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val inference by vm.inferenceState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope0 = rememberCoroutineScope()
    val voice = drive?.state?.collectAsStateWithLifecycle()?.value
    // A drive outlives this screen; coming back to its idea re-attaches to the stage.
    val voiceHere = voice != null && voice.sessionId == vm.sessionId
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.voice_mic_denied)
    val entryCancelled = stringResource(R.string.voice_entry_cancelled)
    val entryDenied = stringResource(R.string.voice_entry_denied)
    val entryInterrupted = stringResource(R.string.voice_entry_interrupted)
    var initialVoicePending by rememberSaveable(vm.sessionId) { mutableStateOf(voiceOnEntry) }
    var entryVoiceInProgress by remember(vm.sessionId) { mutableStateOf(false) }
    val voiceRequest = remember(vm.sessionId) { mutableLongStateOf(0L) }
    var permissionRequest by remember(vm.sessionId) { mutableStateOf<Long?>(null) }
    var screenVisible by remember(vm.sessionId) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { screenVisible = true }
    var microphoneHeld by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationsHeld by remember { mutableStateOf(hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        microphoneHeld = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        notificationsHeld = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    var rationale by remember { mutableStateOf(false) }
    var permissionPermit by remember { mutableStateOf<InferencePermit?>(null) }
    LaunchedEffect(inference) {
        vm.refreshConsent()
        if (inference.blocked) {
            voiceRequest.longValue++
            rationale = false
            permissionPermit = null
            permissionRequest = null
            entryVoiceInProgress = false
            initialVoicePending = false
            onVoiceEntryConsumed()
        }
    }
    DisposableEffect(vm.sessionId) {
        onDispose {
            voiceRequest.longValue++
            if (initialVoicePending || entryVoiceInProgress) vm.voiceNotStarted(entryInterrupted)
            initialVoicePending = false
            entryVoiceInProgress = false
            permissionPermit = null
            permissionRequest = null
            onVoiceEntryConsumed()
        }
    }
    // The answer to the permission prompt, then the tap's intent carried out.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        scope0.launch(Dispatchers.Main.immediate) {
            val expected = permissionPermit
            val my = permissionRequest
            if (expected == null || my == null || my != voiceRequest.longValue) return@launch
            permissionPermit = null
            permissionRequest = null
            val allowed = vm.authorizeVoice(expected)
            if (my != voiceRequest.longValue || !isCurrentView()) return@launch
            if (!allowed) {
                entryVoiceInProgress = false
                return@launch
            }
            val mic = grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
            microphoneHeld = mic
            notificationsHeld = grants[Manifest.permission.POST_NOTIFICATIONS] ?: hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            val fromEntry = entryVoiceInProgress
            entryVoiceInProgress = false
            if (!mic && fromEntry) {
                vm.voiceNotStarted(entryDenied)
                return@launch
            }
            if (drive == null) return@launch
            if (fromEntry && !vm.state.value.canAnswer) {
                vm.voiceNotStarted(entryCancelled)
                return@launch
            }
            val attached = drive.state.value.let { it.sessionId == vm.sessionId && it.mode == VoiceMode.BLOCKED }
            if (attached) {
                if (mic) drive.resume()
            } else {
                beginVoice(drive, vm.handOffToVoice(), mic, deniedMessage)
            }
        }
    }
    fun requestVoice(fromEntry: Boolean) {
        if (!fromEntry) {
            initialVoicePending = false
            onVoiceEntryConsumed()
        }
        val my = ++voiceRequest.longValue
        entryVoiceInProgress = fromEntry
        vm.voiceRetry()
        scope0.launch(Dispatchers.Main.immediate) {
            val allowed = vm.authorizeVoice()
            if (my != voiceRequest.longValue || !isCurrentView()) return@launch
            if (!allowed) {
                entryVoiceInProgress = false
                return@launch
            }
            if (fromEntry && (!screenVisible || !vm.state.value.canAnswer)) {
                entryVoiceInProgress = false
                vm.voiceNotStarted(entryCancelled)
                return@launch
            }
            permissionPermit = vm.inferenceState.value.permit
            if (drive != null) {
                if (voicePermissionsHeld(context)) {
                    entryVoiceInProgress = false
                    val blocked = drive.state.value.let { it.sessionId == vm.sessionId && it.mode == VoiceMode.BLOCKED }
                    if (blocked) drive.resume() else beginVoice(drive, vm.handOffToVoice(), micGranted = true, deniedMessage)
                } else {
                    rationale = true
                }
            }
        }
    }
    val askOrStart: () -> Unit = { requestVoice(false) }
    LaunchedEffect(voiceOnEntry, state.canAnswer, screenVisible) {
        if (voiceOnEntry && initialVoicePending && state.canAnswer && screenVisible && isCurrentView()) {
            initialVoicePending = false
            onVoiceEntryConsumed()
            requestVoice(true)
        }
    }

    LaunchedEffect(voice?.exit, voiceHere) {
        if (voiceHere && voice?.exit != null) {
            drive?.exitShown()
            vm.reload()
        }
    }

    // Leaving the screen (another app, the lock screen) writes a queued draft at once.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.flushDraft()
        screenVisible = false
        if (initialVoicePending || entryVoiceInProgress) {
            voiceRequest.longValue++
            rationale = false
            initialVoicePending = false
            entryVoiceInProgress = false
            permissionPermit = null
            permissionRequest = null
            onVoiceEntryConsumed()
            vm.voiceNotStarted(entryInterrupted)
        }
    }

    LaunchedEffect(state.finished) {
        if (state.finished) {
            vm.finishedShown()
            // The failure travels with the navigation: a snackbar here would never be seen.
            onFinished(state.writeUpFailed)
        }
    }
    val scope = rememberCoroutineScope()
    val noticeText = state.notice?.let { noticeText(it) }
    var noticeHeight by remember { mutableStateOf(0) }
    var coverageExpanded by rememberSaveable(vm.sessionId) { mutableStateOf(false) }
    var historyExpanded by rememberSaveable(vm.sessionId) { mutableStateOf(false) }
    val voiceAttached = voiceHere && voice?.attached == true
    LaunchedEffect(state.notice, voiceAttached) {
        val notice = state.notice
        if (notice != null && noticeText != null) {
            if (voiceAttached) {
                // Voice retains its existing one-shot notification path.
                vm.noticeShown()
                scope.launch { snackbar.showSnackbar(noticeText) }
            } else {
                snackbar.showSnackbar(noticeText, duration = SnackbarDuration.Indefinite)
                if (vm.state.value.notice == notice) vm.noticeShown()
            }
        }
    }

    if (rationale) {
        VoicePermissionDialog(
            notificationsNeeded = voicePermissions().contains(Manifest.permission.POST_NOTIFICATIONS) && !notificationsHeld,
            onContinue = {
                val my = voiceRequest.longValue
                scope0.launch(Dispatchers.Main.immediate) {
                    val expected = permissionPermit
                    if (expected != null && vm.authorizeVoice(expected) && my == voiceRequest.longValue
                        && rationale && isCurrentView()) {
                        rationale = false
                        permissionRequest = my
                        permissions.launch(voicePermissions())
                    }
                }
            },
            onDismiss = {
                voiceRequest.longValue++
                rationale = false
                permissionPermit = null
                permissionRequest = null
                if (entryVoiceInProgress) {
                    entryVoiceInProgress = false
                    vm.voiceNotStarted(entryCancelled)
                }
            },
        )
    }

    if (drive != null && voice != null && voiceHere && voice.attached) {
        Column(modifier.fillMaxSize().testTag("interview")) {
            SnackbarHost(snackbar)
            VoiceStage(
                voice,
                onPause = drive::pause,
                onResume = {
                    scope0.launch {
                        if (vm.authorizeVoice() && isCurrentView()) {
                            permissionPermit = vm.inferenceState.value.permit
                            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) rationale = true else drive.resume()
                        }
                    }
                },
                onExit = drive::exit,
                modifier = Modifier.weight(1f),
                ideaTitle = state.session?.let(::sessionDisplayTitle).orEmpty(),
                microphoneDenied = voice.mode == VoiceMode.BLOCKED && !microphoneHeld,
                notificationsOff = voicePermissions().contains(Manifest.permission.POST_NOTIFICATIONS) && !notificationsHeld,
            )
        }
        return
    }

    Column(modifier.fillMaxSize().testTag("interview")) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            val barHeight = if (onBack != null) TopAppBarDefaults.TopAppBarExpandedHeight else 0.dp
            val noticeBlock = with(LocalDensity.current) { noticeHeight.toDp() }
            val bodyHeight = (maxHeight - barHeight - noticeBlock).coerceAtLeast(0.dp)
            val composerMaxHeight = bodyHeight * 0.6f
            // History and persistent feedback can starve the thread even without an IME.
            val historyBlock = if (threadOf(state).isNotEmpty()) IdeaForgeSpacing.control else 0.dp
            // Scroll the real content instead of weighting the editor down to zero.
            val shortWindow = bodyHeight - historyBlock < 240.dp * LocalDensity.current.fontScale
            val scrollWithComposer = shortWindow || LocalDensity.current.fontScale > 1f || coverageExpanded
            // A fixed bar can leave less height than one enlarged editor above the IME.
            val scrollHeader = shortWindow && WindowInsets.isImeVisible
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize()) {
                if (!scrollHeader) InterviewHeader(state, onBack)
                SnackbarHost(snackbar, Modifier.onSizeChanged { noticeHeight = it.height })
                Column(Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = IdeaForgeWidths.reading)
                    .let { if (scrollWithComposer) it.verticalScroll(scroll) else it }) {
                    if (scrollHeader) InterviewHeader(state, onBack)
                    if (state.consentRequired) {
                        Column(Modifier.fillMaxWidth().padding(IdeaForgeSpacing.content)) {
                            Text(stringResource(R.string.inference_required), modifier = Modifier.testTag("inference-required"))
                            if (onConsent != null) TextButton(onClick = onConsent) {
                                Text(stringResource(R.string.inference_review))
                            }
                        }
                    }
                    CoverageStrip(state, coverageExpanded) { coverageExpanded = !coverageExpanded }
                    if (state.busy != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("interview-busy"))
                    } else {
                        Spacer(Modifier.height(IdeaForgeSpacing.micro))
                    }
                    Conversation(state, vm, if (scrollWithComposer) Modifier else Modifier.weight(1f),
                        scrollWithComposer, followCurrent = !coverageExpanded, expanded = historyExpanded,
                        onHistoryChange = { historyExpanded = it })
                    // Wrapped actions and enlarged drafts must not consume the entire thread.
                    Composer(
                        state, vm, onVoice = if (drive != null) askOrStart else null,
                        modifier = if (scrollWithComposer) Modifier else Modifier.heightIn(max = composerMaxHeight),
                        shortWindow = shortWindow,
                        scrollWithPage = scrollWithComposer,
                        noticeHeight = noticeHeight,
                        readingDetails = historyExpanded || coverageExpanded,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterviewHeader(state: InterviewState, onBack: (() -> Unit)?) {
    if (onBack == null) return
    val fallback = stringResource(R.string.nav_new_idea)
    val title = state.session?.let { sessionDisplayTitle(it, fallback) } ?: fallback
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("interview-title")) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(IdeaForgeSpacing.control).testTag("interview-back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
        },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun noticeText(n: InterviewNotice): String = when (n) {
    is InterviewNotice.NoProvider -> stringResource(R.string.notice_no_provider, n.detail)
    is InterviewNotice.Fallback -> stringResource(R.string.notice_fallback, n.detail)
    is InterviewNotice.Failed -> stringResource(R.string.notice_interview_failed, n.detail)
    is InterviewNotice.Consent -> n.detail
    is InterviewNotice.Voice -> n.detail
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CoverageStrip(state: InterviewState, expanded: Boolean, toggle: () -> Unit) {
    val pct = state.session?.let(::coveragePercent) ?: 0
    Column(
        Modifier.fillMaxWidth().padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.micro)
            .testTag("coverage-strip"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            Text(
                stringResource(R.string.interview_coverage, pct),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("coverage-percent"),
            )
            TextButton(onClick = toggle, modifier = Modifier.testTag("coverage-details")) {
                Text(stringResource(if (expanded) R.string.interview_coverage_hide else R.string.interview_coverage_show))
            }
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                for (pill in pillsOf(state)) Pill(pill)
            }
        }
    }
}

@Composable
private fun Pill(pill: CoveragePill) {
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.statusColors
    val probing = pill.status == "probing"
    val word = when {
        pill.status == "waived" -> stringResource(R.string.status_waived)
        pill.status == "deferred" -> stringResource(R.string.status_deferred)
        pill.level == "covered" -> stringResource(R.string.level_covered)
        pill.level == "partial" -> stringResource(R.string.level_partial)
        else -> stringResource(R.string.level_thin)
    }
    val accent: Color = when {
        !probing -> scheme.onSurfaceVariant
        pill.level == "covered" -> status.success
        pill.level == "partial" -> status.warning
        else -> scheme.outline
    }
    val description = stringResource(R.string.interview_pill, pill.label, word)
    Row(
        Modifier
            .border(1.dp, accent, IdeaForgeShape.status)
            .background(if (probing && pill.level == "covered") accent.copy(alpha = 0.12f) else Color.Transparent, IdeaForgeShape.status)
            .padding(horizontal = IdeaForgeSpacing.related, vertical = IdeaForgeSpacing.tight)
            .testTag("pill-${pill.id}")
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
    ) {
        // A filled dot for covered, a ring for partial, nothing louder than the outline for thin.
        Box(
            Modifier.size(IdeaForgeSpacing.tight).let {
                when {
                    probing && pill.level == "covered" -> it.background(accent, CircleShape)
                    probing && pill.level == "partial" -> it.border(2.dp, accent, CircleShape)
                    else -> it.border(1.dp, accent, CircleShape)
                }
            },
        )
        Text(
            description,
            style = MaterialTheme.typography.labelLarge,
            color = if (probing) scheme.onSurface else scheme.onSurfaceVariant,
            fontStyle = if (probing) FontStyle.Normal else FontStyle.Italic,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(
    state: InterviewState, vm: InterviewViewModel, modifier: Modifier,
    scrollWithComposer: Boolean, followCurrent: Boolean, expanded: Boolean, onHistoryChange: (Boolean) -> Unit,
) {
    val thread = threadOf(state)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val current = remember { BringIntoViewRequester() }
    val wrap = remember { BringIntoViewRequester() }
    val history = remember { BringIntoViewRequester() }
    @Composable
    fun HistoryControl() {
        if (thread.isEmpty()) return
        TextButton(
            onClick = {
                val next = !expanded
                onHistoryChange(next)
                scope.launch {
                    if (!scrollWithComposer) list.scrollToItem(0)
                    else if (next) history.bringIntoView() else current.bringIntoView()
                }
            },
            modifier = Modifier.padding(horizontal = IdeaForgeSpacing.content)
                .bringIntoViewRequester(history).testTag("history-toggle"),
        ) {
            Text(if (expanded) stringResource(R.string.interview_history_hide)
                else stringResource(R.string.interview_history_show, thread.size))
        }
    }
    // A short window has one page scroller. A nested lazy viewport can scroll a chip
    // into its own bounds while leaving it clipped behind the contextual bar.
    if (scrollWithComposer) {
        LaunchedEffect(state.openTurn?.get("n"), state.wrapOffer) {
            if (!followCurrent || expanded || state.busy != null || state.notice != null && state.notice !is InterviewNotice.NoProvider) return@LaunchedEffect
            if (state.wrapOffer != null) wrap.bringIntoView() else current.bringIntoView()
        }
        Column(
            modifier.fillMaxWidth().testTag("thread"),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
        ) {
            HistoryControl()
            Column(Modifier.padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
                if (expanded) for (entry in thread) ThreadItem(entry)
                Box(Modifier.fillMaxWidth().bringIntoViewRequester(current).testTag("current-turn")) {
                    OpenQuestion(state, vm)
                }
                state.wrapOffer?.let {
                    Box(Modifier.fillMaxWidth().bringIntoViewRequester(wrap)) { WrapOffer(it, vm) }
                }
            }
        }
        return
    }
    LaunchedEffect(state.openTurn?.get("n"), state.wrapOffer) {
        if (followCurrent && !expanded && state.busy == null) list.scrollToItem(if (state.wrapOffer != null) 1 else 0)
    }
    Column(modifier.fillMaxWidth()) {
        HistoryControl()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("thread"),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
        ) {
            if (expanded) items(thread, key = { "turn-${it.n}" }) { ThreadItem(it) }
            item(key = "open") {
                Box(Modifier.fillMaxWidth().testTag("current-turn")) { OpenQuestion(state, vm) }
            }
            state.wrapOffer?.let { offer ->
                item(key = "wrap") { WrapOffer(offer, vm) }
            }
        }
    }
}

@Composable
private fun OpenQuestion(state: InterviewState, vm: InterviewViewModel) {
    val open = state.openTurn
    when {
        state.session == null -> Text(stringResource(R.string.interview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.busy != null -> Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
            BusyLine(state.busy)
            if (open != null) QuestionCard(state, open, vm)
            else threadOf(state).lastOrNull()?.let { ThreadItem(it) }
        }
        open != null -> QuestionCard(state, open, vm)
    }
}

@Composable
private fun ThreadItem(e: ThreadEntry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        Text(e.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(IdeaForgeSpacing.related, IdeaForgeSpacing.related, IdeaForgeSpacing.micro, IdeaForgeSpacing.related),
                modifier = Modifier.widthIn(max = IdeaForgeWidths.answer),
            ) {
                Column(Modifier.padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.related)) {
                    if (e.answer == null) {
                        Text(stringResource(R.string.interview_skipped), fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(e.answer, style = MaterialTheme.typography.bodyLarge)
                        if (e.fromChip) {
                            Text(
                                stringResource(R.string.interview_from_chip),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusyLine(busy: Busy) {
    val text = when (busy) {
        Busy.Thinking -> stringResource(R.string.interview_thinking)
        Busy.Resuming -> stringResource(R.string.interview_resuming)
        Busy.Writing -> stringResource(R.string.interview_writing)
    }
    IdeaForgeStatusText(text, Modifier.testTag("busy-line"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(state: InterviewState, open: JsonObject, vm: InterviewViewModel) {
    val bridge = stringOf(open["bridge"]).orEmpty()
    val question = stringOf(open["question"]).orEmpty()
    val chips = (open["chips"] as? JsonArray).orEmpty().mapNotNull(::stringOf)
    val dim = stringOf(open["dimension"])?.let { runCatching { stringOf(getDimension(it)["label"]) }.getOrNull() }
    val turnline = buildString {
        append(
            if (dim.isNullOrEmpty()) stringResource(R.string.interview_turnline, state.turnCount, HARD_TURN_CEILING)
            else stringResource(R.string.interview_turnline_dimension, state.turnCount, HARD_TURN_CEILING, dim.lowercase()),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
        Column(
            Modifier.fillMaxWidth().testTag("question-card"),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
        ) {
            Column(Modifier.fillMaxWidth().testTag("turnline"), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro)) {
                Text(turnline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("turn-status"))
                if (state.degraded) {
                    IdeaForgeStatusText(
                        stringResource(R.string.interview_from_checklist),
                        Modifier.testTag("degraded"),
                        tone = StatusTone.Warning, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (bridge.isNotEmpty()) {
                Text(bridge, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("bridge"))
            }
            Text(
                question,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("question").semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
        if (chips.isNotEmpty()) {
            val label = stringResource(R.string.interview_suggestions)
            FlowRow(
                Modifier.fillMaxWidth().testTag("chips").semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
            ) {
                chips.forEachIndexed { i, chip ->
                    SuggestionChip(
                        onClick = { vm.onChip(chip) },
                        label = { Text(chip) },
                        enabled = state.canAnswer,
                        modifier = Modifier.testTag("chip-$i"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapOffer(offer: String, vm: InterviewViewModel) {
    Card(
        Modifier.fillMaxWidth().testTag("wrap-offer"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(IdeaForgeSpacing.content), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            Text(offer, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                Button(onClick = vm::writeUp, modifier = Modifier.testTag("wrap-accept")) {
                    Text(stringResource(R.string.interview_wrap_accept))
                }
                TextButton(onClick = vm::declineWrap, modifier = Modifier.testTag("wrap-decline")) {
                    Text(stringResource(R.string.interview_wrap_decline))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    state: InterviewState, vm: InterviewViewModel, onVoice: (() -> Unit)? = null, modifier: Modifier = Modifier,
    shortWindow: Boolean = false,
    scrollWithPage: Boolean = shortWindow,
    noticeHeight: Int = 0,
    readingDetails: Boolean = false,
) {
    val editor = remember { BringIntoViewRequester() }
    var editorVisible by remember { mutableStateOf(false) }
    val keepEditor = remember(state.notice, scrollWithPage, readingDetails) {
        scrollWithPage && editorVisible && !readingDetails
    }
    LaunchedEffect(state.notice, noticeHeight, readingDetails) {
        if (keepEditor && (state.notice is InterviewNotice.Failed || state.notice is InterviewNotice.Fallback
                    || state.notice is InterviewNotice.Voice)) {
            editor.bringIntoView()
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            OutlinedTextField(
                value = state.answer,
                onValueChange = vm::onAnswerChange,
                label = { Text(stringResource(R.string.interview_answer_label)) },
                placeholder = { Text(stringResource(R.string.interview_answer_placeholder)) },
                enabled = state.canAnswer,
                minLines = 1,
                maxLines = if (shortWindow) 1 else 4,
                modifier = Modifier.fillMaxWidth().let {
                    if (scrollWithPage) it.bringIntoViewRequester(editor).onGloballyPositioned { coordinates ->
                        editorVisible = coordinates.boundsInWindow().height > 0f
                    } else it.weight(1f, fill = false)
                }.testTag("answer"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                Button(
                    onClick = vm::send,
                    enabled = state.canAnswer && state.answer.isNotBlank(),
                    busy = state.busy != null,
                    modifier = Modifier.testTag("send"),
                ) { Text(stringResource(R.string.interview_send)) }
                TextButton(onClick = vm::skip, enabled = state.canAnswer, busy = state.busy != null, modifier = Modifier.testTag("skip")) {
                    Text(stringResource(R.string.interview_skip))
                }
                if (onVoice != null) {
                    FilledTonalButton(onClick = onVoice, enabled = state.canAnswer, voice = true, busy = state.busy != null, modifier = Modifier.testTag("voice-start")) {
                        Text(stringResource(R.string.voice_start))
                    }
                }
                if (state.canWriteUp) {
                    TextButton(onClick = vm::writeUp, modifier = Modifier.testTag("write-up")) {
                        Text(stringResource(R.string.interview_write_up))
                    }
                }
            }
        }
    }
}

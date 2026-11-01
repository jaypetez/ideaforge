package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick as semanticClick
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
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.core.isLowConfidence
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
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportAction
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportDialog
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportController
import io.github.jaypetez.ideaforge.ui.voice.VoicePermissionDialog
import io.github.jaypetez.ideaforge.ui.voice.beginVoice
import io.github.jaypetez.ideaforge.ui.voice.hasPermission
import io.github.jaypetez.ideaforge.ui.voice.voicePermissions
import io.github.jaypetez.ideaforge.ui.voice.voicePermissionsHeld
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.DictationPhase
import io.github.jaypetez.ideaforge.voice.VoiceMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One coverage pill: a dimension, its level, and whether it is still being asked about. */
data class CoveragePill(val id: String, val label: String, val level: String, val status: String, val reason: String = "")

/** One settled exchange in the thread. */
data class ThreadEntry(val n: Int, val question: String, val answer: String?, val fromChip: Boolean, val id: String = "")

private data class VoicePermissionIntent(
    val request: Long, val permit: InferencePermit, val inputRevision: Long,
    val fromEntry: Boolean, val resume: Boolean,
)

internal fun pillsOf(state: InterviewState): List<CoveragePill> = DIMENSIONS.map { d ->
    val id = stringOf(d["id"]).orEmpty()
    val c = state.session?.get("coverage")?.get(id)
    CoveragePill(id, stringOf(d["label"]).orEmpty(), stringOf(c["level"]) ?: "thin", stringOf(c["status"]) ?: "probing",
        stringOf(c["waivedReason"]).orEmpty())
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
            fromChip = isLowConfidence(t),
            id = stringOf(t["id"]).orEmpty(),
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
    var launchedVoicePermission by remember(vm.sessionId) { mutableStateOf<VoicePermissionIntent?>(null) }
    var screenVisible by remember(vm.sessionId) { mutableStateOf(false) }
    var dictationRationale by remember(vm.sessionId) { mutableStateOf<DictationRequest?>(null) }
    // Keep the launched request immutable until its OS callback, even if Stop retires it.
    var dictationPermission by remember(vm.sessionId) { mutableStateOf<DictationRequest?>(null) }
    val manualPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = dictationPermission
        dictationPermission = null
        if (request != null) scope0.launch(Dispatchers.Main.immediate) {
            vm.dictationPermission(request, granted)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { screenVisible = true }
    var microphoneHeld by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationsHeld by remember { mutableStateOf(hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        microphoneHeld = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        notificationsHeld = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    var rationale by remember { mutableStateOf(false) }
    var voicePermissionIntent by remember { mutableStateOf<VoicePermissionIntent?>(null) }
    LaunchedEffect(inference) {
        vm.refreshConsent()
        if (inference.blocked) {
            voiceRequest.longValue++
            rationale = false
            voicePermissionIntent = null
            entryVoiceInProgress = false
            initialVoicePending = false
            onVoiceEntryConsumed()
        }
    }
    DisposableEffect(vm.sessionId) {
        onDispose {
            vm.cancelDictation()
            voiceRequest.longValue++
            if (initialVoicePending || entryVoiceInProgress) vm.voiceNotStarted(entryInterrupted)
            initialVoicePending = false
            entryVoiceInProgress = false
            voicePermissionIntent = null
            onVoiceEntryConsumed()
        }
    }
    fun ownsVoiceIntent(intent: VoicePermissionIntent): Boolean =
        intent.request == voiceRequest.longValue && vm.ownsVoiceInput(intent.inputRevision) &&
            screenVisible && isCurrentView() && vm.reports.state.value.output == null && vm.state.value.dimensionEdit == null

    fun beginAdmittedVoice(intent: VoicePermissionIntent, mic: Boolean) {
        if (!ownsVoiceIntent(intent) || drive == null) return
        if (intent.fromEntry && !mic) {
            vm.voiceNotStarted(entryDenied)
            return
        }
        if (intent.fromEntry && !vm.state.value.canAnswer) {
            vm.voiceNotStarted(entryCancelled)
            return
        }
        if (intent.resume) {
            val current = drive.state.value
            if (current.sessionId == vm.sessionId && current.mode in setOf(VoiceMode.PAUSED, VoiceMode.BLOCKED)) {
                if (mic) drive.resume() else drive.microphoneDenied(deniedMessage)
            }
        } else beginVoice(drive, vm.handOffToVoice(), mic, deniedMessage)
    }

    // Keep the actual launched intent until its callback, never a newer rationale's permit.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val intent = launchedVoicePermission
        launchedVoicePermission = null
        scope0.launch(Dispatchers.Main.immediate) {
            if (intent == null || !ownsVoiceIntent(intent)) return@launch
            if (voicePermissionIntent === intent) voicePermissionIntent = null
            val allowed = vm.authorizeVoice(intent.permit)
            if (!ownsVoiceIntent(intent)) return@launch
            if (!allowed) {
                entryVoiceInProgress = false
                return@launch
            }
            val mic = grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
            microphoneHeld = mic
            notificationsHeld = grants[Manifest.permission.POST_NOTIFICATIONS] ?: hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            entryVoiceInProgress = false
            beginAdmittedVoice(intent, mic)
        }
    }
    fun requestVoice(fromEntry: Boolean) {
        val inputRevision = vm.retireVoiceInput()
        dictationRationale = null
        if (!fromEntry) {
            initialVoicePending = false
            onVoiceEntryConsumed()
        }
        val my = ++voiceRequest.longValue
        entryVoiceInProgress = fromEntry
        vm.voiceRetry()
        val expected = vm.inferenceState.value.permit ?: run {
            entryVoiceInProgress = false
            vm.refreshConsent()
            return
        }
        val resume = drive?.state?.value?.let {
            it.sessionId == vm.sessionId && it.mode in setOf(VoiceMode.PAUSED, VoiceMode.BLOCKED)
        } == true
        val intent = VoicePermissionIntent(my, expected, inputRevision, fromEntry, resume)
        scope0.launch(Dispatchers.Main.immediate) {
            val allowed = vm.authorizeVoice(expected)
            if (!ownsVoiceIntent(intent)) return@launch
            if (!allowed) {
                entryVoiceInProgress = false
                return@launch
            }
            if (fromEntry && (!screenVisible || !vm.state.value.canAnswer)) {
                entryVoiceInProgress = false
                vm.voiceNotStarted(entryCancelled)
                return@launch
            }
            voicePermissionIntent = intent
            if (drive != null) {
                val ready = if (intent.resume) hasPermission(context, Manifest.permission.RECORD_AUDIO) else voicePermissionsHeld(context)
                if (ready) {
                    entryVoiceInProgress = false
                    beginAdmittedVoice(intent, mic = true)
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
        vm.cancelDictation()
        dictationRationale = null
        vm.flushDraft()
        screenVisible = false
        voiceRequest.longValue++
        rationale = false
        voicePermissionIntent = null
        if (initialVoicePending || entryVoiceInProgress) {
            initialVoicePending = false
            entryVoiceInProgress = false
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
    LaunchedEffect(state.dictation.active) {
        if (!state.dictation.active) dictationRationale = null
    }
    dictationRationale?.let { request ->
        VoicePermissionDialog(
            notificationsNeeded = false,
            manual = true,
            onContinue = {
                scope0.launch(Dispatchers.Main.immediate) {
                    if (vm.authorizeDictation(request) && dictationRationale === request &&
                        dictationPermission == null && screenVisible && isCurrentView()) {
                        dictationRationale = null
                        dictationPermission = request
                        manualPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            onDismiss = { dictationRationale = null; vm.cancelDictation() },
        )
    }
    ContentReportDialog(vm.reports, onReplace = { vm.replaceReportedQuestion(fromVoice = voiceAttached) })
    state.dimensionEdit?.let {
        DimensionDialog(it, vm::chooseDimension, vm::dimensionReason, vm::confirmDimension, { vm.dismissDimension() })
    }
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
                val intent = voicePermissionIntent
                scope0.launch(Dispatchers.Main.immediate) {
                    if (intent != null && vm.authorizeVoice(intent.permit) && ownsVoiceIntent(intent)
                        && voicePermissionIntent === intent && rationale && launchedVoicePermission == null) {
                        rationale = false
                        launchedVoicePermission = intent
                        permissions.launch(voicePermissions())
                    }
                }
            },
            onDismiss = {
                voiceRequest.longValue++
                rationale = false
                voicePermissionIntent = null
                if (entryVoiceInProgress) {
                    entryVoiceInProgress = false
                    vm.voiceNotStarted(entryCancelled)
                }
            },
        )
    }

    if (drive != null && voice != null && voiceHere && voice.attached) {
        val session = drive.reportingSession()?.takeIf { stringOf(it["id"]) == vm.sessionId }
        val displayedTurn = (session?.get("turns") as? JsonArray).orEmpty()
            .lastOrNull { stringOf(it["question"]) == voice.question }
        val turnId = stringOf(displayedTurn?.get("id"))
        Column(modifier.fillMaxSize().testTag("interview")) {
            SnackbarHost(snackbar)
            VoiceStage(
                voice,
                onPause = { voiceRequest.longValue++; voicePermissionIntent = null; rationale = false; drive.pause() },
                onResume = askOrStart,
                onExit = { voiceRequest.longValue++; voicePermissionIntent = null; rationale = false; drive.exit() },
                modifier = Modifier.weight(1f),
                ideaTitle = state.session?.let(::sessionDisplayTitle).orEmpty(),
                microphoneDenied = voice.mode == VoiceMode.BLOCKED && !microphoneHeld,
                notificationsOff = voicePermissions().contains(Manifest.permission.POST_NOTIFICATIONS) && !notificationsHeld,
                questionOutput = session?.let { reportableOutput(it, "question", turnId) },
                suggestionsOutput = session?.let { reportableOutput(it, "suggestions", turnId) },
                onReport = { output ->
                    if (session != null && screenVisible && isCurrentView()) {
                        vm.reports.open(session, output, drive)
                    }
                },
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
            val scrollWithComposer = shortWindow || LocalDensity.current.fontScale > 1f || coverageExpanded ||
                state.dictation.active || state.dictation.outcome != null
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
                    CoverageStrip(state, coverageExpanded, toggle = { coverageExpanded = !coverageExpanded }, onDimension = { id ->
                        if (screenVisible && isCurrentView()) {
                            voiceRequest.longValue++
                            rationale = false
                            voicePermissionIntent = null
                            dictationRationale = null
                            initialVoicePending = false
                            entryVoiceInProgress = false
                            onVoiceEntryConsumed()
                            vm.openDimension(id)
                        }
                    })
                    if (state.busy != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("interview-busy"))
                    } else {
                        Spacer(Modifier.height(IdeaForgeSpacing.micro))
                    }
                    Conversation(state, vm, if (scrollWithComposer) Modifier else Modifier.weight(1f),
                        scrollWithComposer, followCurrent = !coverageExpanded && !state.dictation.active, expanded = historyExpanded,
                        onHistoryChange = { historyExpanded = it })
                    // Wrapped actions and enlarged drafts must not consume the entire thread.
                    Composer(
                        state, vm, onVoice = if (drive != null) askOrStart else null,
                        onDictation = if (!vm.canDictate) null else {
                            {
                                if (state.dictation.active) vm.stopDictation()
                                else if (dictationPermission == null) {
                                    voiceRequest.longValue++
                                    voicePermissionIntent = null
                                    rationale = false
                                    entryVoiceInProgress = false
                                    initialVoicePending = false
                                    onVoiceEntryConsumed()
                                    scope0.launch(Dispatchers.Main.immediate) {
                                        val request = vm.requestDictation { screenVisible && isCurrentView() }
                                        if (request != null) {
                                            if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                                vm.dictationPermission(request, true)
                                            } else dictationRationale = request
                                        }
                                    }
                                }
                            }
                        },
                        permissionPending = dictationPermission != null,
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
private fun CoverageStrip(state: InterviewState, expanded: Boolean, toggle: () -> Unit, onDimension: (String) -> Unit) {
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
            Text(stringResource(R.string.dimension_hint), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = IdeaForgeSpacing.tight))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                for (pill in pillsOf(state)) Pill(pill, enabled = state.session != null && state.busy != Busy.Writing) {
                    onDimension(pill.id)
                }
            }
        }
    }
}

@Composable
private fun Pill(pill: CoveragePill, enabled: Boolean, onClick: () -> Unit) {
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
    val fullDescription = if (pill.reason.isNotEmpty()) stringResource(R.string.dimension_pill_reason, description, pill.reason) else description
    val actionLabel = stringResource(R.string.dimension_manage, pill.label)
    Surface(
        onClick = onClick, enabled = enabled, shape = IdeaForgeShape.status, border = BorderStroke(1.dp, accent),
        color = if (probing && pill.level == "covered") accent.copy(alpha = 0.12f) else scheme.surface,
        modifier = Modifier.sizeIn(minWidth = IdeaForgeSpacing.control, minHeight = IdeaForgeSpacing.control).testTag("pill-${pill.id}")
            .semantics { semanticClick(label = actionLabel, action = null) },
    ) {
        Row(
            Modifier.padding(horizontal = IdeaForgeSpacing.related, vertical = IdeaForgeSpacing.tight)
                .semantics { contentDescription = fullDescription },
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
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (probing) scheme.onSurface else scheme.onSurfaceVariant,
                    fontStyle = if (probing) FontStyle.Normal else FontStyle.Italic,
                )
                if (pill.reason.isNotEmpty()) Text(stringResource(R.string.dimension_has_reason),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
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
                if (expanded) for (entry in thread) ThreadItem(entry, state.session, vm.reports)
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
            if (expanded) items(thread, key = { "turn-${it.n}" }) { ThreadItem(it, state.session, vm.reports) }
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
            else threadOf(state).lastOrNull()?.let { ThreadItem(it, state.session, vm.reports, "busy") }
        }
        open != null -> QuestionCard(state, open, vm)
    }
}

@Composable
private fun ThreadItem(e: ThreadEntry, session: Session?, reports: ContentReportController, surface: String = "history") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        ContentReportAction(session?.let { reportableOutput(it, "question", e.id) }, "report-$surface-question-${e.id}") {
            if (session != null) reports.open(session, it)
        }
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
                            ContentReportAction(session?.let { reportableOutput(it, "suggestions", e.id) },
                                "report-$surface-suggestions-${e.id}") {
                                if (session != null) reports.open(session, it)
                            }
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
            ContentReportAction(state.session?.let { reportableOutput(it, "question", stringOf(open["id"])) },
                "report-current-question") { state.session?.let { session -> vm.reports.open(session, it) } }
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
                if (stringOf(open["questionSource"]) == "replacement") {
                    Text(stringResource(R.string.report_replacement_source), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("replacement-source"))
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
            ContentReportAction(state.session?.let { reportableOutput(it, "suggestions", stringOf(open["id"])) },
                "report-current-suggestions") { state.session?.let { session -> vm.reports.open(session, it) } }
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
    onDictation: (() -> Unit)? = null,
    permissionPending: Boolean = false,
    shortWindow: Boolean = false,
    scrollWithPage: Boolean = shortWindow,
    noticeHeight: Int = 0,
    readingDetails: Boolean = false,
) {
    val editor = remember { BringIntoViewRequester() }
    val dictationAction = remember { BringIntoViewRequester() }
    LaunchedEffect(state.dictation.phase, state.dictation.outcome) {
        if (scrollWithPage && !readingDetails && (state.dictation.active || state.dictation.outcome != null)) {
            dictationAction.bringIntoView()
        }
    }
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
            if (onDictation != null) DictationFeedback(state.dictation)
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
                if (onDictation != null) {
                    FilledTonalButton(
                        onClick = onDictation,
                        enabled = state.canAnswer && (state.dictation.active || !permissionPending),
                        voice = true,
                        modifier = Modifier.widthIn(min = IdeaForgeSpacing.voice)
                            .bringIntoViewRequester(dictationAction).testTag("dictation-toggle"),
                    ) {
                        Text(stringResource(when (state.dictation.phase) {
                            DictationPhase.TRANSCRIBING, DictationPhase.STOPPING -> R.string.dictation_cancel
                            null -> R.string.dictation_start
                            else -> R.string.dictation_stop
                        }))
                    }
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

@Composable
private fun DictationFeedback(state: DictationState) {
    val label = state.phase?.let { phase ->
        stringResource(when (phase) {
            DictationPhase.PREPARING -> R.string.dictation_preparing
            DictationPhase.PERMISSION -> R.string.dictation_permission
            DictationPhase.LISTENING -> R.string.dictation_listening
            DictationPhase.STOPPING -> R.string.dictation_stopping
            DictationPhase.TRANSCRIBING -> R.string.dictation_transcribing
        })
    } ?: state.outcome?.let { outcome ->
        stringResource(when (outcome) {
            DictationOutcome.ADDED -> R.string.dictation_added
            DictationOutcome.EMPTY -> R.string.dictation_empty
            DictationOutcome.DENIED -> R.string.dictation_denied
            DictationOutcome.CANCELLED -> R.string.dictation_cancelled
            DictationOutcome.INTERRUPTED -> R.string.dictation_interrupted
            DictationOutcome.FAILED -> R.string.dictation_failed
        })
    }
    if (label != null) {
        IdeaForgeStatusText(
            listOf(label, state.detail).filter { it.isNotEmpty() }.joinToString(" "),
            modifier = Modifier.testTag("dictation-status"),
            tone = if (state.outcome in setOf(DictationOutcome.DENIED, DictationOutcome.FAILED)) StatusTone.Error else StatusTone.Neutral,
        )
    } else {
        Text(stringResource(R.string.dictation_hint), style = MaterialTheme.typography.bodySmall)
    }
    if (state.caption.isNotEmpty()) Text(
        state.caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("dictation-caption"),
    )
}

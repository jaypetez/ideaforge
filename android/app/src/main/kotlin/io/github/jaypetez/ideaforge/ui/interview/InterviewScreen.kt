package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.jaypetez.ideaforge.ui.voice.beginVoice
import io.github.jaypetez.ideaforge.ui.voice.hasPermission
import io.github.jaypetez.ideaforge.ui.voice.voicePermissions
import io.github.jaypetez.ideaforge.ui.voice.voicePermissionsHeld
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.VoiceMode
import kotlinx.coroutines.launch
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
 * The typed interview: a coverage strip, the thread so far, the open question with its
 * bridge and suggestion chips, and a composer docked above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterviewScreen(
    vm: InterviewViewModel,
    onFinished: (synthesisFailed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    drive: DriveController? = null,
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope0 = rememberCoroutineScope()
    val voice = drive?.state?.collectAsStateWithLifecycle()?.value
    // A drive outlives this screen; coming back to its idea re-attaches to the stage.
    val voiceHere = voice != null && voice.sessionId == vm.sessionId
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.voice_mic_denied)
    val notificationsOff = stringResource(R.string.voice_notifications_denied)
    var rationale by rememberSaveable { mutableStateOf(false) }
    // The answer to the permission prompt, then the tap's intent carried out.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val mic = grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (drive == null) return@rememberLauncherForActivityResult
        val attached = drive.state.value.let { it.sessionId == vm.sessionId && it.mode == VoiceMode.BLOCKED }
        val started = if (attached) {
            if (mic) drive.resume() else Unit
            mic
        } else {
            beginVoice(drive, vm.handOffToVoice(), mic, deniedMessage)
        }
        if (started && grants[Manifest.permission.POST_NOTIFICATIONS] == false) {
            scope0.launch { snackbar.showSnackbar(notificationsOff) }
        }
    }
    val askOrStart: () -> Unit = {
        if (drive != null) {
            when {
                voicePermissionsHeld(context) -> {
                    val blocked = drive.state.value.let { it.sessionId == vm.sessionId && it.mode == VoiceMode.BLOCKED }
                    if (blocked) drive.resume() else beginVoice(drive, vm.handOffToVoice(), micGranted = true, deniedMessage)
                }
                else -> rationale = true
            }
        }
    }

    LaunchedEffect(voice?.exit, voiceHere) {
        if (voiceHere && voice?.exit != null) {
            drive?.exitShown()
            vm.reload()
        }
    }

    // Leaving the screen (another app, the lock screen) writes a queued draft at once.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushDraft() }

    LaunchedEffect(state.finished) {
        if (state.finished) {
            vm.finishedShown()
            // The failure travels with the navigation: a snackbar here would never be seen.
            onFinished(state.writeUpFailed)
        }
    }
    // Shown from a scope that outlives the notice: clearing it must not cancel the snackbar.
    val scope = rememberCoroutineScope()
    val noticeText = state.notice?.let { noticeText(it) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            vm.noticeShown()
            scope.launch { snackbar.showSnackbar(noticeText) }
        }
    }

    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(R.string.voice_mic_title)) },
            text = { Text(stringResource(R.string.voice_mic_rationale)) },
            confirmButton = {
                TextButton(onClick = { rationale = false; permissions.launch(voicePermissions()) }, modifier = Modifier.testTag("voice-mic-continue")) {
                    Text(stringResource(R.string.voice_mic_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { rationale = false }) { Text(stringResource(R.string.voice_mic_cancel)) }
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
                    // A blocked drive may be blocked on the permission itself: ask again.
                    if (voice.mode == VoiceMode.BLOCKED && !voicePermissionsHeld(context)) rationale = true else drive.resume()
                },
                onExit = drive::exit,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    Column(modifier.fillMaxSize().testTag("interview")) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            val barHeight = if (onBack != null) TopAppBarDefaults.TopAppBarExpandedHeight else 0.dp
            val bodyHeight = (maxHeight - barHeight).coerceAtLeast(0.dp)
            val composerMaxHeight = bodyHeight * 0.6f
            // An open IME can leave less than one editor plus wrapped action rows.
            // Scroll the real content instead of weighting the editor down to zero.
            val shortWindow = bodyHeight < 240.dp * LocalDensity.current.fontScale
            // A fixed bar can leave less height than one enlarged editor above the IME.
            val scrollHeader = shortWindow && WindowInsets.isImeVisible
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize()) {
                if (!scrollHeader) InterviewHeader(state, onBack)
                SnackbarHost(snackbar)
                Column(Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = IdeaForgeWidths.work)
                    .let { if (shortWindow) it.verticalScroll(scroll) else it }) {
                    if (scrollHeader) InterviewHeader(state, onBack)
                    CoverageStrip(state)
                    if (state.busy != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("interview-busy"))
                    } else {
                        Spacer(Modifier.height(IdeaForgeSpacing.micro))
                    }
                    Conversation(state, vm, if (shortWindow) Modifier else Modifier.weight(1f), scrollWithComposer = shortWindow)
                    // Wrapped actions and enlarged drafts must not consume the entire thread.
                    Composer(
                        state, vm, onVoice = if (drive != null) askOrStart else null,
                        modifier = if (shortWindow) Modifier else Modifier.heightIn(max = composerMaxHeight),
                        shortWindow = shortWindow,
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
            IconButton(onClick = onBack, modifier = Modifier.testTag("interview-back")) {
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
}

@Composable
private fun CoverageStrip(state: InterviewState) {
    val pct = state.session?.let(::coveragePercent) ?: 0
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight)
            .testTag("coverage-strip"),
        horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.interview_coverage, pct),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("coverage-percent"),
        )
        for (pill in pillsOf(state)) Pill(pill)
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
            pill.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (probing) scheme.onSurface else scheme.onSurfaceVariant,
            fontStyle = if (probing) FontStyle.Normal else FontStyle.Italic,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(state: InterviewState, vm: InterviewViewModel, modifier: Modifier, scrollWithComposer: Boolean) {
    val thread = threadOf(state)
    // A short window has one page scroller. A nested lazy viewport can scroll a chip
    // into its own bounds while leaving it clipped behind the contextual bar.
    if (scrollWithComposer) {
        val current = remember { BringIntoViewRequester() }
        val wrap = remember { BringIntoViewRequester() }
        LaunchedEffect(state.openTurn?.get("n"), state.wrapOffer) {
            if (state.busy != null || state.notice != null) return@LaunchedEffect
            if (state.wrapOffer != null) wrap.bringIntoView() else current.bringIntoView()
        }
        Column(
            modifier.fillMaxWidth().testTag("thread")
                .padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
        ) {
            for (entry in thread) ThreadItem(entry)
            Box(Modifier.fillMaxWidth().bringIntoViewRequester(current).testTag("current-turn")) {
                OpenQuestion(state, vm)
            }
            state.wrapOffer?.let {
                Box(Modifier.fillMaxWidth().bringIntoViewRequester(wrap)) { WrapOffer(it, vm) }
            }
        }
        return
    }
    val list = rememberLazyListState()
    // Keep the open question in view as the thread grows.
    LaunchedEffect(thread.size, state.busy, state.wrapOffer) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag("thread"),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
        verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
    ) {
        items(thread, key = { "turn-${it.n}" }) { ThreadItem(it) }
        item(key = "open") { OpenQuestion(state, vm) }
        state.wrapOffer?.let { offer ->
            item(key = "wrap") { WrapOffer(offer, vm) }
        }
    }
}

@Composable
private fun OpenQuestion(state: InterviewState, vm: InterviewViewModel) {
    val open = state.openTurn
    when {
        state.session == null -> Text(stringResource(R.string.interview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.busy != null -> BusyLine(state.busy)
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
        Card(
            Modifier.fillMaxWidth().testTag("question-card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(IdeaForgeSpacing.content), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                if (bridge.isNotEmpty()) {
                    Text(bridge, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("bridge"))
                }
                Text(
                    question,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("question").semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
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
) {
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            OutlinedTextField(
                value = state.answer,
                onValueChange = vm::onAnswerChange,
                label = { Text(stringResource(R.string.interview_answer_label)) },
                placeholder = { Text(stringResource(R.string.interview_answer_placeholder)) },
                enabled = state.canAnswer,
                minLines = if (shortWindow) 1 else 2,
                maxLines = if (shortWindow) 1 else 6,
                modifier = Modifier.fillMaxWidth().let { if (shortWindow) it else it.weight(1f, fill = false) }.testTag("answer"),
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
                    FilledTonalButton(onClick = vm::writeUp, modifier = Modifier.testTag("write-up")) {
                        Text(stringResource(R.string.interview_write_up))
                    }
                }
            }
        }
    }
}

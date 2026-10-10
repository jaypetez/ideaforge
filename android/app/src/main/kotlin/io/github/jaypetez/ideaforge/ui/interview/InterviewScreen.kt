package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.DIMENSIONS
import io.github.jaypetez.ideaforge.core.HARD_TURN_CEILING
import io.github.jaypetez.ideaforge.core.coveragePercent
import io.github.jaypetez.ideaforge.core.getDimension
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.ui.theme.ExtraColors
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
@Composable
fun InterviewScreen(vm: InterviewViewModel, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Leaving the screen (another app, the lock screen) writes a queued draft at once.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushDraft() }

    LaunchedEffect(state.finished) {
        if (state.finished) {
            vm.finishedShown()
            onFinished()
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

    Box(modifier.fillMaxSize().testTag("interview")) {
        Column(Modifier.fillMaxSize()) {
            CoverageStrip(state)
            if (state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("interview-busy"))
            } else {
                Spacer(Modifier.height(4.dp))
            }
            Conversation(state, vm, Modifier.weight(1f))
            Composer(state, vm)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
    }
}

@Composable
private fun noticeText(n: InterviewNotice): String = when (n) {
    is InterviewNotice.NoProvider -> stringResource(R.string.notice_no_provider, n.detail)
    is InterviewNotice.Fallback -> stringResource(R.string.notice_fallback, n.detail)
    is InterviewNotice.WriteUpFailed -> stringResource(R.string.notice_write_up_failed, n.detail)
    is InterviewNotice.Failed -> stringResource(R.string.notice_interview_failed, n.detail)
}

@Composable
private fun CoverageStrip(state: InterviewState) {
    val pct = state.session?.let(::coveragePercent) ?: 0
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("coverage-strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
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
        pill.level == "covered" -> if (dark) ExtraColors.successDark else ExtraColors.successLight
        pill.level == "partial" -> if (dark) ExtraColors.warningDark else ExtraColors.warningLight
        else -> scheme.outline
    }
    val description = stringResource(R.string.interview_pill, pill.label, word)
    Row(
        Modifier
            .border(1.dp, accent, CircleShape)
            .background(if (probing && pill.level == "covered") accent.copy(alpha = 0.12f) else Color.Transparent, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("pill-${pill.id}")
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A filled dot for covered, a ring for partial, nothing louder than the outline for thin.
        Box(
            Modifier.size(8.dp).let {
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
private fun Conversation(state: InterviewState, vm: InterviewViewModel, modifier: Modifier) {
    val thread = threadOf(state)
    val list = rememberLazyListState()
    // Keep the open question in view as the thread grows.
    LaunchedEffect(thread.size, state.busy, state.wrapOffer) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag("thread"),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(thread, key = { "turn-${it.n}" }) { ThreadItem(it) }
        item(key = "open") {
            val open = state.openTurn
            when {
                state.session == null -> Text(stringResource(R.string.interview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.busy != null -> BusyLine(state.busy)
                open != null -> QuestionCard(state, open, vm)
            }
        }
        state.wrapOffer?.let { offer ->
            item(key = "wrap") { WrapOffer(offer, vm) }
        }
    }
}

@Composable
private fun ThreadItem(e: ThreadEntry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(e.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("busy-line"))
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth().testTag("question-card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Row(Modifier.testTag("turnline")) {
                    Text(turnline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.degraded) {
                        val warning = if (isSystemInDarkTheme()) ExtraColors.warningDark else ExtraColors.warningLight
                        Text(
                            " · " + stringResource(R.string.interview_from_checklist),
                            style = MaterialTheme.typography.labelMedium,
                            color = warning,
                            modifier = Modifier.testTag("degraded"),
                        )
                    }
                }
            }
        }
        if (chips.isNotEmpty()) {
            val label = stringResource(R.string.interview_suggestions)
            FlowRow(
                Modifier.fillMaxWidth().testTag("chips").semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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

@Composable
private fun WrapOffer(offer: String, vm: InterviewViewModel) {
    Card(
        Modifier.fillMaxWidth().testTag("wrap-offer"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(offer, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun Composer(state: InterviewState, vm: InterviewViewModel) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.answer,
                onValueChange = vm::onAnswerChange,
                label = { Text(stringResource(R.string.interview_answer_label)) },
                placeholder = { Text(stringResource(R.string.interview_answer_placeholder)) },
                enabled = state.canAnswer,
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("answer"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::send,
                    enabled = state.canAnswer && state.answer.isNotBlank(),
                    modifier = Modifier.testTag("send"),
                ) { Text(stringResource(R.string.interview_send)) }
                TextButton(onClick = vm::skip, enabled = state.canAnswer, modifier = Modifier.testTag("skip")) {
                    Text(stringResource(R.string.interview_skip))
                }
                Spacer(Modifier.weight(1f))
                if (state.canWriteUp) {
                    FilledTonalButton(onClick = vm::writeUp, modifier = Modifier.testTag("write-up")) {
                        Text(stringResource(R.string.interview_write_up))
                    }
                }
            }
        }
    }
}

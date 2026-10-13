package io.github.jaypetez.ideaforge.ui.result

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.library.ExportPreview
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton as OutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeSnackbarHost as SnackbarHost
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.readableColor
import kotlinx.coroutines.launch

object ResultTags {
    const val SCREEN = "result"
    const val TITLE = "result-title"
    const val META = "result-meta"
    const val SYNTHESIS_FAILED = "synthesis-failed"
    const val OUTPUT = "result-output"
    const val CONTENT = "result-content"
    const val ACTIONS = "result-actions"
    const val SHARE = "result-share"
    const val COPY = "result-copy"
    const val SAVE = "result-save"
    const val ASK_MORE = "result-ask-more"
    const val BACK = "result-back"
    const val MISSING = "result-missing"
}

/**
 * The finished idea: the web's done panel. The export is rendered through core's
 * renderBlocks (selectable, scrolling), with the done-meta line above it, and Share .md,
 * Copy markdown, Save .md and Ask me more below, all handing over [exportFor]'s bytes.
 *
 * [synthesisFailed] is the interview's word that the write-up just failed
 * (ResultRoute.synthesisFailed); a stored idea with no refined prompt says the same on its
 * own, so the message also shows when the idea is opened later from the library.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(
    vm: ResultViewModel,
    onBack: () -> Unit,
    onAskMore: () -> Unit,
    modifier: Modifier = Modifier,
    synthesisFailed: Boolean = false,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    // Snackbars outlive the notice that raised them: clearing it must not cancel the message.
    val scope = rememberCoroutineScope()
    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MARKDOWN_MIME)) { uri ->
        if (uri != null) vm.save(uri)
    }

    LaunchedEffect(state.reopened) {
        if (state.reopened) {
            vm.reopenedShown()
            onAskMore()
        }
    }
    val noticeText = state.notice?.let { noticeText(it) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            vm.noticeShown()
            say(noticeText)
        }
    }

    val title = state.title(stringResource(R.string.result_title_fallback))
    val chooserTitle = stringResource(R.string.result_share_chooser)
    val clipLabel = stringResource(R.string.result_clip_label)
    val copied = stringResource(R.string.result_copied)
    val ready = state.session != null

    val actions: @Composable () -> Unit = {
        Surface(tonalElevation = 2.dp, modifier = Modifier.testTag(ResultTags.ACTIONS)) {
            Column {
                HorizontalDivider()
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
                    horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                ) {
                    Button(
                        onClick = {
                            try {
                                context.startActivity(ResultActions.chooser(context, state.export, state.filename, title, chooserTitle, vm.now()))
                            } catch (e: Exception) {
                                say(resources.getString(R.string.result_share_failed, e.message ?: e.toString()))
                            }
                        },
                        enabled = ready,
                        modifier = Modifier.testTag(ResultTags.SHARE),
                    ) { Text(stringResource(R.string.result_share)) }
                    OutlinedButton(
                        onClick = { if (ResultActions.copy(context, state.export, clipLabel)) say(copied) },
                        enabled = ready,
                        modifier = Modifier.testTag(ResultTags.COPY),
                    ) { Text(stringResource(R.string.result_copy)) }
                    OutlinedButton(
                        onClick = { save.launch(state.filename) },
                        enabled = ready,
                        modifier = Modifier.testTag(ResultTags.SAVE),
                    ) { Text(stringResource(R.string.result_save)) }
                    OutlinedButton(
                        onClick = vm::askMore,
                        enabled = ready && !state.reopening,
                        busy = state.reopening,
                        modifier = Modifier.testTag(ResultTags.ASK_MORE),
                    ) { Text(stringResource(R.string.result_ask_more)) }
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag(ResultTags.SCREEN)) {
        // A wrapped dock can leave no document viewport in a short, enlarged window.
        val shortWindow = maxHeight < 240.dp * LocalDensity.current.fontScale
        Scaffold(
            Modifier.fillMaxSize(),
            // The app shell's Scaffold has already applied the system-bar insets.
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(ResultTags.TITLE)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag(ResultTags.BACK)) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.result_back))
                        }
                    },
                    windowInsets = WindowInsets(0),
                )
            },
            snackbarHost = { if (!shortWindow) SnackbarHost(snackbar) },
            bottomBar = {
                if (!shortWindow) actions()
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // A timed notice cannot follow the document offscreen.
                if (shortWindow) SnackbarHost(snackbar)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(ResultTags.CONTENT)
                        .verticalScroll(rememberScrollState())
                        .padding(IdeaForgeSpacing.content),
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
                ) document@{
                    if (state.missing) {
                        Text(stringResource(R.string.result_missing), Modifier.testTag(ResultTags.MISSING))
                        if (shortWindow) actions()
                        return@document
                    }
                    state.meta?.let { meta ->
                        val line = stringResource(R.string.result_meta, meta.questions, meta.coverage)
                        Text(
                            if (meta.degraded) stringResource(R.string.result_meta_checklist, line) else line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(ResultTags.META),
                        )
                        if (synthesisFailed || state.synthesisFailed) {
                            Text(
                                stringResource(R.string.result_synthesis_failed),
                                color = MaterialTheme.colorScheme.readableColor(MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag(ResultTags.SYNTHESIS_FAILED).semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        SelectionContainer {
                            ExportPreview(state.export, Modifier.fillMaxWidth().testTag(ResultTags.OUTPUT))
                        }
                    }
                    if (shortWindow) actions()
                }
            }
        }
    }
}

@Composable
private fun noticeText(n: ResultNotice): String = when (n) {
    ResultNotice.Saved -> stringResource(R.string.result_saved)
    is ResultNotice.SaveFailed -> stringResource(R.string.result_save_failed, n.detail)
    is ResultNotice.ReopenFailed -> stringResource(R.string.result_reopen_failed, n.detail)
}

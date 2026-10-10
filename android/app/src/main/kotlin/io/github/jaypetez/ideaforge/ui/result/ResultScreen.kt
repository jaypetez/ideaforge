package io.github.jaypetez.ideaforge.ui.result

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.library.ExportPreview
import kotlinx.coroutines.launch

object ResultTags {
    const val SCREEN = "result"
    const val TITLE = "result-title"
    const val META = "result-meta"
    const val SYNTHESIS_FAILED = "synthesis-failed"
    const val OUTPUT = "result-output"
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

    Scaffold(
        modifier.fillMaxSize().testTag(ResultTags.SCREEN),
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
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column {
                    HorizontalDivider()
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            modifier = Modifier.testTag(ResultTags.ASK_MORE),
                        ) { Text(stringResource(R.string.result_ask_more)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.missing) {
                Text(stringResource(R.string.result_missing), Modifier.testTag(ResultTags.MISSING))
                return@Column
            }
            val meta = state.meta ?: return@Column
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
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ResultTags.SYNTHESIS_FAILED),
                )
            }
            SelectionContainer {
                ExportPreview(state.export, Modifier.fillMaxWidth().testTag(ResultTags.OUTPUT))
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

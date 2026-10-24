package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.getDimension
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths

enum class DimensionAction { Waive, Defer, Resume }
enum class DimensionFailure { Save, Changed, Retired }

data class DimensionEdit(
    val id: String,
    val request: Long,
    val status: String,
    val level: String,
    val storedReason: String = "",
    val action: DimensionAction? = null,
    val reason: String = "",
    val busy: Boolean = false,
    val saved: Boolean = false,
    val failure: DimensionFailure? = null,
    val detail: String = "",
    val retainedDraft: String = "",
    val retainedQuestion: String = "",
)

@Composable
internal fun DimensionDialog(
    edit: DimensionEdit,
    choose: (DimensionAction) -> Unit,
    reason: (String) -> Unit,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) {
    val feedback = remember { BringIntoViewRequester() }
    LaunchedEffect(edit.failure, edit.busy, edit.saved) {
        if (edit.failure != null || edit.busy || edit.saved) feedback.bringIntoView()
    }
    val status = stringResource(when (edit.status) {
        "waived" -> R.string.status_waived
        "deferred" -> R.string.status_deferred
        else -> R.string.dimension_probing
    })
    AlertDialog(
        onDismissRequest = { if (!edit.busy) dismiss() },
        modifier = Modifier.padding(IdeaForgeSpacing.content).widthIn(max = IdeaForgeWidths.form)
            .fillMaxWidth().testTag("dimension-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringOf(getDimension(edit.id)["label"]).orEmpty()) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
                Text(stringResource(R.string.dimension_status, status, edit.level), Modifier.testTag("dimension-status"))
                if (edit.storedReason.isNotEmpty()) Text(stringResource(R.string.dimension_saved_reason, edit.storedReason),
                    Modifier.testTag("dimension-stored-reason"))
                Text(stringResource(R.string.dimension_disclosure), Modifier.testTag("dimension-disclosure"),
                    style = MaterialTheme.typography.bodyMedium)
                if (edit.retainedDraft.isNotEmpty()) {
                    Text(stringResource(R.string.dimension_retained_disclosure), Modifier.testTag("dimension-retained-disclosure"))
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                            Text(stringResource(R.string.dimension_retained_question, edit.retainedQuestion),
                                Modifier.testTag("dimension-retained-question"), style = MaterialTheme.typography.bodySmall)
                            Text(edit.retainedDraft, Modifier.testTag("dimension-retained-draft"))
                        }
                    }
                }
                if (edit.saved) {
                    IdeaForgeStatusText(stringResource(R.string.dimension_saved),
                        Modifier.testTag("dimension-saved").bringIntoViewRequester(feedback))
                } else if (edit.action == null) {
                    val actions = if (edit.status == "probing") listOf(DimensionAction.Waive, DimensionAction.Defer)
                        else listOf(DimensionAction.Resume)
                    for (action in actions) IdeaForgeTextButton(
                        onClick = { choose(action) }, modifier = Modifier.testTag("dimension-${action.name.lowercase()}"),
                    ) { Text(stringResource(actionLabel(action))) }
                } else {
                    Text(stringResource(when (edit.action) {
                        DimensionAction.Waive -> R.string.dimension_waive_body
                        DimensionAction.Defer -> R.string.dimension_defer_body
                        DimensionAction.Resume -> R.string.dimension_resume_body
                    }), Modifier.testTag("dimension-confirmation"))
                    if (edit.action == DimensionAction.Waive) IdeaForgeField(
                        edit.reason, reason, Modifier.fillMaxWidth().testTag("dimension-reason"),
                        enabled = !edit.busy, label = { Text(stringResource(R.string.dimension_reason)) },
                        maxLines = 4,
                    )
                }
                if (edit.busy) IdeaForgeStatusText(stringResource(R.string.dimension_saving),
                    Modifier.testTag("dimension-saving").bringIntoViewRequester(feedback))
                if (edit.failure != null) IdeaForgeStatusText(stringResource(when (edit.failure) {
                    DimensionFailure.Save -> R.string.dimension_save_failed
                    DimensionFailure.Changed -> R.string.dimension_changed
                    DimensionFailure.Retired -> R.string.dimension_retired
                }, edit.detail), Modifier.testTag("dimension-error").bringIntoViewRequester(feedback), tone = StatusTone.Error)
            }
        },
        confirmButton = {
            if (edit.saved) IdeaForgeButton(onClick = dismiss, modifier = Modifier.testTag("dimension-done")) {
                Text(stringResource(R.string.action_done))
            } else if (edit.action != null) IdeaForgeButton(
                onClick = confirm, enabled = !edit.busy && edit.failure !in setOf(DimensionFailure.Changed, DimensionFailure.Retired),
                busy = edit.busy, modifier = Modifier.testTag("dimension-confirm"),
            ) { Text(stringResource(if (edit.action == DimensionAction.Waive) R.string.dimension_confirm_waive else actionLabel(edit.action))) }
        },
        dismissButton = {
            if (!edit.saved) IdeaForgeTextButton(onClick = dismiss, enabled = !edit.busy, modifier = Modifier.testTag("dimension-cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun actionLabel(action: DimensionAction): Int = when (action) {
    DimensionAction.Waive -> R.string.dimension_waive
    DimensionAction.Defer -> R.string.dimension_defer
    DimensionAction.Resume -> R.string.dimension_resume
}

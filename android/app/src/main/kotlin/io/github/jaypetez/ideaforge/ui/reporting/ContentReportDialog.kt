package io.github.jaypetez.ideaforge.ui.reporting

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.MAX_REPORT_NOTE
import io.github.jaypetez.ideaforge.core.REPORT_CATEGORIES
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import kotlinx.serialization.json.JsonObject

/** Adjacent to the output it describes. A static/fallback output supplies null: no AI claim. */
@Composable
fun ContentReportAction(output: JsonObject?, tag: String, onReport: (JsonObject) -> Unit) {
    if (output == null) return
    val kind = stringOf(output["kind"])
    val label = stringResource(when (kind) {
        "question" -> R.string.report_ai_question
        "suggestions" -> R.string.report_ai_suggestions
        else -> R.string.report_ai_result
    })
    val action = stringResource(R.string.report_named_action, label)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        IdeaForgeTextButton(onClick = { onReport(output) },
            modifier = Modifier.testTag(tag).semantics { contentDescription = action }) {
            Text(stringResource(R.string.report_action))
        }
    }
}

/** This is an email COMPOSER handoff, never evidence that an email was sent. */
fun contentReportEmail(report: JsonObject, recipient: String, subject: String): Intent {
    require(Regex("[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+").matches(recipient))
    val body = "IdeaForge local content report\n\nCategory: ${stringOf(report["category"])}\n" +
        "Output: ${stringOf(report["output"]["kind"])}\n\n${stringOf(report["output"]["text"]).orEmpty()}\n\n" +
        "Optional note:\n${stringOf(report["note"]).orEmpty()}"
    return Intent(Intent.ACTION_SENDTO, Uri.parse(
        "mailto:$recipient?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}",
    ))
}

@Composable
fun ContentReportDialog(controller: ContentReportController, onReplace: () -> Unit = { controller.replace() }) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (state.output == null) return
    val context = LocalContext.current
    val recipient = stringResource(R.string.report_privacy_email)
    val subject = stringResource(R.string.report_email_subject)
    var emailFailed by remember(state.id) { mutableStateOf(false) }
    ContentReportForm(state, controller::category, controller::note, controller::save, controller::dismiss, onReplace,
        recipient = recipient, emailFailed = emailFailed, onEmail = {
            val report = state.report
            if (report != null && recipient.isNotBlank()) {
                try {
                    context.startActivity(contentReportEmail(report, recipient, subject))
                    emailFailed = false
                } catch (_: Exception) {
                    emailFailed = true
                }
            }
        })
}

@Composable
internal fun ContentReportForm(
    state: ContentReportState,
    category: (String) -> Unit,
    note: (String) -> Unit,
    save: () -> Unit,
    dismiss: () -> Unit,
    replace: () -> Unit,
    recipient: String,
    emailFailed: Boolean = false,
    onEmail: () -> Unit = {},
) {
    val output = state.output ?: return
    var reviewPayload by remember(state.id) { mutableStateOf(false) }
    val feedback = remember { BringIntoViewRequester() }
    LaunchedEffect(state.failure, emailFailed, state.busy) {
        if (state.failure != null || emailFailed || state.busy) feedback.bringIntoView()
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) dismiss() },
        modifier = Modifier.padding(IdeaForgeSpacing.content).widthIn(max = IdeaForgeWidths.form).fillMaxWidth()
            .testTag("content-report-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(if (state.saved) R.string.report_saved_title else R.string.report_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
                Text(stringResource(R.string.report_disclosure), Modifier.testTag("report-disclosure"))
                IdeaForgeTextButton(onClick = { reviewPayload = !reviewPayload }, modifier = Modifier.testTag("report-review")) {
                    Text(stringResource(if (reviewPayload) R.string.report_hide_payload else R.string.report_review_payload))
                }
                if (reviewPayload) Text(stringOf(output["text"]).orEmpty(), Modifier.testTag("report-payload"),
                    style = MaterialTheme.typography.bodyMedium)
                if (!state.saved) {
                    Column(Modifier.selectableGroup()) {
                        for (value in REPORT_CATEGORIES) {
                            Row(Modifier.fillMaxWidth().sizeIn(minHeight = IdeaForgeSpacing.control)
                                .testTag("report-category-$value")
                                .selectable(state.category == value, enabled = !state.busy, role = Role.RadioButton,
                                    onClick = { category(value) }),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.category == value, onClick = null, enabled = !state.busy)
                                Text(stringResource(categoryLabel(value)), Modifier.padding(start = IdeaForgeSpacing.related))
                            }
                        }
                    }
                    IdeaForgeField(state.note, note, Modifier.fillMaxWidth().testTag("report-note"),
                        label = { Text(stringResource(R.string.report_note)) }, enabled = !state.busy, maxLines = 4,
                        isError = state.note.length > MAX_REPORT_NOTE,
                        supportingText = { Text(stringResource(R.string.report_note_limit, state.note.length, MAX_REPORT_NOTE)) })
                } else {
                    IdeaForgeStatusText(stringResource(R.string.report_saved), Modifier.testTag("report-saved"))
                    if (state.replaced) IdeaForgeStatusText(stringResource(R.string.report_replaced), Modifier.testTag("report-replaced"))
                    if (state.canReplace) {
                        Text(stringResource(R.string.report_replace_disclosure))
                        IdeaForgeTextButton(onClick = replace, enabled = !state.busy, modifier = Modifier.testTag("report-replace")) {
                            Text(stringResource(R.string.report_replace))
                        }
                    }
                    if (recipient.isBlank()) Text(stringResource(R.string.report_email_unavailable))
                    else {
                        Text(stringResource(R.string.report_email_disclosure, recipient))
                        IdeaForgeTextButton(onClick = onEmail, enabled = !state.busy, modifier = Modifier.testTag("report-email")) {
                            Text(stringResource(R.string.report_email))
                        }
                    }
                }
                if (state.busy) IdeaForgeStatusText(stringResource(R.string.report_saving),
                    Modifier.testTag("report-saving").bringIntoViewRequester(feedback))
                if (state.failure != null || emailFailed) {
                    val text = if (emailFailed) stringResource(R.string.report_email_failed)
                    else stringResource(when (state.failure) {
                        ReportFailure.Replace -> R.string.report_replace_failed
                        ReportFailure.Retired -> R.string.report_retired
                        else -> R.string.report_save_failed
                    }, state.detail)
                    IdeaForgeStatusText(text, Modifier.testTag("report-error").bringIntoViewRequester(feedback), tone = StatusTone.Error)
                }
            }
        },
        confirmButton = {
            IdeaForgeButton(onClick = if (state.saved) dismiss else save,
                enabled = !state.busy && (state.saved || state.category != null && state.note.length <= MAX_REPORT_NOTE),
                busy = state.busy, modifier = Modifier.testTag(if (state.saved) "report-done" else "report-save")) {
                Text(stringResource(if (state.saved) R.string.report_done else R.string.report_save))
            }
        },
        dismissButton = {
            if (!state.saved) IdeaForgeTextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.testTag("report-cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun categoryLabel(value: String): Int = when (value) {
    "offensive" -> R.string.report_offensive
    "harmful" -> R.string.report_harmful
    "inaccurate" -> R.string.report_inaccurate
    else -> R.string.report_other
}

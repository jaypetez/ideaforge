package io.github.jaypetez.ideaforge.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.isLowConfidence
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.renderBlocks
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

@Composable
internal fun LibraryPreview(session: JsonElement) {
    Column(Modifier.widthIn(max = IdeaForgeWidths.reading), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
        if (stringOf(session["status"]) == "done" || truthy(session["synthesis"]["text"])) {
            ExportPreview(remember(session) { buildExport(session) })
        } else {
            Text(stringResource(R.string.idea_no_write_up), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.idea_transcript), style = MaterialTheme.typography.titleLarge)
            val turns = (session["turns"] as? JsonArray).orEmpty()
            if (turns.none { truthy(it["answer"]) || truthy(it["skipped"]) }) {
                Text(stringResource(R.string.idea_no_preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (turn in turns) {
                Text(stringOf(turn["question"]).orEmpty(), style = MaterialTheme.typography.titleSmall)
                if (stringOf(turn["questionSource"]) == "bank") {
                    Text(stringResource(R.string.interview_from_checklist), style = MaterialTheme.typography.labelMedium)
                }
                if (truthy(turn["skipped"])) {
                    Text(stringResource(R.string.interview_skipped), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (truthy(turn["answer"])) {
                    if (isLowConfidence(turn)) {
                        Text(stringResource(R.string.interview_from_chip), style = MaterialTheme.typography.labelMedium)
                    } else if (stringOf(turn["answerSource"]) == "voice") {
                        Text(stringResource(R.string.idea_dictated), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(stringOf(turn["answer"]).orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val draft = stringOf(session["draftAnswer"]).orEmpty()
        if (draft.isNotBlank()) {
            Text(stringResource(R.string.idea_unsent_draft), style = MaterialTheme.typography.titleMedium)
            Text(draft, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * A read-only rendering of the export markdown: the library's detail pane and the result
 * screen's document (which wraps it in a SelectionContainer and a scroll).
 *
 * The blocks come from `core`'s [renderBlocks], the same parser the web's `#output` uses, so
 * what is shown and the file that is saved or shared are one document. Every span goes in as
 * text: the blocks carry model output, and nothing here interprets it as markup.
 */
@Composable
fun ExportPreview(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { renderBlocks(str(markdown)) }
    val type = MaterialTheme.typography
    Column(Modifier.widthIn(max = IdeaForgeWidths.reading).then(modifier), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        for (block in blocks) {
            when (val kind = stringOf(block["type"])) {
                "hr" -> HorizontalDivider()
                "pre" -> Text(stringOf(block["text"]).orEmpty(), style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                "list" -> Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro)) {
                    val ordered = truthy(block["ordered"])
                    items(block["items"]).forEachIndexed { i, item ->
                        val marker = if (ordered) "${i + 1}. " else "• "
                        Text(spans(item, marker), style = type.bodyMedium)
                    }
                }
                "table" -> Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro)) {
                    // Each cell is a span list; the preview keeps the words, not the emphasis.
                    Text(row(block["head"]), style = type.labelLarge)
                    for (cells in items(block["rows"])) Text(row(cells), style = type.bodyMedium)
                }
                "quote" -> Text(
                    spans(block["spans"]),
                    Modifier.padding(start = IdeaForgeSpacing.related),
                    style = type.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                "h1", "h2" -> Text(spans(block["spans"]), style = type.titleLarge)
                else -> Text(
                    spans(block["spans"]),
                    style = if (kind != null && kind.startsWith("h")) type.titleMedium else type.bodyMedium,
                )
            }
        }
    }
}

private fun items(value: JsonElement?): List<JsonElement> = (value as? JsonArray).orEmpty()

private fun row(cells: JsonElement?): String =
    items(cells).joinToString(" · ") { cell -> items(cell).joinToString("") { stringOf(it["text"]).orEmpty() } }

private fun spans(value: JsonElement?, prefix: String = ""): AnnotatedString = buildAnnotatedString {
    append(prefix)
    for (span in items(value)) {
        val style = SpanStyle(
            fontWeight = if (truthy(span["bold"])) FontWeight.SemiBold else null,
            fontStyle = if (truthy(span["italic"])) FontStyle.Italic else null,
            fontFamily = if (truthy(span["code"])) FontFamily.Monospace else null,
        )
        withStyle(style) { append(stringOf(span["text"]).orEmpty()) }
    }
}

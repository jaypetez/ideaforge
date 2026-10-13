package io.github.jaypetez.ideaforge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeShape
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.controls
import io.github.jaypetez.ideaforge.ui.theme.readableColor
import io.github.jaypetez.ideaforge.ui.theme.statusColors

@Composable
private fun Modifier.control(
    source: MutableInteractionSource,
    voice: Boolean = false,
    busy: Boolean = false,
    focusColor: Color = MaterialTheme.controls.focus,
): Modifier {
    val focused by source.collectIsFocusedAsState()
    val working = stringResource(R.string.control_busy)
    return sizeIn(minWidth = IdeaForgeSpacing.control, minHeight = if (voice) IdeaForgeSpacing.voice else IdeaForgeSpacing.control)
        .then(if (focused) Modifier.border(2.dp, focusColor, IdeaForgeShape.control) else Modifier)
        .semantics { if (busy) stateDescription = working }
}

private val ControlPadding get() = PaddingValues(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.related)

@Composable
fun IdeaForgeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.controls
    Button(
        onClick, modifier.control(source, busy = busy, focusColor = colors.onPrimary), enabled,
        shape = IdeaForgeShape.control, interactionSource = source, contentPadding = ControlPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary, contentColor = colors.onPrimary,
            disabledContainerColor = colors.disabled, disabledContentColor = colors.onDisabled,
        ),
        content = content,
    )
}

@Composable
fun IdeaForgeTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    voice: Boolean = false,
    busy: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.controls
    FilledTonalButton(
        onClick, modifier.control(source, voice, busy), enabled,
        shape = IdeaForgeShape.control, interactionSource = source, contentPadding = ControlPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.tonal, contentColor = colors.onTonal,
            disabledContainerColor = colors.disabled, disabledContentColor = colors.onDisabled,
        ),
        content = content,
    )
}

@Composable
fun IdeaForgeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.controls
    OutlinedButton(
        onClick, modifier.control(source, busy = busy), enabled,
        shape = IdeaForgeShape.control, interactionSource = source, contentPadding = ControlPadding,
        border = BorderStroke(1.dp, colors.edge),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (contentColor == Color.Unspecified) colors.quiet else MaterialTheme.colorScheme.readableColor(contentColor, stateLayer = true),
            disabledContainerColor = colors.disabled, disabledContentColor = colors.onDisabled,
        ),
        content = content,
    )
}

@Composable
fun IdeaForgeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.controls
    TextButton(
        onClick, modifier.control(source, busy = busy), enabled,
        shape = IdeaForgeShape.control, interactionSource = source, contentPadding = ControlPadding,
        colors = ButtonDefaults.textButtonColors(
            contentColor = colors.quiet,
            disabledContainerColor = colors.disabled, disabledContentColor = colors.onDisabled,
        ),
        content = content,
    )
}

@Composable
fun IdeaForgeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    val control = MaterialTheme.controls
    val ink = scheme.readableColor(scheme.onSurface, listOf(scheme.surfaceContainerLowest))
    val hint = scheme.readableColor(scheme.onSurfaceVariant, listOf(scheme.surfaceContainerLowest))
    val error = scheme.readableColor(scheme.error, listOf(scheme.surfaceContainerLowest))
    OutlinedTextField(
        value, onValueChange, modifier.sizeIn(minWidth = IdeaForgeSpacing.control, minHeight = IdeaForgeSpacing.control),
        enabled = enabled, label = label, placeholder = placeholder, trailingIcon = trailingIcon, supportingText = supportingText,
        isError = isError, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        shape = IdeaForgeShape.control, textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerLowest, unfocusedContainerColor = scheme.surfaceContainerLowest,
            errorContainerColor = scheme.surfaceContainerLowest, disabledContainerColor = control.disabled,
            focusedTextColor = ink, unfocusedTextColor = ink, errorTextColor = ink, disabledTextColor = control.onDisabled,
            focusedPlaceholderColor = hint, unfocusedPlaceholderColor = hint, errorPlaceholderColor = hint, disabledPlaceholderColor = control.onDisabled,
            focusedLabelColor = control.quiet, unfocusedLabelColor = scheme.readableColor(scheme.onSurfaceVariant),
            errorLabelColor = scheme.readableColor(scheme.error), disabledLabelColor = control.onDisabled,
            focusedSupportingTextColor = scheme.readableColor(scheme.onSurfaceVariant),
            unfocusedSupportingTextColor = scheme.readableColor(scheme.onSurfaceVariant),
            errorSupportingTextColor = scheme.readableColor(scheme.error), disabledSupportingTextColor = scheme.readableColor(scheme.onSurfaceVariant),
            focusedBorderColor = control.focus, unfocusedBorderColor = control.edge, errorBorderColor = error, disabledBorderColor = control.edge,
            cursorColor = control.focus, errorCursorColor = error,
            focusedTrailingIconColor = hint, unfocusedTrailingIconColor = hint, errorTrailingIconColor = error, disabledTrailingIconColor = control.onDisabled,
        ),
    )
}

@Composable
fun IdeaForgeSuggestionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.controls
    SuggestionChip(
        onClick, label, modifier.control(source), enabled,
        shape = IdeaForgeShape.control, interactionSource = source, border = BorderStroke(1.dp, colors.edge),
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, labelColor = colors.quiet,
            disabledContainerColor = colors.disabled, disabledLabelColor = colors.onDisabled,
        ),
    )
}

@Composable
fun IdeaForgeFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.controls
    FilterChip(
        selected, onClick, label, modifier.control(source),
        shape = IdeaForgeShape.control, interactionSource = source,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.focus else colors.edge),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, labelColor = colors.quiet,
            selectedContainerColor = colors.tonal, selectedLabelColor = colors.onTonal,
        ),
    )
}

enum class StatusTone { Neutral, Warning, Error }

@Composable
fun IdeaForgeStatusText(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val label = when (tone) {
        StatusTone.Neutral -> text
        StatusTone.Warning -> stringResource(R.string.status_warning_message, text)
        StatusTone.Error -> stringResource(R.string.status_error_message, text)
    }
    val color = when (tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.readableColor(MaterialTheme.colorScheme.onSurface)
        StatusTone.Warning -> MaterialTheme.statusColors.warning
        StatusTone.Error -> MaterialTheme.colorScheme.readableColor(MaterialTheme.colorScheme.error)
    }
    Text(label, modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = style, color = color)
}

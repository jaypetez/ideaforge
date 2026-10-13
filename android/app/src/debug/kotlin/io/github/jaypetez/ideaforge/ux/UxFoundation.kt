package io.github.jaypetez.ideaforge.ux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeFilterChip
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeSuggestionChip
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTonalButton
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths

/** These two representative Material schemes are simulations, never wallpaper evidence. */
enum class UxFoundationPalette(val id: String, val colourSource: String) {
    FocusStudio("focus-studio", "focus-studio-fallback"),
    Ocean("simulated-ocean", "simulated-dynamic-style"),
    Plum("simulated-plum", "simulated-dynamic-style");

    fun scheme(dark: Boolean, fallback: ColorScheme): ColorScheme = when (this) {
        FocusStudio -> fallback
        Plum -> if (dark) darkColorScheme() else lightColorScheme()
        Ocean -> if (dark) darkColorScheme(
            primary = Color(0xFF83D3E3), onPrimary = Color(0xFF00363F),
            primaryContainer = Color(0xFF004E59), onPrimaryContainer = Color(0xFFA7EEFF),
            secondary = Color(0xFFACCBD3), onSecondary = Color(0xFF19333A),
            secondaryContainer = Color(0xFF324A51), onSecondaryContainer = Color(0xFFCCE7EF),
            background = Color(0xFF0E1419), surface = Color(0xFF0E1419), onSurface = Color(0xFFDDE3E8),
            onSurfaceVariant = Color(0xFFBCC9CF), outline = Color(0xFF87949A),
            surfaceContainerLowest = Color(0xFF091014), surfaceContainerLow = Color(0xFF161D22),
            surfaceContainer = Color(0xFF1A2126), surfaceContainerHigh = Color(0xFF252C31),
            surfaceContainerHighest = Color(0xFF30373C),
        ) else lightColorScheme(
            primary = Color(0xFF006877), onPrimary = Color.White,
            primaryContainer = Color(0xFFA7EEFF), onPrimaryContainer = Color(0xFF004E59),
            secondary = Color(0xFF4A6269), onSecondary = Color.White,
            secondaryContainer = Color(0xFFCCE7EF), onSecondaryContainer = Color(0xFF324A51),
            background = Color(0xFFF5FAFF), surface = Color(0xFFF5FAFF), onSurface = Color(0xFF17212B),
            onSurfaceVariant = Color(0xFF3F484D), outline = Color(0xFF70797E),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF4F8),
            surfaceContainer = Color(0xFFE9EFF3), surfaceContainerHigh = Color(0xFFE3E9ED),
            surfaceContainerHighest = Color(0xFFDDE3E8),
        )
    }

    companion object {
        fun from(id: String): UxFoundationPalette = entries.singleOrNull { it.id == id }
            ?: error("unknown requested foundation palette: $id")
    }
}

/** Uses the production controls; it constructs no app graph or voice/provider effects. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UxFoundationContent(palette: UxFoundationPalette) {
    val scheme = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(IdeaForgeSpacing.content),
        verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.content),
    ) {
        Text("Isolated component · shared controls · ${palette.id}", Modifier.testTag("ux-isolated-label"),
            style = MaterialTheme.typography.labelMedium)
        Text(if (palette == UxFoundationPalette.FocusStudio) "Focus Studio fallback · dynamicColor=false"
            else "Simulated Material palette · not actual wallpaper colours",
            Modifier.testTag("ux-foundation-provenance"), style = MaterialTheme.typography.bodySmall)
        Text("Native shared controls", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            IdeaForgeButton({}, Modifier.testTag("foundation-primary")) { Text(stringResource(R.string.settings_save)) }
            IdeaForgeTonalButton({}, Modifier.testTag("foundation-tonal")) { Text(stringResource(R.string.interview_write_up)) }
            IdeaForgeOutlinedButton({}, Modifier.testTag("foundation-outlined")) { Text(stringResource(R.string.settings_check_connection)) }
            IdeaForgeTextButton({}, Modifier.testTag("foundation-quiet")) { Text(stringResource(R.string.action_cancel)) }
        }
        Surface(color = scheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.widthIn(max = IdeaForgeWidths.form).fillMaxWidth().padding(IdeaForgeSpacing.content),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
                IdeaForgeField("", {}, Modifier.fillMaxWidth().testTag("foundation-field"),
                    label = { Text(stringResource(R.string.onboarding_base_label)) },
                    placeholder = { Text("http://192.168.1.20:11434/v1") })
                IdeaForgeField("", {}, Modifier.fillMaxWidth().testTag("foundation-field-error"), isError = true,
                    label = { Text(stringResource(R.string.settings_model)) },
                    supportingText = { Text("Error: a model is required before this connection can be saved.") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                    IdeaForgeSuggestionChip({}, { Text("A deliberately long suggestion which must remain complete at enlarged native font sizes") },
                        Modifier.testTag("foundation-suggestion"))
                    IdeaForgeFilterChip(selected, { selected = !selected }, { Text("Selected tag") }, Modifier.testTag("foundation-selected"))
                }
            }
        }
        Surface(color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            FlowRow(Modifier.fillMaxWidth().padding(IdeaForgeSpacing.content),
                horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                IdeaForgeButton({}, Modifier.testTag("foundation-disabled"), enabled = false) { Text("Unavailable") }
                IdeaForgeButton({}, Modifier.testTag("foundation-busy"), enabled = false, busy = true) {
                    Text(stringResource(R.string.onboarding_checking))
                }
                IdeaForgeOutlinedButton({}, Modifier.testTag("foundation-destructive"), contentColor = scheme.error) {
                    Text(stringResource(R.string.idea_delete))
                }
                Box {
                    IdeaForgeOutlinedButton({ menu = true }, Modifier.testTag("foundation-menu")) { Text("Open native menu") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.idea_edit)) }, onClick = { menu = false },
                            modifier = Modifier.testTag("foundation-menu-item"))
                    }
                }
                IdeaForgeOutlinedButton({ dialog = true }, Modifier.testTag("foundation-dialog")) { Text("Open native confirmation") }
            }
        }
        IdeaForgeStatusText("The checklist is asking this question; the model did not answer.", Modifier.testTag("foundation-warning"), StatusTone.Warning)
        IdeaForgeStatusText("Nothing was saved or deleted. Check the connection and try again.", Modifier.testTag("foundation-error"), StatusTone.Error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
            IdeaForgeTonalButton({}, Modifier.testTag("foundation-voice-pause"), voice = true) { Text(stringResource(R.string.voice_pause)) }
            IdeaForgeTonalButton({}, Modifier.testTag("foundation-voice-exit"), voice = true) { Text(stringResource(R.string.voice_exit)) }
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                IdeaForgeTextButton({ dialog = false }, Modifier.testTag("foundation-dialog-confirm")) { Text(stringResource(R.string.settings_forget)) }
            },
            dismissButton = { IdeaForgeTextButton({ dialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

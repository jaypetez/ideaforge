package io.github.jaypetez.ideaforge.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeShape
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing

/** Keeps the native host queue/timing and the entire message, without an unbounded overlay. */
@Composable
fun IdeaForgeSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val line = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
        val bodyLimit = (maxHeight * 0.25f).coerceAtMost(IdeaForgeSpacing.voice * 2).coerceAtLeast(line)
        SnackbarHost(state, Modifier.heightIn(max = bodyLimit + IdeaForgeSpacing.control)) { notice ->
            Snackbar(
                dismissAction = {
                    TextButton(
                        onClick = notice::dismiss,
                        modifier = Modifier.sizeIn(minWidth = IdeaForgeSpacing.control, minHeight = IdeaForgeSpacing.control)
                            .testTag("notice-dismiss"),
                        shape = IdeaForgeShape.control,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                    ) { Text(stringResource(R.string.notice_dismiss)) }
                },
            ) {
                Text(
                    notice.visuals.message,
                    Modifier.heightIn(max = bodyLimit).verticalScroll(rememberScrollState()).testTag("notice-message"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

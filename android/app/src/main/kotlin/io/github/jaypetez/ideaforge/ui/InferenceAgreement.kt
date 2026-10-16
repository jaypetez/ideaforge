package io.github.jaypetez.ideaforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.providers.InferenceDestination
import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing

@Composable
fun InferenceAgreement(
    choice: ProviderChoice,
    destination: InferenceDestination?,
    checked: Boolean,
    problem: String?,
    onChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        Text(
            if (choice.local) {
                if (destination == null) stringResource(R.string.inference_local_invalid)
                else stringResource(R.string.inference_local, choice.label, destination.address.orEmpty())
            } else stringResource(R.string.inference_hosted, choice.label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("inference-disclosure"),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = IdeaForgeSpacing.control)
                .toggleable(checked, enabled = destination != null, role = Role.Checkbox, onValueChange = onChange)
                .testTag("inference-consent"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked, onCheckedChange = null, enabled = destination != null)
            Text(stringResource(R.string.inference_agree, choice.label),
                Modifier.padding(start = IdeaForgeSpacing.tight), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.inference_separate), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!problem.isNullOrEmpty()) IdeaForgeStatusText(problem, StatusTone.ERROR)
    }
}

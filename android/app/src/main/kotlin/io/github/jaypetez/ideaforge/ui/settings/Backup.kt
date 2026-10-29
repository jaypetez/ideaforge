package io.github.jaypetez.ideaforge.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton as OutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import kotlinx.coroutines.launch

object BackupTags {
    const val EXPORT = "backup-export"
    const val IMPORT = "backup-import"
    const val STATUS = "backup-status"
}

/**
 * Types offered by the import picker. The web accepts `.json,application/json`; providers
 * label a downloaded `.json` inconsistently, so text and octet-stream are offered too, and
 * parseBackup decides what the file really is.
 */
private val IMPORT_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

/**
 * The web library's "Back up ideas" / "Import backup" pair, as part of the settings screen's
 * Data section. It owns its ViewModel, so moving it is moving one call.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSection(vm: BackupViewModel = viewModel(factory = BackupViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        vm.export(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backup_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.backup_unencrypted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val filename = vm.prepareExport() ?: return@launch
                        try { create.launch(filename) } catch (e: Exception) { vm.exportPickerFailed(e) }
                    }
                },
                enabled = !state.busy,
                busy = state.busy,
                modifier = Modifier.testTag(BackupTags.EXPORT),
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { open.launch(IMPORT_TYPES) },
                enabled = !state.busy,
                busy = state.busy,
                modifier = Modifier.testTag(BackupTags.IMPORT),
            ) { Text(stringResource(R.string.backup_import)) }
        }
        val message = if (state.busy) stringResource(R.string.backup_working) else state.message
        if (message != null) {
            IdeaForgeStatusText(
                message,
                tone = if (state.failed) StatusTone.Error else StatusTone.Neutral,
                modifier = Modifier
                    .testTag(BackupTags.STATUS)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

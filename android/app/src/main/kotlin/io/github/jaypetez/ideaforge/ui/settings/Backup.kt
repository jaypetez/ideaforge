package io.github.jaypetez.ideaforge.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jaypetez.ideaforge.R

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
@Composable
fun BackupSection(vm: BackupViewModel = viewModel(factory = BackupViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backup_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.backup_unencrypted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { create.launch(vm.exportFilename()) },
                enabled = !state.busy,
                modifier = Modifier.testTag(BackupTags.EXPORT),
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { open.launch(IMPORT_TYPES) },
                enabled = !state.busy,
                modifier = Modifier.testTag(BackupTags.IMPORT),
            ) { Text(stringResource(R.string.backup_import)) }
        }
        val message = if (state.busy) stringResource(R.string.backup_working) else state.message
        if (message != null) {
            Text(
                message,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .testTag(BackupTags.STATUS)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

package io.github.jaypetez.ideaforge.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.BuildConfig
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Test tags. The two empty states reuse the web's element ids. */
object LibraryTags {
    const val LIST = "library-list"
    const val DETAIL = "library-detail"
    const val ZERO = "library-zero"
    const val EMPTY = "library-empty"
    const val UNAVAILABLE = "library-unavailable"
    const val RETRY = "library-retry"
    const val SEARCH = "library-search"
    const val ARCHIVED = "library-archived"
    const val NEW = "library-new"
    const val CARD = "idea-card"
    const val NAME = "idea-name"
    const val TAGS = "idea-tags"
    fun tagChip(tag: String) = "library-tag-$tag"
    fun more(id: String) = "idea-more-$id"
}

/**
 * Ideas as home: the library list beside the selected idea's summary.
 *
 * [NavigableListDetailPaneScaffold] decides how many panes fit from the window size class.
 * On a phone it shows one pane at a time (a card opens the detail, Back returns); from the
 * expanded width class up, the list and the detail sit side by side.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    open: (LibraryItem) -> Unit,
    newIdea: () -> Unit,
    onboarding: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = navigator.currentDestination?.contentKey

    val noticeText = notice?.let { stringResource(it.text) }
    LaunchedEffect(notice) {
        if (noticeText != null) {
            // Cleared only after it has shown: clearing first would restart this effect and
            // cancel the snackbar it was about to show.
            snackbar.showSnackbar(noticeText)
            vm.noticeShown()
        }
    }

    // The delete dialog works from the stored session, so an idea hidden by the filters can
    // still be deleted from its open detail pane.
    fun itemOf(id: String?) = sessions.firstOrNull { stringOf(it["id"]) == id }?.let(LibraryViewModel::item)
    fun select(id: String) = scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
    val actions = IdeaActions(
        select = { id -> select(id) },
        open = open,
        // The web's inline "Rename or tag", in the detail pane rather than a dialog. A text
        // field in a Dialog window re-lays itself out for ever under Robolectric (the text
        // context-menu node writes its coordinates on every pass), which would leave the
        // one form that edits user data without a test.
        edit = {
            editingId = it.id
            if (selectedId != it.id) select(it.id)
        },
        archive = { vm.setArchived(it.id, !it.archived) },
        delete = { deletingId = it.id },
    )

    Scaffold(
        // The app shell's Scaffold has already applied the system-bar insets.
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            val label = stringResource(R.string.nav_new_idea)
            ExtendedFloatingActionButton(
                text = { Text(label) },
                icon = { Icon(painterResource(R.drawable.ic_new_idea), contentDescription = null) },
                onClick = newIdea,
                modifier = Modifier.testTag(LibraryTags.NEW).semantics { contentDescription = label },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            modifier = Modifier.padding(padding),
            listPane = {
                AnimatedPane(Modifier.testTag(LibraryTags.LIST)) {
                    // The scaffold moves focus into a pane when it is shown, and the first
                    // focusable here is the search field: the keyboard covered half the
                    // library on every launch (seen on the API 36 emulator). A focus target
                    // around the list takes that focus itself; a tap still reaches the field.
                    Box(Modifier.focusTarget()) {
                        LibraryList(state, selectedId, vm, actions, onboarding)
                    }
                }
            },
            detailPane = {
                AnimatedPane(Modifier.testTag(LibraryTags.DETAIL)) {
                    val session = sessions.firstOrNull { stringOf(it["id"]) == selectedId }
                    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
                    val item = session?.let(LibraryViewModel::item)
                    IdeaDetail(
                        item = item,
                        markdown = session?.let { remember(it) { buildExport(it) } },
                        actions = actions,
                        back = if (listHidden) ({ scope.launch { navigator.navigateBack() } }) else null,
                        editor = if (item != null && editingId == item.id) {
                            {
                                EditForm(
                                    item = item,
                                    dismiss = { editingId = null },
                                    save = { name, tags ->
                                        vm.saveDetails(item.id, name, tags)
                                        editingId = null
                                    },
                                )
                            }
                        } else null,
                    )
                }
            },
        )
    }

    itemOf(deletingId)?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.idea_delete_title, item.title)) },
            text = { Text(stringResource(R.string.idea_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(item.id)
                    deletingId = null
                    if (selectedId == item.id) scope.launch { navigator.navigateBack() }
                }) { Text(stringResource(R.string.idea_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private class IdeaActions(
    val select: (String) -> Unit,
    val open: (LibraryItem) -> Unit,
    val edit: (LibraryItem) -> Unit,
    val archive: (LibraryItem) -> Unit,
    val delete: (LibraryItem) -> Unit,
)

private val LibraryNotice.text: Int
    get() = when (this) {
        LibraryNotice.Saved -> R.string.notice_saved
        LibraryNotice.Archived -> R.string.notice_archived
        LibraryNotice.Restored -> R.string.notice_restored
        LibraryNotice.Deleted -> R.string.notice_deleted
        LibraryNotice.Failed -> R.string.notice_failed
    }

@Composable
private fun LibraryList(
    state: LibraryState,
    selectedId: String?,
    vm: LibraryViewModel,
    actions: IdeaActions,
    onboarding: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        // Clear of the FAB, so the last card's menu stays reachable.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.library_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.loadProblem != null) {
            item {
                Column(
                    Modifier.testTag(LibraryTags.UNAVAILABLE).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.library_unavailable), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.library_unavailable_body))
                    Text(state.loadProblem, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::retryLoad, modifier = Modifier.testTag(LibraryTags.RETRY)) {
                        Text(stringResource(R.string.keyring_retry))
                    }
                }
            }
        } else if (state.loading) {
            item { Text(stringResource(R.string.library_loading)) }
        } else if (state.noIdeasYet) {
            item {
                Column(Modifier.testTag(LibraryTags.ZERO), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onboarding) { Text(stringResource(R.string.action_how_it_works)) }
                }
            }
        } else {
            item { Filters(state, vm) }
            if (state.nothingMatches) {
                item {
                    Text(
                        stringResource(R.string.library_no_match),
                        Modifier.testTag(LibraryTags.EMPTY),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.items, key = { it.id }) { item ->
                IdeaCard(item, selected = item.id == selectedId, actions)
            }
        }
        item {
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Filters(state: LibraryState, vm: LibraryViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.filters.query,
            onValueChange = vm::setQuery,
            label = { Text(stringResource(R.string.library_search)) },
            placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().testTag(LibraryTags.SEARCH),
        )
        if (state.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tag in state.tags) {
                    FilterChip(
                        selected = state.filters.tag == tag,
                        onClick = { vm.toggleTag(tag) },
                        label = { Text(tag) },
                        modifier = Modifier.testTag(LibraryTags.tagChip(tag)),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.library_count, state.total, state.items.size, state.total),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val archivedLabel = stringResource(R.string.library_show_archived)
            Text(archivedLabel, style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = state.filters.showArchived,
                onCheckedChange = vm::setShowArchived,
                modifier = Modifier.padding(start = 8.dp).testTag(LibraryTags.ARCHIVED).semantics { contentDescription = archivedLabel },
            )
        }
    }
}

@Composable
private fun metaLine(item: LibraryItem): String {
    val questions = pluralStringResource(R.plurals.idea_questions, item.questionCount, item.questionCount)
    val updated = if (item.updatedAt > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.updatedAt))
    } else {
        stringResource(R.string.idea_unknown_date)
    }
    return stringResource(R.string.idea_meta, questions, item.coverage, updated)
}

@Composable
private fun IdeaCard(item: LibraryItem, selected: Boolean, actions: IdeaActions) {
    Card(
        Modifier
            .fillMaxWidth()
            .testTag(LibraryTags.CARD)
            .clickable(role = Role.Button) { actions.select(item.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f).padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                IdeaMenu(item, actions)
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(metaLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TagRow(item)
            }
        }
    }
}

@Composable
private fun TagRow(item: LibraryItem) {
    val labels = buildList {
        if (item.status != "active") add(stringResource(statusLabel(item.status)))
        addAll(item.tags)
    }
    if (labels.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, label) in labels.withIndex()) {
            val isStatus = i == 0 && item.status != "active"
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isStatus) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun statusLabel(status: String): Int = when (status) {
    "completed" -> R.string.idea_status_completed
    "archived" -> R.string.idea_status_archived
    else -> R.string.idea_status_active
}

@Composable
private fun IdeaMenu(item: LibraryItem, actions: IdeaActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(LibraryTags.more(item.id))) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.idea_more, item.title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.idea_edit)) },
                onClick = { expanded = false; actions.edit(item) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (item.archived) R.string.idea_restore else R.string.idea_archive)) },
                onClick = { expanded = false; actions.archive(item) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.idea_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; actions.delete(item) },
            )
        }
    }
}

@Composable
private fun IdeaDetail(
    item: LibraryItem?,
    markdown: String?,
    actions: IdeaActions,
    back: (() -> Unit)?,
    editor: (@Composable () -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (back != null) TextButton(onClick = back) { Text(stringResource(R.string.idea_all)) }
        if (item == null) {
            Text(stringResource(R.string.idea_select), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IdeaMenu(item, actions)
        }
        Text(metaLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TagRow(item)
        editor?.invoke()
        Button(onClick = { actions.open(item) }) {
            Text(stringResource(if (item.done) R.string.idea_view_result else R.string.idea_continue))
        }
        if (item.canExport && markdown != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                ExportPreview(markdown, Modifier.padding(16.dp))
            }
        } else {
            Text(stringResource(R.string.idea_no_preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EditForm(item: LibraryItem, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var tags by rememberSaveable(item.id) { mutableStateOf(item.tags.joinToString(", ")) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.idea_edit), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.idea_name)) },
                placeholder = { Text(item.title) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(LibraryTags.NAME),
            )
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(stringResource(R.string.idea_tags)) },
                placeholder = { Text(stringResource(R.string.idea_tags_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(LibraryTags.TAGS),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(name, tags) }) { Text(stringResource(R.string.idea_save_details)) }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

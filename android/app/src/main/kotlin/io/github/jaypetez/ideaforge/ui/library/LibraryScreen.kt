package io.github.jaypetez.ideaforge.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.BuildConfig
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField as OutlinedTextField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeFilterChip as FilterChip
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton as TextButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeSnackbarHost as SnackbarHost
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeShape
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.ui.theme.controls
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportController
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportDialog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
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
    const val HEADING = "library-heading"
    const val FILTERS = "library-filters"
    const val CLEAR = "library-clear-filters"
    const val EDITOR = "idea-editor"
    const val CARD = "idea-card"
    const val NAME = "idea-name"
    const val TAGS = "idea-tags"
    fun tagChip(tag: String) = "library-tag-$tag"
    fun more(id: String) = "idea-more-$id"
    fun details(id: String) = "idea-details-$id"
    fun detailMore(id: String) = "idea-detail-more-$id"
}

/**
 * Ideas as home: the library list beside the selected idea's summary.
 *
 * [NavigableListDetailPaneScaffold] decides how many panes fit from the window size class.
 * Compact rows open the interview/result directly; More exposes their secondary details.
 * Wider windows keep selection and the primary action in the existing adaptive detail pane.
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
    var restoreFocus by remember { mutableStateOf<(() -> Unit)?>(null) }
    val headingFocus = remember { FocusRequester() }
    val selectedId = navigator.currentDestination?.contentKey
    val visibleIds = state.items.map { it.id }
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    val detailHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Hidden
    ContentReportDialog(vm.reports)

    LaunchedEffect(visibleIds, listHidden, state.loading, state.loadProblem) {
        if (listHidden && selectedId != null && selectedId !in visibleIds &&
            !state.loading && state.loadProblem == null) {
            navigator.navigateBack()
        }
    }

    val noticeText = notice?.let { stringResource(it.text) }
    LaunchedEffect(notice) {
        if (noticeText != null) {
            // Cleared only after it has shown: clearing first would restart this effect and
            // cancel the snackbar it was about to show.
            snackbar.showSnackbar(noticeText)
            vm.noticeShown()
        }
    }

    fun itemOf(id: String?) = sessions.firstOrNull { stringOf(it["id"]) == id }?.let(LibraryViewModel::item)
    fun select(id: String) = scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
    fun dismissDialog() {
        editingId = null
        deletingId = null
        val restore = restoreFocus
        restoreFocus = null
        if (restore != null) restore() else headingFocus.requestFocus()
    }
    val actions = IdeaActions(
        select = { id -> select(id) },
        open = open,
        edit = { item, restore ->
            editingId = item.id
            restoreFocus = restore
        },
        archive = { vm.setArchived(it.id, !it.archived) },
        delete = { item, restore ->
            deletingId = item.id
            restoreFocus = restore
        },
        focusHeading = { headingFocus.requestFocus() },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = IdeaForgeWidths.work),
        // The app shell's Scaffold has already applied the system-bar insets.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val label = stringResource(R.string.nav_new_idea)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
                horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.library_title),
                    Modifier.weight(1f).focusRequester(headingFocus).focusTarget()
                        .testTag(LibraryTags.HEADING).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Button(
                    onClick = newIdea,
                    modifier = Modifier.testTag(LibraryTags.NEW).semantics { contentDescription = label },
                ) { Text(label) }
            }
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
                        LibraryList(state, selectedId, vm, actions, onboarding, directOpen = detailHidden)
                    }
                }
            },
            detailPane = {
                AnimatedPane(Modifier.testTag(LibraryTags.DETAIL)) {
                    val session = sessions.firstOrNull { stringOf(it["id"]) == selectedId && selectedId in visibleIds }
                    val item = session?.let(LibraryViewModel::item)
                    IdeaDetail(
                        item = item,
                        session = session,
                        reports = vm.reports,
                        actions = actions,
                        back = if (listHidden) ({ scope.launch { navigator.navigateBack() } }) else null,
                    )
                }
            },
        )
    }

    itemOf(deletingId)?.let { item ->
        AlertDialog(
            onDismissRequest = ::dismissDialog,
            title = { Text(stringResource(R.string.idea_delete_title, item.title)) },
            text = { Text(stringResource(R.string.idea_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingId = null
                    restoreFocus = null
                    headingFocus.requestFocus()
                    vm.delete(item.id)
                }) { Text(stringResource(R.string.idea_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = ::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    itemOf(editingId)?.let { item ->
        EditDialog(
            item = item,
            dismiss = ::dismissDialog,
            save = { name, tags ->
                dismissDialog()
                vm.saveDetails(item.id, name, tags)
            },
        )
    }
}

private class IdeaActions(
    val select: (String) -> Unit,
    val open: (LibraryItem) -> Unit,
    val edit: (LibraryItem, () -> Unit) -> Unit,
    val archive: (LibraryItem) -> Unit,
    val delete: (LibraryItem, () -> Unit) -> Unit,
    val focusHeading: () -> Unit,
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
    directOpen: Boolean,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = IdeaForgeSpacing.content, vertical = IdeaForgeSpacing.tight),
    ) {
        if (state.loadProblem != null) {
            item {
                Column(
                    Modifier.testTag(LibraryTags.UNAVAILABLE).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
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
                Column(Modifier.testTag(LibraryTags.ZERO), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                    Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onboarding) { Text(stringResource(R.string.action_how_it_works)) }
                }
            }
        } else {
            item { Filters(state, vm) }
            if (state.nothingMatches) {
                item {
                    Column(Modifier.testTag(LibraryTags.EMPTY), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                        Text(stringResource(R.string.library_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.filters == LibraryFilters()) {
                            TextButton(onClick = { vm.setShowArchived(true) }) { Text(stringResource(R.string.library_show_archived)) }
                        } else {
                            TextButton(onClick = vm::clearFilters, modifier = Modifier.testTag(LibraryTags.CLEAR)) {
                                Text(stringResource(R.string.library_clear_filters))
                            }
                        }
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                IdeaRow(item, selected = item.id == selectedId, actions, directOpen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item {
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(state: LibraryState, vm: LibraryViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val disclosure = stringResource(if (expanded) R.string.library_filters_expanded else R.string.library_filters_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.library_count, state.total, state.items.size, state.total),
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag(LibraryTags.FILTERS).semantics { stateDescription = disclosure },
            ) { Text(stringResource(if (expanded) R.string.library_hide_filters else R.string.library_filters)) }
        }
        if (!expanded && (state.filters.tag != null || state.filters.showArchived)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
                state.filters.tag?.let { tag -> Text(stringResource(R.string.library_tag_summary, tag), style = MaterialTheme.typography.bodyMedium) }
                if (state.filters.showArchived) Text(stringResource(R.string.idea_status_archived), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = vm::clearFilters) { Text(stringResource(R.string.library_clear_filters)) }
            }
        }
        if (expanded && state.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
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
        if (expanded) FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val archivedLabel = stringResource(R.string.library_show_archived)
                Text(archivedLabel, style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = state.filters.showArchived,
                    onCheckedChange = vm::setShowArchived,
                    modifier = Modifier.padding(start = IdeaForgeSpacing.tight).testTag(LibraryTags.ARCHIVED)
                        .semantics { contentDescription = archivedLabel },
                )
            }
            if (state.filters != LibraryFilters()) {
                TextButton(onClick = vm::clearFilters) { Text(stringResource(R.string.library_clear_filters)) }
            }
        }
    }
}

@Composable
private fun metaLine(item: LibraryItem): String {
    val questions = pluralStringResource(R.plurals.idea_questions, item.questionCount, item.questionCount)
    return stringResource(R.string.idea_meta, questions, item.coverage, updatedDate(item))
}

@Composable
private fun updatedDate(item: LibraryItem): String =
    if (item.updatedAt > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.updatedAt))
    } else {
        stringResource(R.string.idea_unknown_date)
    }

@Composable
private fun IdeaRow(item: LibraryItem, selected: Boolean, actions: IdeaActions, directOpen: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = stringResource(if (!directOpen) R.string.idea_details else if (item.done) R.string.idea_view_result else R.string.idea_continue)
    val actionLabel = stringResource(R.string.idea_open, label, item.title)
    val ink = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = IdeaForgeSpacing.control)
            .testTag(LibraryTags.CARD)
            .semantics { this.selected = selected }
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, IdeaForgeShape.control)
            .then(if (selected || focused) Modifier.border(2.dp, MaterialTheme.controls.focus, IdeaForgeShape.control) else Modifier)
            .clickable(
                interactionSource = interaction, indication = LocalIndication.current,
                role = Role.Button, onClickLabel = actionLabel,
                onClick = { if (directOpen) actions.open(item) else actions.select(item.id) },
            )
            .padding(start = IdeaForgeSpacing.tight, end = IdeaForgeSpacing.micro, top = IdeaForgeSpacing.related, bottom = IdeaForgeSpacing.related),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.idea_row_meta, stringResource(statusLabel(item.status)), item.coverage, updatedDate(item)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.tags.isNotEmpty()) {
                val remainder = item.tags.size - 2
                val tags = item.tags.take(2).joinToString(", ") +
                    if (remainder > 0) " · " + pluralStringResource(R.plurals.idea_more_tags, remainder, remainder) else ""
                Text(tags, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.controls.quiet)
        }
        IdeaMenu(item, actions)
    }
}

@Composable
private fun TagRow(item: LibraryItem) {
    val labels = buildList {
        if (item.status != "active") add(stringResource(statusLabel(item.status)))
        addAll(item.tags)
    }
    if (labels.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
        for ((i, label) in labels.withIndex()) {
            val isStatus = i == 0 && item.status != "active"
            Surface(
                shape = IdeaForgeShape.status,
                color = if (isStatus) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(label, Modifier.padding(horizontal = IdeaForgeSpacing.tight, vertical = IdeaForgeSpacing.micro), style = MaterialTheme.typography.labelMedium)
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
private fun IdeaMenu(item: LibraryItem, actions: IdeaActions, detail: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { attached = false } }
    val restore: () -> Unit = { if (attached) focus.requestFocus() else actions.focusHeading() }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.sizeIn(minWidth = IdeaForgeSpacing.control, minHeight = IdeaForgeSpacing.control)
                .focusRequester(focus).testTag(if (detail) LibraryTags.detailMore(item.id) else LibraryTags.more(item.id)),
        ) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.idea_more, item.title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!detail) DropdownMenuItem(
                text = { Text(stringResource(R.string.idea_details)) },
                onClick = { expanded = false; actions.select(item.id) },
                modifier = Modifier.testTag(LibraryTags.details(item.id)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.idea_edit)) },
                onClick = { expanded = false; actions.edit(item, restore) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (item.archived) R.string.idea_restore else R.string.idea_archive)) },
                onClick = { expanded = false; actions.archive(item) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.idea_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; actions.delete(item, restore) },
            )
        }
    }
}

@Composable
private fun IdeaDetail(
    item: LibraryItem?,
    session: Session?,
    reports: ContentReportController,
    actions: IdeaActions,
    back: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(IdeaForgeSpacing.content),
        verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
    ) {
        if (back != null) TextButton(onClick = back) { Text(stringResource(R.string.idea_all)) }
        if (item == null) {
            Text(stringResource(R.string.idea_select), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IdeaMenu(item, actions, detail = true)
        }
        Text(metaLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TagRow(item)
        Button(onClick = { actions.open(item) }) {
            Text(stringResource(if (item.done) R.string.idea_view_result else R.string.idea_continue))
        }
        if (session != null) LibraryPreview(session) { reports.open(session, it) }
    }
}

@Composable
private fun EditDialog(item: LibraryItem, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var tags by rememberSaveable(item.id) { mutableStateOf(item.tags.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.padding(horizontal = IdeaForgeSpacing.content).testTag(LibraryTags.EDITOR),
        // Material owns the width; platform-default sizing loops editable layouts in Robolectric.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.idea_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
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
            }
        },
        confirmButton = { Button(onClick = { save(name, tags) }) { Text(stringResource(R.string.idea_save_details)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

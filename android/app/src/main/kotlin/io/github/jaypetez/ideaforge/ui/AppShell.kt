package io.github.jaypetez.ideaforge.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.BuildConfig
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.ui.onboarding.OnboardingScreen
import io.github.jaypetez.ideaforge.ui.onboarding.OnboardingViewModel
import io.github.jaypetez.ideaforge.ui.onboarding.rememberStartDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private val CreationExtras.graph: AppGraph get() = (this[APPLICATION_KEY] as IdeaForgeApp).graph

class LibraryViewModel(store: SessionStore, private val ids: IdGen) : ViewModel() {
    val sessions: StateFlow<List<Session>> = store.sessions

    /** A fresh id for a new idea. Nothing is written until the interview has an answer. */
    fun newSessionId(): String = ids.newSessionId()

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(graph.sessionStore, graph.idGen) }
        }
    }
}

class SettingsViewModel(private val prefsStore: PrefsStore) : ViewModel() {
    val prefs: StateFlow<Prefs> =
        prefsStore.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Prefs())

    fun setHandsFree(on: Boolean) {
        viewModelScope.launch { prefsStore.update { it.copy(handsFree = on) } }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel(graph.prefsStore) }
        }
    }
}

private data class TopLevel(val route: Any, @StringRes val label: Int, @DrawableRes val icon: Int)

/** The app shell: bottom navigation (Ideas / New idea / Settings) over a NavHost. */
@Composable
fun AppShell(nav: NavHostController = rememberNavController()) {
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val graph = (LocalContext.current.applicationContext as IdeaForgeApp).graph
    // Null while the keyring is read: nothing renders rather than a frame of the wrong screen.
    val start = rememberStartDestination(graph.keyringStore) ?: return
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination

    // Pop to the library, not to the graph's start destination: after first-run onboarding
    // the start destination (Onboarding) is no longer on the back stack, and popping to it
    // pops nothing, so every bottom-bar tap would stack another copy of the library.
    fun goTopLevel(route: Any) = nav.navigate(route) {
        popUpTo<LibraryRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        bottomBar = {
            if (destination?.hasRoute(OnboardingRoute::class) != true) {
                NavigationBar {
                    val ideas = TopLevel(LibraryRoute, R.string.nav_ideas, R.drawable.ic_ideas)
                    val settings = TopLevel(SettingsRoute, R.string.nav_settings, R.drawable.ic_settings)
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(LibraryRoute::class) } == true,
                        onClick = { goTopLevel(ideas.route) },
                        icon = { Icon(painterResource(ideas.icon), contentDescription = null) },
                        label = { Text(stringResource(ideas.label)) },
                    )
                    NavigationBarItem(
                        selected = destination?.hasRoute(InterviewRoute::class) == true,
                        // No launchSingleTop: a new idea must be a new back-stack entry. Reusing
                        // the current Interview entry would hand the new sessionId the old
                        // entry's ViewModelStore, and its in-flight work, so one idea's
                        // results could land in another (a busy flag is not an identity guard).
                        // Pop any open interview first so the stack does not grow per tap.
                        onClick = {
                            nav.navigate(InterviewRoute(library.newSessionId())) {
                                popUpTo<InterviewRoute> { inclusive = true }
                            }
                        },
                        icon = { Icon(painterResource(R.drawable.ic_new_idea), contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_new_idea)) },
                    )
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(SettingsRoute::class) } == true,
                        onClick = { goTopLevel(settings.route) },
                        icon = { Icon(painterResource(settings.icon), contentDescription = null) },
                        label = { Text(stringResource(settings.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable<LibraryRoute> {
                LibraryScreen(
                    library,
                    open = { nav.navigate(InterviewRoute(it)) },
                    onboarding = { nav.navigate(OnboardingRoute) },
                )
            }
            composable<InterviewRoute> { backStack ->
                val route = backStack.toRoute<InterviewRoute>()
                Placeholder(stringResource(R.string.screen_interview), route.sessionId) {
                    Button(onClick = { nav.navigate(ResultRoute(route.sessionId)) }) {
                        Text(stringResource(R.string.action_see_result))
                    }
                }
            }
            composable<ResultRoute> { backStack ->
                Placeholder(stringResource(R.string.screen_result), backStack.toRoute<ResultRoute>().sessionId)
            }
            composable<SettingsRoute> {
                SettingsScreen(viewModel(factory = SettingsViewModel.Factory))
            }
            composable<OnboardingRoute> {
                OnboardingScreen(viewModel(factory = OnboardingViewModel.Factory)) {
                    // Onboarding leaves the back stack: Back from the library exits the app
                    // rather than returning to a finished setup.
                    nav.navigate(LibraryRoute) {
                        popUpTo<OnboardingRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(vm: LibraryViewModel, open: (String) -> Unit, onboarding: () -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onboarding) { Text(stringResource(R.string.action_how_it_works)) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { (it["id"] as JsonPrimitive).content }) { s ->
                    val id = (s["id"] as JsonPrimitive).content
                    Card(
                        Modifier.fillMaxWidth().clickable { open(id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        Text(sessionDisplayTitle(s), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingsScreen(vm: SettingsViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.screen_settings), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pref_hands_free), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = prefs.handsFree, onCheckedChange = vm::setHandsFree)
        }
        Text(
            stringResource(R.string.pref_trigger, prefs.effectiveTrigger),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Placeholder(title: String, detail: String, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

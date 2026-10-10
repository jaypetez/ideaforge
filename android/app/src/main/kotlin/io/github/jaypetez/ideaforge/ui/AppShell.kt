package io.github.jaypetez.ideaforge.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.interview.InterviewScreen
import io.github.jaypetez.ideaforge.ui.interview.InterviewViewModel
import io.github.jaypetez.ideaforge.ui.library.LibraryScreen
import io.github.jaypetez.ideaforge.ui.library.LibraryViewModel
import io.github.jaypetez.ideaforge.ui.onboarding.OnboardingScreen
import io.github.jaypetez.ideaforge.ui.onboarding.OnboardingViewModel
import io.github.jaypetez.ideaforge.ui.onboarding.startGate
import io.github.jaypetez.ideaforge.ui.result.ResultScreen
import io.github.jaypetez.ideaforge.ui.result.ResultViewModel
import io.github.jaypetez.ideaforge.ui.settings.SettingsScreen

/** The public guide, published beside the web app (guide/ on Pages). */
const val GUIDE_URL = "https://jaypetez.github.io/ideaforge/guide/getting-started.html"

/** Test tag on the bottom bar: its "New idea" shares a label with the library's FAB. */
const val BOTTOM_BAR = "bottom-bar"

private data class TopLevel(val route: Any, @StringRes val label: Int, @DrawableRes val icon: Int)

/** The app shell: bottom navigation (Ideas / New idea / Settings) over a NavHost. */
@Composable
fun AppShell(nav: NavHostController = rememberNavController()) {
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val graph = (LocalContext.current.applicationContext as IdeaForgeApp).graph
    // Null while the keyring is read: nothing renders rather than a frame of the wrong screen.
    val start = startGate(graph.keyringStore) ?: return
    val uri = LocalUriHandler.current
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

    // No launchSingleTop: a new idea must be a new back-stack entry. Reusing the current
    // Interview entry would hand the new sessionId the old entry's ViewModelStore, and its
    // in-flight work, so one idea's results could land in another (a busy flag is not an
    // identity guard). Pop any open interview first so the stack does not grow per tap.
    fun newIdea() = nav.navigate(InterviewRoute(library.newSessionId())) {
        popUpTo<InterviewRoute> { inclusive = true }
    }

    Scaffold(
        bottomBar = {
            if (destination?.hasRoute(OnboardingRoute::class) != true) {
                NavigationBar(Modifier.testTag(BOTTOM_BAR)) {
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
                        onClick = ::newIdea,
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
                    // The web's card button: "View result" for a finished idea, else "Continue".
                    open = { nav.navigate(if (it.done) ResultRoute(it.id) else InterviewRoute(it.id)) },
                    newIdea = ::newIdea,
                    // "How it works" opens the public guide. It used to open the onboarding
                    // placeholder; now that onboarding is real provider setup, sending a user
                    // who already has a provider through it again would answer the wrong
                    // question. Onboarding is reached only as the start destination.
                    onboarding = { runCatching { uri.openUri(GUIDE_URL) } },
                )
            }
            composable<InterviewRoute> { backStack ->
                val route = backStack.toRoute<InterviewRoute>()
                // Keyed by the idea: this entry's ViewModel belongs to one session for its life.
                val vm: InterviewViewModel = viewModel(key = route.sessionId, factory = InterviewViewModel.factory(route.sessionId))
                InterviewScreen(
                    vm,
                    onFinished = { failed ->
                        nav.navigate(ResultRoute(route.sessionId, synthesisFailed = failed)) {
                            popUpTo<InterviewRoute> { inclusive = true }
                        }
                    },
                    // The Scaffold's padding already lifts content above the bottom bar, so the
                    // composer's imePadding adds only what the keyboard covers beyond it.
                    modifier = Modifier.consumeWindowInsets(padding),
                    drive = graph.driveController,
                )
            }
            composable<ResultRoute> { backStack ->
                val route = backStack.toRoute<ResultRoute>()
                val vm: ResultViewModel = viewModel(key = route.sessionId, factory = ResultViewModel.factory(route.sessionId))
                ResultScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    // "Ask me more": the reopened idea's interview replaces the result.
                    onAskMore = {
                        nav.navigate(InterviewRoute(route.sessionId)) {
                            popUpTo<ResultRoute> { inclusive = true }
                        }
                    },
                    modifier = Modifier.consumeWindowInsets(padding),
                    synthesisFailed = route.synthesisFailed,
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onNoProvider = { nav.navigate(OnboardingRoute) { popUpTo(nav.graph.id) { inclusive = true } } })
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


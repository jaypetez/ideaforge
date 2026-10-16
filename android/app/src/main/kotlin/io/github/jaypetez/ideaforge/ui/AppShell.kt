package io.github.jaypetez.ideaforge.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
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
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.launch

/** The public guide, published beside the web app (guide/ on Pages). */
const val GUIDE_URL = "https://jaypetez.github.io/ideaforge/guide/getting-started.html"

/** The one navigation set; its "New idea" shares a label with the library's FAB. */
const val NAVIGATION = "app-navigation"
const val BOTTOM_BAR = "bottom-bar"
const val NAVIGATION_RAIL = "navigation-rail"

private data class TopLevel(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** One NavHost, with window-adaptive navigation and once-only system/cutout clearance. */
@Composable
fun AppShell(nav: NavHostController = rememberNavController()) {
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val graph = (LocalContext.current.applicationContext as IdeaForgeApp).graph
    val scope = rememberCoroutineScope()
    // Null while the keyring is read: nothing renders rather than a frame of the wrong screen.
    val start = startGate(graph.keyringStore) ?: return
    val uri = LocalUriHandler.current
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val rail = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val interviewId = if (destination?.hasRoute(InterviewRoute::class) == true) {
        entry?.toRoute<InterviewRoute>()?.sessionId
    } else null
    val voice = graph.driveController.state.collectAsStateWithLifecycle()
    // Captions/levels must not rebuild the shell: only stage ownership changes its chrome.
    val voiceOwnsScreen by remember(interviewId, voice) {
        derivedStateOf { interviewId != null && voice.value.sessionId == interviewId && voice.value.attached }
    }
    val showNavigation = destination?.hasRoute(OnboardingRoute::class) != true && !voiceOwnsScreen
    // Exclude IME: the editing screen owns the keyboard beyond this consumed clearance.
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

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
    fun newIdea() {
        val revision = graph.inferenceConsent.state.value.revision
        scope.launch {
            try {
                graph.inferenceConsent.require()
                if (revision != graph.inferenceConsent.state.value.revision) return@launch
                nav.navigate(InterviewRoute(library.newSessionId())) {
                    popUpTo<InterviewRoute> { inclusive = true }
                }
            } catch (error: ProviderException) {
                if (error.code != "consent") throw error
                goTopLevel(SettingsRoute)
            }
        }
    }

    val destinations = listOf(
        TopLevel(R.string.nav_ideas, R.drawable.ic_ideas,
            destination?.hierarchy?.any { it.hasRoute(LibraryRoute::class) || it.hasRoute(ResultRoute::class) } == true,
            { goTopLevel(LibraryRoute) }),
        TopLevel(R.string.nav_new_idea, R.drawable.ic_new_idea,
            destination?.hasRoute(InterviewRoute::class) == true, ::newIdea),
        TopLevel(R.string.nav_settings, R.drawable.ic_settings,
            destination?.hierarchy?.any { it.hasRoute(SettingsRoute::class) } == true,
            { goTopLevel(SettingsRoute) }),
    )

    Scaffold(
        contentWindowInsets = systemInsets,
        bottomBar = {
            if (showNavigation && !rail) {
                Box(Modifier.testTag(NAVIGATION)) {
                    NavigationBar(
                        Modifier.testTag(BOTTOM_BAR),
                        windowInsets = systemInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ) {
                        for (item in destinations) NavigationBarItem(
                            selected = item.selected,
                            onClick = item.onClick,
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (showNavigation && rail) {
                Box(Modifier.width(IdeaForgeWidths.rail * LocalDensity.current.fontScale.coerceAtLeast(1f))
                    .fillMaxHeight().testTag(NAVIGATION)) {
                    NavigationRail(
                        Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).testTag(NAVIGATION_RAIL),
                        windowInsets = WindowInsets(0),
                    ) {
                        for (item in destinations) NavigationRailItem(
                            selected = item.selected,
                            onClick = item.onClick,
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            NavHost(nav, startDestination = start, modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                        drive = graph.driveController,
                        onBack = { nav.popBackStack() },
                        onConsent = { goTopLevel(SettingsRoute) },
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
}

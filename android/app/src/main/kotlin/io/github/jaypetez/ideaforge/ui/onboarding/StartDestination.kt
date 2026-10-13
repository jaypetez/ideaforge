package io.github.jaypetez.ideaforge.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.ui.LibraryRoute
import io.github.jaypetez.ideaforge.ui.OnboardingRoute
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton as TextButton
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import kotlinx.coroutines.launch

/** What the keyring says about where the app should open. */
sealed interface StartDecision {
    /** Not read yet: render nothing rather than one frame of the wrong screen. */
    data object Loading : StartDecision

    /**
     * A blob exists that could not be decrypted. KeyringStore keeps it on purpose, because a
     * Keystore that failed once may answer on the next read, so this is never silently taken
     * as "no keys": the user is told, and chooses between retrying and setting up again.
     */
    data class Unreadable(val problem: String) : StartDecision

    data class Go(val route: Any) : StartDecision
}

/** Onboarding when the keyring cannot start an interview, else the library. */
fun startDecisionFor(state: KeyringState): StartDecision = when {
    state.status == KeyringState.Status.NOT_LOADED -> StartDecision.Loading
    state.status == KeyringState.Status.UNREADABLE -> StartDecision.Unreadable(state.problem.orEmpty())
    hasUsableProvider(state.ring) -> StartDecision.Go(LibraryRoute)
    else -> StartDecision.Go(OnboardingRoute)
}

/**
 * The start route, decided once and kept: null until then. A NavHost's start destination
 * must not move under it, and saving a key at the end of onboarding makes the keyring usable,
 * so re-deciding then would swap the graph's start while onboarding navigates away from it.
 *
 * While the keyring is unreadable nothing is decided; this emits the problem with a Retry
 * (another read) and a way to set up a provider anyway. Onboarding then still refuses to
 * overwrite the unreadable blob without an explicit confirmation.
 */
@Composable
fun startGate(keyring: KeyringStore): Any? {
    val state by keyring.state.collectAsStateWithLifecycle()
    // The application starts the load at launch; this covers a graph built without it.
    LaunchedEffect(keyring) {
        if (keyring.state.value.status == KeyringState.Status.NOT_LOADED) keyring.load()
    }
    var onboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var retrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    onboarding?.let { return if (it) OnboardingRoute else LibraryRoute }
    return when (val d = startDecisionFor(state)) {
        StartDecision.Loading -> null
        is StartDecision.Go -> {
            SideEffect { onboarding = d.route == OnboardingRoute }
            d.route
        }
        is StartDecision.Unreadable -> {
            KeyringProblem(
                d.problem,
                retrying = retrying,
                retry = {
                    scope.launch {
                        retrying = true
                        try { keyring.load() } finally { retrying = false }
                    }
                },
                setUp = { onboarding = true },
            )
            null
        }
    }
}

@Composable
private fun KeyringProblem(problem: String, retrying: Boolean, retry: () -> Unit, setUp: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(IdeaForgeSpacing.content).testTag("keyring-problem"),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
        ) {
            Text(stringResource(R.string.keyring_unreadable_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.keyring_unreadable_body), style = MaterialTheme.typography.bodyLarge)
            if (problem.isNotEmpty()) {
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = retry, enabled = !retrying, busy = retrying, modifier = Modifier.testTag("keyring-retry")) {
                Text(stringResource(R.string.keyring_retry))
            }
            TextButton(onClick = setUp, enabled = !retrying, busy = retrying, modifier = Modifier.testTag("keyring-setup")) {
                Text(stringResource(R.string.keyring_set_up_again))
            }
        }
    }
}

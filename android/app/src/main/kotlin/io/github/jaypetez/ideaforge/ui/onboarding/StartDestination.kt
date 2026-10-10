package io.github.jaypetez.ideaforge.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.ui.LibraryRoute
import io.github.jaypetez.ideaforge.ui.OnboardingRoute

/**
 * Where the app opens: onboarding when the keyring cannot start an interview, else the
 * library. Null until the keyring has been read, so a returning user never sees one frame of
 * onboarding while the Keystore decrypts. An unreadable keyring reads as empty, so it opens
 * onboarding: asking again beats an app that cannot start.
 */
fun startDestinationFor(state: KeyringState): Any? = when {
    state.status == KeyringState.Status.NOT_LOADED -> null
    hasUsableProvider(state.ring) -> LibraryRoute
    else -> OnboardingRoute
}

/**
 * [startDestinationFor], decided once and kept. A NavHost's start destination must not move
 * under it: saving a key at the end of onboarding makes the keyring usable, and re-deciding
 * then would swap the graph's start while onboarding is navigating away from it.
 */
@Composable
fun rememberStartDestination(keyring: KeyringStore): Any? {
    val state by keyring.state.collectAsStateWithLifecycle()
    // The application starts the load at launch; this covers a graph built without it.
    LaunchedEffect(keyring) {
        if (keyring.state.value.status == KeyringState.Status.NOT_LOADED) keyring.load()
    }
    var onboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val decided = onboarding ?: startDestinationFor(state)?.let { it == OnboardingRoute }
    if (onboarding == null && decided != null) SideEffect { onboarding = decided }
    return when (decided) {
        null -> null
        true -> OnboardingRoute
        false -> LibraryRoute
    }
}

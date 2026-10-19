package io.github.jaypetez.ideaforge.ui

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose destinations. The web app toggles panels with `hidden`
// rather than routing (WebKit re-prompts for the microphone on a hash change); on Android
// the back stack is the platform's, so these are real destinations.

@Serializable
data object LibraryRoute

@Serializable
data object NewIdeaRoute

@Serializable
data class InterviewRoute(val sessionId: String, val voiceOnEntry: Boolean = false)

/**
 * [synthesisFailed]: the interview's write-up call just failed (or returned nothing usable),
 * so the result has the transcript but no refined prompt. The interview cannot show that
 * itself, because it navigates here in the same moment, so the flag carries it over, as the
 * web shows the failure on its done panel. ResultScreen reads it.
 */
@Serializable
data class ResultRoute(val sessionId: String, val synthesisFailed: Boolean = false)

@Serializable
data object SettingsRoute

@Serializable
data object OnboardingRoute

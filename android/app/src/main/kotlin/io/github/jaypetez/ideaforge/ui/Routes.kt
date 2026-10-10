package io.github.jaypetez.ideaforge.ui

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose destinations. The web app toggles panels with `hidden`
// rather than routing (WebKit re-prompts for the microphone on a hash change); on Android
// the back stack is the platform's, so these are real destinations.

@Serializable
data object LibraryRoute

@Serializable
data class InterviewRoute(val sessionId: String)

@Serializable
data class ResultRoute(val sessionId: String)

@Serializable
data object SettingsRoute

@Serializable
data object OnboardingRoute

package io.github.jaypetez.ideaforge

import android.app.Activity
import android.content.Intent

/** Release builds open no diagnostic screen from the launch intent. See the debug source set. */
object DebugProbes {
    @Suppress("UNUSED_PARAMETER")
    fun fromLaunch(activity: Activity, intent: Intent?): Boolean = false
}

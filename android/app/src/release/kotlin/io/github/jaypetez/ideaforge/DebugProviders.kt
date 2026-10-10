package io.github.jaypetez.ideaforge

import android.content.Context
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource

/** Release builds take no provider override from the launch intent. See the debug source set. */
object DebugProviders {
    @Suppress("UNUSED_PARAMETER")
    fun fromLaunch(context: Context, name: String?): ProviderSource? = null
}

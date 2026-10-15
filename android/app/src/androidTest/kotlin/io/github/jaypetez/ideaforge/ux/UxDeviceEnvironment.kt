package io.github.jaypetez.ideaforge.ux

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Restore exact display/font overrides and any navigation overlays changed by a probe. */
internal class UxDeviceEnvironment(private val automation: UiAutomation) : Closeable {
    private val size = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
    private val density = shell("wm density").lineSequence().firstOrNull { it.startsWith("Override density:") }?.substringAfter(":")?.trim()
    private val font = shell("settings get system font_scale").trim()
    private val navigation = navigationOverlays()
    private val navigationResource = shell("cmd overlay lookup android android:integer/config_navBarInteractionMode").trim()
    private var navigationChanged = false
    private val navigationValues = mapOf("threebutton" to "0", "gestural" to "2")
    val navigationModes: List<String> get() = navigationValues.keys.filter {
        navigation.containsKey("com.android.internal.systemui.navbar.$it")
    }

    fun selectNavigationMode(mode: String) {
        require(mode in navigationModes) { "navigation mode $mode is not installed: ${navigation.keys}" }
        navigationChanged = true
        val result = shell("cmd overlay enable-exclusive --user current --category com.android.internal.systemui.navbar.$mode")
        check(shell("cmd overlay lookup android android:integer/config_navBarInteractionMode").trim() == navigationValues.getValue(mode)) {
            "navigation overlay $mode did not apply: $result"
        }
    }

    fun navigationModeApplied(mode: String): Boolean =
        shell("settings get secure navigation_mode").trim() == navigationValues.getValue(mode)

    fun configure(window: String, fontScale: Float, resize: Boolean, heightPx: Int? = null) {
        if (resize) {
            when (window) {
                "compact" -> {
                    shell("wm size 1080x2400")
                    shell("wm density 420")
                }
                "expanded" -> {
                    shell("wm size 1920x${heightPx ?: 1350}")
                    shell("wm density 240")
                }
                "medium" -> {
                    shell("wm size 1080x1440")
                    shell("wm density 240")
                }
                "compact-landscape" -> {
                    shell("wm size 960x${heightPx ?: 600}")
                    shell("wm density 240")
                }
                else -> error("unknown window $window")
            }
        }
        shell("settings put system font_scale $fontScale")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun navigationOverlays(): Map<String, Boolean> =
        shell("cmd overlay list --user current android").lineSequence().mapNotNull { line ->
            Regex("""^\[([ x])\]\s+(com\.android\.internal\.systemui\.navbar\.[A-Za-z0-9_.]+)$""")
                .matchEntire(line.trim())?.let { it.groupValues[2] to (it.groupValues[1] == "x") }
        }.toMap()

    private fun restoreNavigation() {
        if (!navigationChanged) return
        val current = navigationOverlays()
        val results = navigation.mapNotNull { (name, enabled) ->
            if (current[name] == enabled) null
            else shell("cmd overlay ${if (enabled) "enable" else "disable"} --user current $name")
        }
        check(navigationOverlays() == navigation &&
            shell("cmd overlay lookup android android:integer/config_navBarInteractionMode").trim() == navigationResource) {
            "cannot restore original navigation overlays: $results"
        }
    }

    override fun close() {
        UxEvidence.finish(null, listOf(
            { restoreNavigation() },
            { shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font") },
            { shell("wm size ${size ?: "reset"}") },
            { shell("wm density ${density ?: "reset"}") },
        )) {}
    }
}

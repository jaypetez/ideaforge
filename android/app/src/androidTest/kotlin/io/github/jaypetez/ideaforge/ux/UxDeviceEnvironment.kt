package io.github.jaypetez.ideaforge.ux

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Only display/font settings are changed; restore the exact overrides, including absence. */
internal class UxDeviceEnvironment(private val automation: UiAutomation) : Closeable {
    private val size = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
    private val density = shell("wm density").lineSequence().firstOrNull { it.startsWith("Override density:") }?.substringAfter(":")?.trim()
    private val font = shell("settings get system font_scale").trim()

    fun configure(window: String, fontScale: Float, resize: Boolean) {
        if (resize) {
            when (window) {
                "compact" -> {
                    shell("wm size 1080x2400")
                    shell("wm density 420")
                }
                "expanded" -> {
                    shell("wm size 1920x1350")
                    shell("wm density 240")
                }
                else -> error("unknown window $window")
            }
        }
        shell("settings put system font_scale $fontScale")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    override fun close() {
        UxEvidence.finish(null, listOf(
            { shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font") },
            { shell("wm size ${size ?: "reset"}") },
            { shell("wm density ${density ?: "reset"}") },
        )) {}
    }
}

package io.github.jaypetez.ideaforge

import android.app.Activity
import android.content.Intent
import io.github.jaypetez.ideaforge.probe.RecognizerProbeActivity

/**
 * Debug-only diagnostic screens, chosen at launch:
 *
 *     adb shell am start -n io.github.jaypetez.ideaforge.debug/io.github.jaypetez.ideaforge.MainActivity \
 *         --es probe recognizer
 *
 * `recognizer` opens the SpeechRecognizer probe (issue #89), forwarding the launch extras so
 * its toggles can be pre-filled. This is the probe's only route: its activity is not
 * exported, and it starts nothing on its own unless the shell allowed autostart. The release source set's DebugProbes does nothing.
 */
object DebugProbes {
    fun fromLaunch(activity: Activity, intent: Intent?): Boolean = when (intent?.getStringExtra("probe")) {
        "recognizer" -> {
            activity.startActivity(Intent(activity, RecognizerProbeActivity::class.java).putExtras(intent))
            true
        }
        else -> false
    }
}

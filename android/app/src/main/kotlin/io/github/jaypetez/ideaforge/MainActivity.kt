package io.github.jaypetez.ideaforge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jaypetez.ideaforge.ui.AppShell
import io.github.jaypetez.ideaforge.ui.theme.PrefsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: the Scaffold's insets keep content clear of the system bars.
        // Predictive back is enabled in the manifest (enableOnBackInvokedCallback).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds only: `--es provider scripted` replays the walkthrough with no key.
        // The release DebugProviders always answers null.
        DebugProviders.fromLaunch(this, intent?.getStringExtra("provider"))?.let {
            (application as IdeaForgeApp).graph.providerSource = it
        }
        // Debug builds only: `--es probe recognizer` opens the SpeechRecognizer probe (#89)
        // over the app. The release DebugProbes opens nothing.
        if (savedInstanceState == null) DebugProbes.fromLaunch(this, intent)
        val prefs = (application as IdeaForgeApp).graph.prefsStore.prefs
        setContent {
            // The stored theme choice (Settings, Appearance). The system bars follow it too:
            // enableEdgeToEdge alone picks their icon colour from the system's mode, which
            // leaves dark icons on a dark app when the phone is light.
            PrefsTheme(prefs, onDark = { dark ->
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }) { AppShell() }
        }
    }
}

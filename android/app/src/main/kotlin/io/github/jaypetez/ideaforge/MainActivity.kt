package io.github.jaypetez.ideaforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jaypetez.ideaforge.ui.AppShell
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme

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
        setContent { IdeaForgeTheme { AppShell() } }
    }
}

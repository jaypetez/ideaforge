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
        setContent { IdeaForgeTheme { AppShell() } }
    }
}

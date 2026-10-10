package io.github.jaypetez.ideaforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IdeaForgeApp() }
    }
}

// Placeholder palette taken from the web tokens in src/ui/app.css (--accent, --ground). The
// full Material 3 theme, with dynamic colour, arrives with the app shell (#81).
private val Light = lightColorScheme(primary = Color(0xFF24665E), background = Color(0xFFF5F6F2))
private val Dark = darkColorScheme(primary = Color(0xFF9ECDC3), background = Color(0xFF15191A))

@Composable
fun IdeaForgeApp() {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge)
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

package io.github.jaypetez.ideaforge.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton
import io.github.jaypetez.ideaforge.ui.components.StatusTone

const val PRIVACY_POLICY_URL = "https://jaypetez.github.io/ideaforge/guide/privacy-policy.html"

@Composable
fun PrivacyPolicyLink() {
    val uri = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    Column {
        IdeaForgeTextButton(onClick = {
            try {
                uri.openUri(PRIVACY_POLICY_URL)
                failed = false
            } catch (_: ActivityNotFoundException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            } catch (_: SecurityException) {
                failed = true
            }
        }) { Text(stringResource(R.string.privacy_policy)) }
        if (failed) IdeaForgeStatusText(
            stringResource(R.string.privacy_policy_open_failed, PRIVACY_POLICY_URL),
            tone = StatusTone.Error,
        )
    }
}

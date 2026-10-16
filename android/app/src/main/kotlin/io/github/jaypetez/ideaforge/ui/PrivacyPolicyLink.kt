package io.github.jaypetez.ideaforge.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val feedback = remember { BringIntoViewRequester() }
    var failedOpens by remember { mutableIntStateOf(0) }
    LaunchedEffect(failedOpens) {
        if (failedOpens > 0) feedback.bringIntoView()
    }
    Column {
        IdeaForgeTextButton(onClick = {
            try {
                uri.openUri(PRIVACY_POLICY_URL)
                failedOpens = 0
            } catch (_: ActivityNotFoundException) {
                failedOpens++
            } catch (_: IllegalArgumentException) {
                failedOpens++
            } catch (_: SecurityException) {
                failedOpens++
            }
        }) { Text(stringResource(R.string.privacy_policy)) }
        if (failedOpens > 0) IdeaForgeStatusText(
            stringResource(R.string.privacy_policy_open_failed, PRIVACY_POLICY_URL),
            modifier = Modifier.bringIntoViewRequester(feedback),
            tone = StatusTone.Error,
        )
    }
}

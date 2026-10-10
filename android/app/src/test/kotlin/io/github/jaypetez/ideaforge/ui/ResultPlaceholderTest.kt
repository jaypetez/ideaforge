package io.github.jaypetez.ideaforge.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Result stand-in shows the write-up failure the interview carried over (until #86). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultPlaceholderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFailedWriteUpIsSaidOnTheResult() {
        compose.setContent { IdeaForgeTheme { ResultPlaceholder(ResultRoute("s_x", synthesisFailed = true)) } }
        compose.onNodeWithTag("synthesis-failed").assertTextContains("could not be generated", substring = true)
    }

    @Test
    fun aGoodWriteUpSaysNothingOfTheSort() {
        compose.setContent { IdeaForgeTheme { ResultPlaceholder(ResultRoute("s_x")) } }
        compose.onNodeWithTag("synthesis-failed").assertDoesNotExist()
    }
}

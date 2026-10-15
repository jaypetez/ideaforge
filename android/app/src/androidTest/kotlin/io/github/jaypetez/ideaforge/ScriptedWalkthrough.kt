package io.github.jaypetez.ideaforge

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.ux.UxBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * One real-navigation walkthrough shared by the baseline test and opt-in evidence run.
 * Captures observe it; they never replace navigation, answers or degraded-run assertions.
 */
@OptIn(ExperimentalTestApi::class)
internal fun scriptedWalkthrough(
    compose: ComposeTestRule,
    target: Context,
    launch: () -> Unit,
    capture: (String) -> Unit = {},
    settleInput: () -> Unit = {},
) {
    val vector = Json.parseToJsonElement(target.assets.open(ScriptedProvider.ASSET).bufferedReader().use { it.readText() })
    val case = (vector["cases"] as JsonArray).single { stringOf(it["name"]) == "a chip answer" }
    val script = ScriptedProvider.loadScript(target)
    val steps = case["input"]["steps"] as JsonArray
    assertTrue("the case answers with a chip", steps.any { it["chip"] != null })

    fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    fun click(tag: String) {
        val interaction = compose.onNodeWithTag(tag)
        val node = interaction.fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val top = coordinates.localToWindow(Offset.Zero)
        val raw = UxBounds(top.x, top.y, top.x + coordinates.size.width, top.y + coordinates.size.height)
        val clipped = node.boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        if (!clipped.contains(raw)) interaction.performScrollTo()
        interaction.performClick()
    }

    launch()
    compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), DEVICE_TIMEOUT)
    compose.onNode(hasText(target.getString(R.string.nav_new_idea)) and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()

    var turn = 1
    for (step in steps) {
        val expected = if (turn == 1) SEED_QUESTION else stringOf(script["turns"][turn.toString()]["question"])
        compose.waitUntil(DEVICE_TIMEOUT) { !exists("interview-busy") && exists("question") }
        assertEquals("turn $turn is the scripted question, not the checklist", expected, text("question"))
        compose.onNodeWithTag("degraded").assertDoesNotExist()
        if (turn == 1) capture("seed")
        when {
            truthy(step["skip"]) -> click("skip")
            step["chip"] != null -> {
                val chipTag = "chip-${toNumber(step["chip"]).toInt()}"
                val chipText = text(chipTag)
                click(chipTag)
                settleInput()
                assertEquals("turn $turn chip was actually tapped and filled the real draft", chipText,
                    compose.onNodeWithTag("answer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
                capture("chip")
                click("send")
            }
            else -> {
                compose.onNodeWithTag("answer").performTextInput(stringOf(step["text"])!!)
                settleInput()
                click("send")
            }
        }
        turn++
        compose.waitUntil(DEVICE_TIMEOUT) {
            exists("wrap-offer") || (!exists("interview-busy") && exists("question") && text("question") != expected)
        }
        if (exists("wrap-offer")) break
    }
    assertTrue("the run reached past the first few turns ($turn)", turn > 4)
    click(if (exists("wrap-offer")) "wrap-accept" else "write-up")

    compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.SCREEN), DEVICE_TIMEOUT)
    compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.OUTPUT), DEVICE_TIMEOUT)
    assertEquals(stringOf(script["synthesis"]["title"])!!, text(ResultTags.TITLE))
    compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
    val export = stringOf(case["expect"]["value"]["export"])!!
    val headings = export.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
    assertTrue("the export has ## headings", headings.isNotEmpty())
    val output = hasAnyAncestor(hasTestTag(ResultTags.OUTPUT))
    for (heading in headings) {
        compose.onNode(hasText(heading) and output).assertExists("the result shows the \"## $heading\" section")
    }
    capture("result")
}

// The existing walkthrough's budget: software-rendered CI is slower than a local emulator.
internal const val DEVICE_TIMEOUT = 30_000L

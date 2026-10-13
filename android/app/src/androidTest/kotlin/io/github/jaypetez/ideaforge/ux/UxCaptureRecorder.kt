package io.github.jaypetez.ideaforge.ux

import android.app.Activity
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import io.github.jaypetez.ideaforge.DEVICE_TIMEOUT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal data class UxConfiguration(val window: String, val theme: String, val fontScale: Float) {
    val id get() = "$window-$theme-f${fontScale.toString().replace('.', '_')}"
}

internal data class UxControl(val name: String, val matcher: SemanticsMatcher, val actionRequired: Boolean = true)

/**
 * Actual device PNGs plus incremental manifests. A failed assertion keeps its screenshot
 * and reason; other requested states still run, so a red matrix remains useful evidence.
 */
internal class UxCaptureRecorder(
    private val compose: ComposeTestRule,
    private val automation: UiAutomation,
    private val request: UxCaptureRequest,
    private val output: File,
    private val expected: Set<String>,
) {
    private val records = LinkedHashMap<String, JsonObject>()
    private val json = Json { prettyPrint = true }
    val failures = mutableListOf<String>()
    val recordedKeys: Set<String> get() = records.keys.toSet()

    init {
        check(output.isDirectory || output.mkdirs()) { "cannot create UX test artifact directory: $output" }
        writeManifest("running")
    }

    fun capture(
        state: UxState,
        configuration: UxConfiguration,
        activity: Activity,
        controls: List<UxControl>,
        nativeSurface: String = state.surface,
        nativeMapping: String? = null,
        labNavigationMs: Double? = null,
    ) {
        val key = "${configuration.id}/${state.id}"
        val relative = "${configuration.id}/${state.evidence}/${state.id}.png"
        val geometry = mutableListOf<JsonElement>()
        var failure: String? = null
        var pixels: UxPixels? = null
        val display = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val view = activity.window.decorView
        val insets = view.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        val safe = UxBounds(
            (insets?.left ?: 0).toFloat(), (insets?.top ?: 0).toFloat(),
            (view.width - (insets?.right ?: 0)).toFloat(), (view.height - (insets?.bottom ?: 0)).toFloat(),
        )
        try {
            check(kotlin.math.abs(config.fontScale - configuration.fontScale) < 0.01f) {
                "requested fontScale ${configuration.fontScale}, actual ${config.fontScale}"
            }
            check(if (configuration.window == "compact") config.screenWidthDp < 600 else config.screenWidthDp >= 840) {
                "requested ${configuration.window} window, actual ${config.screenWidthDp}dp"
            }
            pixels = saveImage(relative, safe)
            UxEvidence.requireTheme(configuration.theme, pixels)
            var currentImage = relative
            for (control in controls) {
                var revealedFromLazyList = false
                if (compose.onAllNodes(control.matcher).fetchSemanticsNodes().isEmpty()) {
                    // A lazy list disposes off-screen controls. Reveal the real item before
                    // fetching its geometry; a missing item still fails the normal checks.
                    val lists = compose.onAllNodes(hasScrollToIndexAction())
                    for (index in lists.fetchSemanticsNodes().indices) {
                        runCatching { lists[index].performScrollToNode(control.matcher) }
                        if (compose.onAllNodes(control.matcher).fetchSemanticsNodes().isNotEmpty()) {
                            revealedFromLazyList = true
                            break
                        }
                    }
                }
                val node = compose.onNode(control.matcher)
                var raw = rawBounds(node.fetchSemanticsNode())
                var clipped = clippedBounds(node.fetchSemanticsNode())
                var reachedByScroll = revealedFromLazyList
                if (!safe.intersect(clipped).contains(raw)) {
                    // An offscreen action is valid when a real scroll can reveal it.
                    // Unscrollable clipped controls still fail after the attempt.
                    runCatching { node.performScrollTo() }
                    compose.waitForIdle()
                    reachedByScroll = true
                    raw = rawBounds(node.fetchSemanticsNode())
                    clipped = clippedBounds(node.fetchSemanticsNode())
                }
                val controlPath = if (reachedByScroll) {
                    "${configuration.id}/${state.evidence}/${state.id}-reach-${control.name}.png"
                } else currentImage
                if (reachedByScroll) {
                    saveImage(controlPath, safe)
                    currentImage = controlPath
                }
                val semantics = node.fetchSemanticsNode()
                val label = semanticLabel(semantics)
                val actionable = semantics.config.contains(SemanticsActions.OnClick) ||
                    semantics.config.contains(SemanticsActions.SetText) || semantics.config.contains(SemanticsActions.SetProgress)
                val checks = runCatching {
                    UxEvidence.requireControl(control.name, raw, safe.intersect(clipped), label, actionable || !control.actionRequired)
                    for (child in descendants(semantics)) {
                        val layout = child.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
                        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        compose.runOnUiThread { layout.action?.invoke(results) }
                        for (result in results) {
                            // MultiParagraph.width retains the original max constraint even
                            // when Text wraps to its intrinsic width. It is not painted ink.
                            val lines = (0 until result.lineCount).toList()
                            UxEvidence.requireLabel(
                                control.name,
                                lines.maxOfOrNull { result.getLineRight(it) - result.getLineLeft(it) } ?: 0f,
                                lines.maxOfOrNull(result::getLineBottom) ?: 0f,
                                result.size.width, result.size.height,
                                result.multiParagraph.didExceedMaxLines || lines.any(result::isLineEllipsized),
                            )
                        }
                    }
                }
                geometry += buildJsonObject {
                    put("control", control.name)
                    put("boundsPx", bounds(raw))
                    put("clippedBoundsPx", bounds(clipped))
                    put("semanticLabel", label)
                    put("actionable", actionable)
                    put("actionRequired", control.actionRequired)
                    put("enabled", !semantics.config.contains(SemanticsProperties.Disabled))
                    put("reachedByScroll", reachedByScroll)
                    put("artifact", controlPath)
                    put("passed", checks.isSuccess)
                    checks.exceptionOrNull()?.let { put("failure", it.message.orEmpty()) }
                }
                checks.exceptionOrNull()?.let {
                    failure = "${it.javaClass.simpleName}: ${it.message}"
                    failures += "$key/${control.name}: $failure"
                }
            }
        } catch (e: Throwable) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
            failures += "$key: $failure"
        }
        val semanticsPath = "${configuration.id}/${state.evidence}/${state.id}-semantics.txt"
        if (failure != null) {
            runCatching {
                File(output, semanticsPath).writeText(compose.onRoot(useUnmergedTree = true).printToString())
            }
        }
        records[key] = buildJsonObject {
            put("state", state.id)
            put("sharedSurface", state.surface)
            put("nativeSurface", nativeSurface)
            put("evidence", state.evidence)
            put("testedSha", request.sha)
            put("sourceState", request.sourceState)
            put("theme", configuration.theme)
            put("colourSource", if (Build.VERSION.SDK_INT >= 31) "dynamic-wallpaper" else "fallback")
            put("fallback", Build.VERSION.SDK_INT < 31)
            put("fontScale", config.fontScale)
            put("imeVisible", view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
            put("imeBottomPx", view.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0)
            put("requestedFontScale", configuration.fontScale)
            put("window", configuration.window)
            put("configurationSource", "instrumentation wm size/density and system font_scale overrides")
            put("apiLevel", Build.VERSION.SDK_INT)
            put("deviceModel", Build.MODEL)
            put("viewport", buildJsonObject {
                put("widthPx", view.width)
                put("heightPx", view.height)
                put("widthDp", config.screenWidthDp)
                put("heightDp", config.screenHeightDp)
                put("density", display.density)
                put("densityDpi", display.densityDpi)
                put("displayWidthDp", view.width / display.density)
                put("displayHeightDp", view.height / display.density)
                put("safeBoundsPx", bounds(safe))
            })
            put("geometry", JsonArray(geometry))
            pixels?.let {
                put("pixels", buildJsonObject {
                    put("sampledColours", it.colours)
                    put("meanLuma", it.meanLuma)
                    put("dominantRgb", it.dominantRgb)
                    put("bitmapWidthPx", it.width)
                    put("bitmapHeightPx", it.height)
                })
            }
            put("artifact", relative)
            put("passed", failure == null)
            state.phase?.let { put("sharedPhase", it) }
            for (status in listOf("voice-phase", "busy-line")) {
                val value = compose.onAllNodes(hasTestTag(status)).fetchSemanticsNodes().firstOrNull()
                    ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                if (value != null) put("nativeStatusText", value)
            }
            nativeMapping?.let { put("nativeMapping", it) }
            labNavigationMs?.let { put("labNavigationMs", it) }
            failure?.let { put("failure", it) }
            if (failure != null) put("semanticsArtifact", semanticsPath)
        }
        writeManifest("running")
    }

    fun failed(state: UxState, configuration: UxConfiguration, error: Throwable, activity: Activity? = null) {
        val key = "${configuration.id}/${state.id}"
        val message = "${error.javaClass.simpleName}: ${error.message}"
        if (key !in records && activity != null) {
            runCatching {
                capture(
                    state, configuration, activity, emptyList(), nativeSurface = "unverified-current-ui",
                    nativeMapping = "Requested state was not reached. This is a diagnostic screenshot of the current UI, not passing state evidence.",
                )
            }
        }
        failures += "$key: $message"
        // If capture already recorded this state, keep its pixels and measurements.
        records[key] = records[key]?.let { JsonObject(it + mapOf(
            "passed" to JsonPrimitive(false), "failure" to JsonPrimitive(message), "verified" to JsonPrimitive(false),
        )) }
            ?: buildJsonObject {
                put("state", state.id)
                put("evidence", state.evidence)
                put("testedSha", request.sha)
                put("window", configuration.window)
                put("theme", configuration.theme)
                put("requestedFontScale", configuration.fontScale)
                put("passed", false)
                put("failure", message)
            }
        writeManifest("running")
    }

    fun failedRun(error: Throwable) {
        for (failure in listOf(error) + error.suppressed) {
            failures += "run: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    fun finish() {
        writeManifest(if (failures.isEmpty() && expected == records.keys) "passed" else "failed")
        UxEvidence.requireComplete(expected, records.keys, failures)
    }

    private fun saveImage(relative: String, safe: UxBounds): UxPixels {
        compose.waitForIdle()
        automation.waitForIdle(300, DEVICE_TIMEOUT)
        val bitmap = checkNotNull(automation.takeScreenshot()) { "device returned no screenshot" }
        try {
            val file = File(output, relative)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "cannot create ${file.parent}" }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "could not encode $relative" } }
            return UxEvidence.requireRendered(bitmap, safe)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeManifest(status: String) {
        val manifest = buildJsonObject {
            put("schema", 1)
            put("platform", "android-emulator-or-device")
            put("testedSha", request.sha)
            put("mode", request.mode)
            put("sourceState", request.sourceState)
            put("status", status)
            put("expected", JsonArray(expected.sorted().map(::JsonPrimitive)))
            put("captures", JsonArray(records.values.toList()))
            put("failures", JsonArray(failures.map(::JsonPrimitive)))
            put("limitations", JsonArray(listOf(
                "Scripted/injected effects; no real provider, microphone recording or hosted speech.",
                "Isolated components are not evidence of a real voice run or permissions.",
                "Emulator laboratory evidence, not physical-phone compatibility or usability.",
                "labNavigationMs is a single local instrumentation observation, not INP or field Web Vitals.",
            ).map(::JsonPrimitive)))
        }
        File(output, "manifest-${request.window}.json").writeText(json.encodeToString(JsonObject.serializer(), manifest) + "\n")
    }

    private fun rawBounds(node: SemanticsNode): UxBounds {
        val coordinates = node.layoutInfo.coordinates
        val position = coordinates.localToWindow(Offset.Zero)
        return UxBounds(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }

    private fun clippedBounds(node: SemanticsNode): UxBounds = node.boundsInWindow.let {
        UxBounds(it.left, it.top, it.right, it.bottom)
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }

    private fun semanticLabel(node: SemanticsNode): String = descendants(node).flatMap { n ->
        val text = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val descriptions = n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        (text + descriptions).asSequence()
    }.distinct().joinToString(" ")

    private fun bounds(value: UxBounds) = buildJsonObject {
        put("left", value.left); put("top", value.top); put("right", value.right); put("bottom", value.bottom)
    }
}

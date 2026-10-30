package io.github.jaypetez.ideaforge.ux

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import androidx.core.view.WindowCompat
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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

internal data class UxConfiguration(
    val window: String, val theme: String, val fontScale: Float, val palette: String? = null,
    val requestedWidthDp: Int? = null, val requestedHeightDp: Int? = null,
    val navigationMode: String? = null,
) {
    val id get() = "$window-$theme-f${fontScale.toString().replace('.', '_')}" +
        navigationMode?.let { "-nav-$it" }.orEmpty()
}

internal data class UxControl(
    val name: String, val matcher: SemanticsMatcher, val actionRequired: Boolean = true,
    val minHeightDp: Float = if (name in setOf("new-idea-voice", "dictation-toggle", "voice-start", "voice-pause", "voice-resume", "voice-exit",
            "foundation-voice-pause", "foundation-voice-exit")) 64f else 48f,
)

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
        documentViewport: UxBounds? = null,
        rootSurfaceRole: String = "background",
    ) {
        val key = "${configuration.id}/${state.id}"
        val relative = "${configuration.id}/${state.evidence}/${state.id}.png"
        val geometry = mutableListOf<JsonElement>()
        var failure: String? = null
        var pixels: UxPixels? = null
        val rootAttempts = mutableListOf<JsonElement>()
        val display = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val view = activity.window.decorView
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val insets = view.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        val safe = UxBounds(
            (origin[0] + (insets?.left ?: 0)).toFloat(), (origin[1] + (insets?.top ?: 0)).toFloat(),
            (origin[0] + view.width - (insets?.right ?: 0)).toFloat(),
            (origin[1] + view.height - (insets?.bottom ?: 0)).toFloat(),
        )
        try {
            check(kotlin.math.abs(config.fontScale - configuration.fontScale) < 0.01f) {
                "requested fontScale ${configuration.fontScale}, actual ${config.fontScale}"
            }
            if (configuration.requestedWidthDp != null && configuration.requestedHeightDp != null) {
                check(kotlin.math.abs(view.width / display.density - configuration.requestedWidthDp) < 1f &&
                    kotlin.math.abs(view.height / display.density - configuration.requestedHeightDp) < 1f) {
                    "requested ${configuration.requestedWidthDp}x${configuration.requestedHeightDp}dp, actual ${view.width / display.density}x${view.height / display.density}dp"
                }
            } else {
                check(if (configuration.window == "compact") config.screenWidthDp < 600 else config.screenWidthDp >= 840) {
                    "requested ${configuration.window} window, actual ${config.screenWidthDp}dp"
                }
            }
            pixels = saveImage(relative, safe, configuration, activity, rootAttempts, rootSurfaceRole)
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
                var touch = UxEvidence.touchBounds(node.fetchSemanticsNode())
                var reachedByScroll = revealedFromLazyList
                if (!safe.intersect(clipped).contains(raw) || control.actionRequired && !safe.contains(touch)) {
                    // An offscreen action is valid when a real scroll can reveal it.
                    // Unscrollable clipped controls still fail after the attempt.
                    runCatching { node.performScrollTo() }
                    compose.waitForIdle()
                    reachedByScroll = true
                    raw = rawBounds(node.fetchSemanticsNode())
                    clipped = clippedBounds(node.fetchSemanticsNode())
                    touch = UxEvidence.touchBounds(node.fetchSemanticsNode())
                }
                val controlPath = if (reachedByScroll) {
                    "${configuration.id}/${state.evidence}/${state.id}-reach-${control.name}.png"
                } else currentImage
                if (reachedByScroll) {
                    saveImage(controlPath, safe, configuration, activity, rootSurfaceRole = rootSurfaceRole)
                    currentImage = controlPath
                }
                val semantics = node.fetchSemanticsNode()
                val label = semanticLabel(semantics)
                val actionable = semantics.config.contains(SemanticsActions.OnClick) ||
                    semantics.config.contains(SemanticsActions.SetText) || semantics.config.contains(SemanticsActions.SetProgress)
                val checks = runCatching {
                    // A disabled native editor may omit SetText; its label and target still have to fit.
                    UxEvidence.requireControl(control.name, raw, safe.intersect(clipped), label,
                        actionable || semantics.config.contains(SemanticsProperties.Disabled) || !control.actionRequired)
                    if (control.actionRequired) {
                        check(safe.contains(touch)) { "required control ${control.name} has a clipped touch target: $touch outside $safe" }
                        UxEvidence.requireTarget(control.name, touch.width / display.density, touch.height / display.density, control.minHeightDp)
                    }
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
                    put("touchBoundsPx", bounds(touch))
                    put("clippedTouchBoundsPx", bounds(safe.intersect(touch)))
                    put("targetMeasurement", "Compose native touchBoundsInRoot transformed to screen, including popup/dialog window offset; distinct from paint/layout bounds")
                    put("semanticLabel", label)
                    put("actionable", actionable)
                    put("actionRequired", control.actionRequired)
                    if (control.actionRequired) put("minimumTargetDp", "48x${control.minHeightDp}")
                    put("enabled", !semantics.config.contains(SemanticsProperties.Disabled))
                    semantics.config.getOrNull(SemanticsProperties.Role)?.let { put("role", it.toString()) }
                    put("heading", semantics.config.contains(SemanticsProperties.Heading))
                    semantics.config.getOrNull(SemanticsProperties.Selected)?.let { put("selected", it) }
                    semantics.config.getOrNull(SemanticsProperties.StateDescription)?.let { put("stateDescription", it) }
                    semantics.config.getOrNull(SemanticsProperties.LiveRegion)?.let { put("liveRegion", it.toString()) }
                    semantics.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { put("progress", it.toString()) }
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
        runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val text = roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() }
            check(text.isNotBlank()) { "no semantics tree for $key" }
            File(output, semanticsPath).apply { checkNotNull(parentFile).mkdirs() }.writeText(text)
        }.exceptionOrNull()?.let {
            failure = "cannot retain semantics for $key: ${it.message}"
            failures += checkNotNull(failure)
        }
        records[key] = buildJsonObject {
            put("state", state.id)
            put("sharedSurface", state.surface)
            put("nativeSurface", nativeSurface)
            put("evidence", state.evidence)
            put("testedSha", request.sha)
            put("sourceState", request.sourceState)
            put("theme", configuration.theme)
            put("pixelSurfaceRole", rootSurfaceRole)
            val palette = configuration.palette?.let(UxFoundationPalette::from)
            put("colourSource", palette?.colourSource ?: if (Build.VERSION.SDK_INT >= 31) "dynamic-wallpaper" else "fallback")
            put("fallback", if (palette != null) palette == UxFoundationPalette.FocusStudio else Build.VERSION.SDK_INT < 31)
            put("simulatedPalette", palette != null && palette != UxFoundationPalette.FocusStudio)
            palette?.let { put("paletteId", it.id) }
            put("fontScale", config.fontScale)
            put("imeVisible", view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
            put("imeBottomPx", view.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0)
            put("requestedFontScale", configuration.fontScale)
            put("window", configuration.window)
            configuration.navigationMode?.let { put("systemNavigationMode", it) }
            configuration.requestedWidthDp?.let { put("requestedWidthDp", it) }
            configuration.requestedHeightDp?.let { put("requestedHeightDp", it) }
            documentViewport?.let { put("documentViewportPx", bounds(it)) }
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
                put("windowLeftPx", origin[0])
                put("windowTopPx", origin[1])
                put("safeBoundsPx", bounds(safe))
            })
            put("geometry", JsonArray(geometry))
            put("rootPixelAttempts", JsonArray(rootAttempts))
            pixels?.let {
                put("pixels", buildJsonObject {
                    put("sampledColours", it.colours)
                    put("meanLuma", it.meanLuma)
                    put("dominantRgb", it.dominantRgb)
                    put("bitmapWidthPx", it.width)
                    put("bitmapHeightPx", it.height)
                    it.rootSurfaceRgb?.let { rgb -> put("expectedRootSurfaceRgb", "#%06X".format(rgb and 0x00FFFFFF)) }
                    put("rootSurfaceMatches", it.rootSurfaceMatches)
                    put("sampledPixels", it.sampledPixels)
                    it.statusBar?.let { bar -> put("statusBar", statusBarJson(bar)) }
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
            put("semanticsArtifact", semanticsPath)
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

    private fun saveImage(
        relative: String, safe: UxBounds, configuration: UxConfiguration, activity: Activity,
        attempts: MutableList<JsonElement>? = null,
        rootSurfaceRole: String = "background",
    ): UxPixels {
        val deadline = SystemClock.uptimeMillis() + DEVICE_TIMEOUT
        var attempt = 0
        var latestFile: File? = null
        fun sample(): UxPixels {
            compose.waitForIdle()
            val remaining = deadline - SystemClock.uptimeMillis()
            check(remaining > 0) { "root capture budget expired before screenshot" }
            automation.waitForIdle(minOf(300L, remaining), remaining)
            // Resolve the requested factory role after composition/configuration settles.
            // A context is observed; no self-reported rendered colour becomes the oracle.
            val context = if (activity is UxFixtureActivity) checkNotNull(activity.rootRenderingContext) {
                "isolated fixture has no committed root rendering context"
            } else activity
            val expected = expectedRootSurface(configuration, context, rootSurfaceRole)
            val view = activity.window.decorView
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val statusBounds = if (activity is UxFixtureActivity) {
                val top = checkNotNull(view.rootWindowInsets).getInsets(WindowInsets.Type.statusBars()).top
                UxBounds(origin[0].toFloat(), origin[1].toFloat(), (origin[0] + view.width).toFloat(), (origin[1] + top).toFloat())
            } else null
            val lightAppearance = WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars
            val bitmap = checkNotNull(automation.takeScreenshot()) { "device returned no screenshot" }
            val attemptPath = if (attempts == null) relative else relative.removeSuffix(".png") + "-root-attempt-${++attempt}.png"
            var pixelSource = "UiAutomation display"
            try {
                val file = File(output, attemptPath)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "cannot create ${file.parent}" }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "could not encode $attemptPath" } }
                latestFile = file
                val pixels = UxEvidence.requireRendered(bitmap, safe, expected).copy(
                    statusBar = statusBounds?.let {
                        UxEvidence.statusBarPixels(bitmap, it, expected, darkInk = configuration.theme == "light")
                    },
                )
                val windowPixels = if (attempts != null) {
                    pixelSource = "Compose window PixelCopy"
                    val roots = compose.onAllNodes(isRoot())
                    val nodes = roots.fetchSemanticsNodes()
                    val main = nodes.indices.maxBy { nodes[it].boundsInWindow.width * nodes[it].boundsInWindow.height }
                    val windowBitmap = roots[main].captureToImage().asAndroidBitmap()
                    try {
                        UxEvidence.requireRendered(windowBitmap,
                            UxBounds(0f, 0f, windowBitmap.width.toFloat(), windowBitmap.height.toFloat()), expected)
                    } finally {
                        windowBitmap.recycle()
                    }
                } else null
                attempts?.add(buildJsonObject {
                    put("attempt", attempt)
                    put("artifact", attemptPath)
                    put("expectedRootSurfaceRgb", "#%06X".format(expected and 0x00FFFFFF))
                    put("activityRootSurfaceRgb", "#%06X".format(expectedRootSurface(configuration, activity, rootSurfaceRole) and 0x00FFFFFF))
                    put("roleOwner", "requested theme factory $rootSurfaceRole; native Scaffold, MenuDefaults or AlertDialogDefaults container")
                    put("contextClass", context.javaClass.name)
                    put("sameContextAsActivity", context === activity)
                    put("sameResourcesAsActivity", context.resources === activity.resources)
                    put("contextUiMode", context.resources.configuration.uiMode)
                    put("rootSurfaceMatches", pixels.rootSurfaceMatches)
                    put("sampledPixels", pixels.sampledPixels)
                    put("meanLuma", pixels.meanLuma)
                    put("rootIdentityPassed", runCatching { UxEvidence.requireTheme(configuration.theme, pixels) }.isSuccess)
                    pixels.statusBar?.let {
                        put("statusBar", statusBarJson(it))
                        put("statusBarBoundsPx", bounds(checkNotNull(statusBounds)))
                        put("statusBarPaintPassed", runCatching { UxEvidence.requireStatusBar(it) }.isSuccess)
                        // Requested flags are diagnostic, never a substitute for SystemUI pixels.
                        put("lightStatusBarAppearanceRequested", lightAppearance)
                        put("windowHasFocus", view.hasWindowFocus())
                    }
                    windowPixels?.let {
                        put("windowCopyRootMatches", it.rootSurfaceMatches)
                        put("windowCopySampledPixels", it.sampledPixels)
                        put("windowCopyRootIdentityPassed", runCatching { UxEvidence.requireTheme(configuration.theme, it) }.isSuccess)
                        put("windowCopySource", "Compose captureToImage Android PixelCopy of the actual app window; diagnostic only, not a replacement for UiAutomation display pixels")
                    }
                })
                return pixels
            } catch (error: UxEvidence.UnrenderedFrame) {
                attempts?.add(buildJsonObject {
                    put("attempt", attempt)
                    put("artifact", attemptPath)
                    put("expectedRootSurfaceRgb", "#%06X".format(expected and 0x00FFFFFF))
                    put("rootIdentityPassed", false)
                    put("failureSource", pixelSource)
                    put("failure", error.message.orEmpty())
                })
                throw error
            } finally {
                bitmap.recycle()
            }
        }
        if (attempts == null) return sample().also { it.statusBar?.let(UxEvidence::requireStatusBar) }
        try {
            return UxEvidence.requireStableTheme(configuration.theme, deadline, SystemClock::uptimeMillis, ::sample)
        } finally {
            // A failed run still keeps its last diagnostic frame and every earlier attempt.
            latestFile?.copyTo(File(output, relative), overwrite = true)
        }
    }

    private fun statusBarJson(pixels: UxStatusBarPixels) = buildJsonObject {
        put("source", "UiAutomation display pixels in actual status-bar inset; isolated fixture only, not app-window PixelCopy")
        put("darkInk", pixels.darkInk)
        put("minimumContrast", 4.5)
        put("clockInkPixels", pixels.clockInkPixels)
        put("statusInkPixels", pixels.statusInkPixels)
        put("minimumInkPixelsPerCluster", pixels.minimumInkPixels)
        put("backgroundMatches", pixels.backgroundMatches)
        put("sampledPixels", pixels.sampledPixels)
    }

    private fun expectedRootSurface(configuration: UxConfiguration, context: Context, role: String = "background"): Int {
        val dark = configuration.theme == "dark"
        val fallback = when (role) {
            "background" -> if (dark) Color(0xFF11161B) else Color(0xFFF5F7FA)
            "surfaceContainer" -> if (dark) Color(0xFF27313B) else Color(0xFFE8EDF2)
            "surfaceContainerHigh" -> if (dark) Color(0xFF313E49) else Color(0xFFDEE6EE)
            else -> error("unsupported native pixel surface role $role")
        }
        val palette = configuration.palette?.let(UxFoundationPalette::from)
        val scheme = when {
            palette == UxFoundationPalette.FocusStudio -> null
            palette != null -> palette.scheme(dark, lightColorScheme())
            Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> null
        }
        return (when (role) {
            "background" -> scheme?.background
            "surfaceContainer" -> scheme?.surfaceContainer
            "surfaceContainerHigh" -> scheme?.surfaceContainerHigh
            else -> null
        } ?: fallback).toArgb()
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
                "Supplemental simulated Material palettes are not actual wallpaper colours; the normal real-app path keeps actual system dynamic colour.",
                "Emulator laboratory evidence, not physical-phone compatibility or usability.",
                "Manual TalkBack, switch access, physical recognition and live hosted speech were not performed.",
                "labNavigationMs is a single local instrumentation observation, not INP or field Web Vitals.",
            ).map(::JsonPrimitive)))
        }
        File(output, "manifest-${request.window}.json").writeText(json.encodeToString(JsonObject.serializer(), manifest) + "\n")
    }

    private fun rawBounds(node: SemanticsNode): UxBounds {
        val coordinates = node.layoutInfo.coordinates
        val position = coordinates.localToScreen(Offset.Zero)
        return UxBounds(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }

    private fun clippedBounds(node: SemanticsNode): UxBounds = node.boundsInWindow.let {
        val coordinates = node.layoutInfo.coordinates
        val offset = coordinates.localToScreen(Offset.Zero) - coordinates.localToWindow(Offset.Zero)
        UxBounds(it.left + offset.x, it.top + offset.y, it.right + offset.x, it.bottom + offset.y)
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

package io.github.jaypetez.ideaforge.ux

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jaypetez.ideaforge.DEVICE_TIMEOUT
import io.github.jaypetez.ideaforge.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in, owned AOSP emulator only. These are actual Launcher3 display frames, not a preview. */
@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val label = target.getString(R.string.app_name)
    private lateinit var launcher: String
    private lateinit var homeComponent: String
    private lateinit var themeUri: String

    @Test
    fun requestedLauncherShowsMaskedAndThemedInstalledIcon() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("launcherIconProbe") == null) {
            require(args.getString("class")?.contains("#requestedLauncherShowsMaskedAndThemedInstalledIcon") != true) {
                "the selected launcher test requires launcherIconProbe=true"
            }
            return
        }
        require(args.getString("launcherIconProbe") == "true")
        require(Build.VERSION.SDK_INT >= 33 && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "launcher evidence requires an owned rendering-enabled API 33+ AOSP emulator"
        }
        val request = requireNotNull(UxCaptureRequest.from(args.keySet().associateWith { args.get(it).toString() }))
        require(request.mode == "matrix" && request.window == "compact" && request.states == null)
        val output = File(requireNotNull(args.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }), "launcher-icon")
        check(output.isDirectory || output.mkdirs())
        val captures = mutableListOf<JsonObject>()
        val artwork = mutableListOf<JsonObject>()
        val expected = setOf("launcher-masked", "launcher-themed")
        val failures = mutableListOf<String>()
        val json = Json { prettyPrint = true }
        fun manifest(status: String) {
            File(output, "manifest-compact.json").writeText(json.encodeToString(
                JsonObject.serializer(), buildJsonObject {
                    put("schema", 1)
                    put("status", status)
                    put("testedSha", request.sha)
                    put("sourceState", request.sourceState)
                    put("surface", "actual AOSP Launcher3 home screen; UiAutomation display screenshot")
                    put("apiLevel", Build.VERSION.SDK_INT)
                    put("deviceModel", Build.MODEL)
                    put("deviceFingerprint", Build.FINGERPRINT)
                    put("display", shell("wm size").trim())
                    put("density", shell("wm density").trim())
                    put("expected", JsonArray(expected.map(::JsonPrimitive)))
                    put("captures", JsonArray(captures))
                    put("artwork", JsonArray(artwork))
                    put("failures", JsonArray(failures.map(::JsonPrimitive)))
                    put("limitations", "Owned AOSP emulator, not physical-phone or other-launcher compatibility. No app session, provider or microphone is used.")
                }) + "\n")
        }
        manifest("running")
        var restoreTheme: (() -> Unit)? = null
        var primaryError: Throwable? = null
        try {
            val icon = target.applicationInfo.loadIcon(target.packageManager) as? AdaptiveIconDrawable
                ?: error("the installed application icon is not adaptive")
            checkNotNull(icon.monochrome) { "the installed adaptive icon has no monochrome layer" }
            homeComponent = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lineSequence().last { "/" in it }.trim()
            launcher = homeComponent.substringBefore('/')
            check(launcher == "com.android.launcher3") { "this probe requires AOSP Launcher3, found $launcher" }
            themeUri = "content://$launcher.grid_control/icon_themed"
            val originalTheme = readTheme()
            restoreTheme = { setTheme(originalTheme) }
            setTheme(false)
            home()
            ensureHomeIcon(output)
            val brand = target.getColor(R.color.ic_launcher_background)
            var iconBounds: Rect? = null
            capture(output, "launcher-masked", false, captures) { bitmap, node ->
                val points = matchingPixels(bitmap, node) { it == brand }
                check(points.size >= 200) { "the actual home icon has no visible brand background" }
                val bounds = Rect(points.minOf { it.first }, points.minOf { it.second },
                    points.maxOf { it.first } + 1, points.maxOf { it.second } + 1)
                check(listOf(bounds.left to bounds.top, bounds.right - 1 to bounds.top,
                    bounds.left to bounds.bottom - 1, bounds.right - 1 to bounds.bottom - 1)
                    .all { (x, y) -> bitmap.getPixel(x, y) != brand }) {
                    "the launcher's mask must remove the square background corners"
                }
                iconBounds = bounds
            }
            val square = checkNotNull(iconBounds)
            val baseline = checkNotNull(android.graphics.BitmapFactory.decodeFile(File(output, "launcher-masked.png").path))
            try {
                setTheme(true)
                home()
                capture(output, "launcher-themed", true, captures) { bitmap, node ->
                    check(node.contains(square)) { "the themed icon moved outside its home item" }
                    check(matchingPixels(bitmap, square) { it == brand }.isEmpty()) {
                        "themed icons are enabled but the icon still paints its brand background"
                    }
                    var changed = 0
                    for (y in square.top until square.bottom) for (x in square.left until square.right) {
                        if (bitmap.getPixel(x, y) != baseline.getPixel(x, y)) changed++
                    }
                    check(changed > square.width() * square.height() / 2) {
                        "enabling the launcher's themed-icons setting did not recolour the actual icon"
                    }
                }
            } finally {
                baseline.recycle()
            }
            writePlayListingArtwork(target, output)
            for (name in listOf("icon.png", "featureGraphic.png")) {
                val file = File(output, "images/$name")
                artwork += buildJsonObject {
                    put("artifact", "images/$name")
                    put("sha256", sha256(file))
                    put("source", "installed AdaptiveIconDrawable background/foreground; Android layer bounds without device mask")
                }
            }
        } catch (error: Throwable) {
            primaryError = error
            runCatching { automation.takeScreenshot()?.let { bitmap ->
                try { writeImage(bitmap, File(output, "failure-current-display.png")) } finally { bitmap.recycle() }
            } }
            File(output, "failure-ui.txt").writeText(tree(automation.rootInActiveWindow))
        } finally {
            UxEvidence.finish(primaryError, listOf({ restoreTheme?.invoke() })) { errors ->
                errors.forEach { failures += "${it.javaClass.simpleName}: ${it.message}" }
                val recorded = captures.map { it.getValue("state").toString().trim('"') }.toSet()
                val complete = recorded == expected && artwork.size == 2 && failures.isEmpty()
                manifest(if (complete) "passed" else "failed")
                UxEvidence.requireComplete(expected, recorded, failures + if (artwork.size == 2) emptyList() else listOf("incomplete Play artwork"))
            }
        }
    }

    private fun capture(
        output: File, name: String, themed: Boolean, records: MutableList<JsonObject>, verify: (Bitmap, Rect) -> Unit,
    ) {
        val deadline = SystemClock.uptimeMillis() + DEVICE_TIMEOUT
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            automation.waitForIdle(300, DEVICE_TIMEOUT)
            val node = homeIcon()
            val bitmap = checkNotNull(automation.takeScreenshot())
            try {
                val bounds = Rect()
                checkNotNull(node) { "the app icon is not visible on the launcher home screen" }.getBoundsInScreen(bounds)
                val pixels = UxEvidence.requireRendered(bitmap, UxBounds(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
                check(readTheme() == themed) { "the launcher's themed-icons preference changed during capture" }
                verify(bitmap, bounds)
                val file = File(output, "$name.png")
                writeImage(bitmap, file)
                records += buildJsonObject {
                    put("state", name)
                    put("artifact", file.name)
                    put("sha256", sha256(file))
                    put("widthPx", bitmap.width)
                    put("heightPx", bitmap.height)
                    put("launcherPackage", launcher)
                    put("applicationPackage", target.packageName)
                    put("applicationIconResource", target.resources.getResourceName(target.applicationInfo.icon))
                    put("themedIconsEnabled", readTheme())
                    put("itemBoundsPx", bounds.flattenToString())
                    put("sampledColours", pixels.colours)
                    put("passed", true)
                }
                return
            } catch (error: Throwable) {
                last = error
            } finally {
                bitmap.recycle()
            }
            SystemClock.sleep(200)
        }
        throw IllegalStateException("$name did not settle: ${last?.message}", last)
    }

    private fun ensureHomeIcon(output: File) {
        if (homeIcon() != null) return
        // Use Launcher3's own intent/action instead of assuming its swipe region or drop grid.
        shell("am start -a android.intent.action.ALL_APPS -n $homeComponent")
        val deadline = SystemClock.uptimeMillis() + DEVICE_TIMEOUT
        var node: AccessibilityNodeInfo? = null
        while (node == null && SystemClock.uptimeMillis() < deadline) {
            automation.waitForIdle(300, DEVICE_TIMEOUT)
            node = findIcon(onHome = false)
        }
        File(output, "app-drawer-ui.txt").writeText(tree(automation.rootInActiveWindow))
        val app = checkNotNull(node) { "the installed app is missing from the launcher app drawer" }
        val add = checkNotNull(app.actionList.singleOrNull { it.label?.toString() == "Add to home screen" }) {
            "the launcher app item has no Add to Home screen accessibility action: ${app.actionList}"
        }
        check(app.performAction(add.id)) { "the launcher rejected Add to Home screen" }
        home()
    }

    private fun homeIcon(): AccessibilityNodeInfo? = findIcon(onHome = true)

    private fun findIcon(onHome: Boolean): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { node ->
            node.isVisibleToUser && node.packageName?.toString() == launcher &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                generateSequence(node) { it.parent }.any {
                    it.viewIdResourceName in setOf("$launcher:id/workspace", "$launcher:id/hotseat")
                } == onHome
        }

    private fun home() {
        shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $homeComponent")
        automation.waitForIdle(500, DEVICE_TIMEOUT)
    }

    private fun readTheme(): Boolean {
        val result = shell("content query --uri $themeUri")
        return when (Regex("boolean_value=(0|1|true|false)").find(result)?.groupValues?.get(1)) {
            "1", "true" -> true
            "0", "false" -> false
            else -> error("cannot read the actual launcher's themed-icons setting: $result")
        }
    }

    private fun setTheme(enabled: Boolean) {
        val result = shell("content update --uri $themeUri --bind boolean_value:b:$enabled")
        // API 34's content update is silent; verify the launcher's actual preference.
        check(readTheme() == enabled) {
            "cannot set the actual launcher's themed-icons setting: $result"
        }
    }

    private fun matchingPixels(bitmap: Bitmap, bounds: Rect, matches: (Int) -> Boolean): List<Pair<Int, Int>> =
        buildList {
            check(Rect(0, 0, bitmap.width, bitmap.height).contains(bounds)) { "launcher item is clipped" }
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                if (matches(bitmap.getPixel(x, y))) add(x to y)
            }
        }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun writeImage(bitmap: Bitmap, file: File) {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun tree(node: AccessibilityNodeInfo?, depth: Int = 0): String = if (node == null) "(no active window)" else
        "${" ".repeat(depth)}${node.viewIdResourceName} ${node.text} ${node.contentDescription} ${node.actionList}\n" +
            (0 until node.childCount).joinToString("") { tree(node.getChild(it), depth + 1) }
}

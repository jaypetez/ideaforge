package io.github.jaypetez.ideaforge.ux

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jaypetez.ideaforge.DEVICE_TIMEOUT
import io.github.jaypetez.ideaforge.ui.result.ResultActions
import java.io.Closeable
import java.io.File

/** Actual system pickers; only this run's documents, clipboard and share files are owned. */
internal class UxSystemActions(
    private val instrumentation: Instrumentation,
    private val target: Context,
    private val output: File,
) : Closeable {
    private val automation = instrumentation.uiAutomation
    private val flags = automation.serviceInfo.flags
    private val clipboard = target.getSystemService(ClipboardManager::class.java)
    private var originalClip: ClipData? = null
    private var clipboardOwned = false
    private val shares = File(target.cacheDir, ResultActions.SHARE_DIR)
    private val hadShareDirectory = shares.isDirectory
    private val originalShares = shares.listFiles().orEmpty().map { it.name }.toSet()
    private val ownedShares = mutableSetOf<File>()
    private val documents = mutableListOf<Uri>()
    private val savedPaths = mutableListOf<String>()
    private val prefix = "ux207-${SystemClock.elapsedRealtimeNanos()}"

    init {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    fun awaitExternalWindow() = await {
        it.packageName?.toString() != target.packageName &&
            it.packageName?.toString() !in setOf("com.android.systemui", "com.android.inputmethod.latin")
    }

    /**
     * The app frame just before a system window is launched; [snapshot] requires the
     * window's frame to have changed from it. Captured by [launch].
     */
    private var baseline: IntArray? = null

    fun launch(action: () -> Unit) {
        baseline = sample()
        action()
    }

    private fun sample(): IntArray {
        automation.waitForIdle(300, DEVICE_TIMEOUT)
        val bitmap = checkNotNull(automation.takeScreenshot()) { "no baseline screenshot" }
        return try { UxEvidence.frameSample(bitmap) } finally { bitmap.recycle() }
    }

    fun snapshot(name: String) {
        // A new window's accessibility tree can arrive before its first painted frame.
        automation.waitForIdle(300, DEVICE_TIMEOUT)
        check(output.isDirectory || output.mkdirs())
        val before = baseline
        baseline = null
        val deadline = SystemClock.uptimeMillis() + DEVICE_TIMEOUT
        var previousSheet: Pair<UxBounds, UxBounds>? = null
        var previousFrame: IntArray? = null
        while (true) {
            val root = automation.rootInActiveWindow
            root?.refresh()
            val sheet = sheet(root)
            val bitmap = checkNotNull(automation.takeScreenshot()) { "no system screenshot for $name" }
            try {
                UxEvidence.requireRendered(bitmap, UxBounds(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
                // Idle accessibility is not paint. A chooser's tree arrives while the screen
                // still shows only its scrim over the dimmed source and the sheet then slides
                // in; a picker's arrives while the app is still on screen or mid-transition.
                // Accept a frame only once it is settled (unchanged since the last sample),
                // has changed from the app frame captured before the launch, and, for a
                // chooser, shows its painted sheet at the same reported bounds as before.
                val frame = UxEvidence.frameSample(bitmap)
                val painted = sheet == null || UxEvidence.sheetPainted(bitmap, sheet.first, sheet.second)
                val accepted = painted && sheet == previousSheet && UxEvidence.settled(previousFrame, frame) &&
                    (before == null || UxEvidence.windowChanged(before, frame))
                if (accepted) {
                    File(output, "$name-system.txt").writeText(tree(root))
                    File(output, "$name-system.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    return
                }
                previousSheet = if (painted) sheet else null
                previousFrame = frame
                check(SystemClock.uptimeMillis() < deadline) {
                    "the $name system window did not paint and settle within the capture budget:\n${tree(root)}"
                }
            } finally { bitmap.recycle() }
            SystemClock.sleep(150)
        }
    }

    /** The share chooser's header and drag handle, in screen pixels, when that sheet is active. */
    private fun sheet(root: AccessibilityNodeInfo?): Pair<UxBounds, UxBounds>? {
        fun bounds(id: String) = descendants(root).firstOrNull { it.viewIdResourceName == id && it.isVisibleToUser }?.let {
            val rect = Rect()
            it.getBoundsInScreen(rect)
            UxBounds(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
        }
        val header = bounds("android:id/chooser_header") ?: return null
        val handle = checkNotNull(bounds("android:id/drag")) { "the chooser header has no drag handle to prove its paint" }
        return header to handle
    }

    fun createDocument(label: String): String {
        require(Regex("[a-z][a-z0-9-]*\\.(md|json)").matches(label))
        awaitExternalWindow()
        downloads()
        val filename = "$prefix-$label"
        val path = "/sdcard/Download/$filename"
        check(shell("if test -e '$path'; then echo exists; fi").isBlank()) { "refusing an existing document: $filename" }
        savedPaths += path
        val field = await { it.className?.toString() == "android.widget.EditText" && it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
        })) { "the native filename field rejected editing" }
        snapshot("create-$label")
        click(await { it.text?.toString().equals("Save", ignoreCase = true) && it.isEnabled })
        return path
    }

    fun readDocument(path: String): String {
        check(path in savedPaths)
        return shell("if test -f '$path'; then cat '$path'; fi")
    }

    fun importDocument(label: String, text: String): String {
        val name = "$prefix-$label.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val uri = checkNotNull(target.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        documents += uri
        checkNotNull(target.contentResolver.openOutputStream(uri, "wt")).use { it.write(text.encodeToByteArray()) }
        return name
    }

    fun chooseDocument(name: String) {
        awaitExternalWindow()
        downloads()
        val file = await { it.text?.toString() == name }
        snapshot("import-${name.substringAfter(prefix).trimStart('-')}")
        click(file)
    }

    fun requireCopied(export: String) {
        check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == export) {
            "Copy did not put the complete canonical markdown on the clipboard"
        }
    }

    fun copy(action: () -> Unit) {
        if (!clipboardOwned) {
            originalClip = clipboard.primaryClip
            clipboardOwned = true
        }
        action()
    }

    fun share(action: () -> Unit) {
        val before = shares.listFiles().orEmpty().toSet()
        try {
            action()
        } finally {
            ownedShares += shares.listFiles().orEmpty().toSet() - before
        }
    }

    fun requireShared(export: String) {
        val created = ownedShares.filter { it.name !in originalShares }
        check(created.size == 1 && created.single().listFiles().orEmpty().single().readText() == export) {
            "Share did not create one owned file containing the complete canonical markdown"
        }
    }

    fun requireCommandTransport() {
        check(shell("printf 'owned transport\\n'") == "owned transport\n") { "native command quoting/newlines were not preserved" }
        check(runCatching { shell("cat '/sdcard/Download/$prefix-never-created'") }.exceptionOrNull() is IllegalStateException) {
            "a failed native command must report its stderr and exit status, never look like an empty file"
        }
    }

    private fun downloads() {
        fun root(node: AccessibilityNodeInfo): Boolean = node.text?.toString() == "Downloads" &&
            generateSequence(node.parent) { it.parent }.any {
                it.viewIdResourceName == "com.android.documentsui:id/roots_list"
            }
        val navigator = await {
            root(it) || it.contentDescription?.toString() == "Show roots"
        }
        if (!root(navigator)) clickWhere { it.contentDescription?.toString() == "Show roots" }
        // Re-query until the actual drawer row accepts the action, never a stale title.
        clickWhere(::root)
    }

    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var action: AccessibilityNodeInfo? = node
        while (action != null && !action.isClickable) action = action.parent
        return action?.takeIf { it.isEnabled }
    }

    private fun click(node: AccessibilityNodeInfo) {
        check(clickTarget(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            "the native action was not clickable: ${node.text}/${node.contentDescription}\n${tree(automation.rootInActiveWindow)}"
        }
    }

    private fun clickWhere(matches: (AccessibilityNodeInfo) -> Boolean) {
        await { matches(it) && clickTarget(it)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
    }

    private fun await(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + DEVICE_TIMEOUT
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            descendants(root).firstOrNull { it.isVisibleToUser && matches(it) }?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("missing native system action:\n${tree(automation.rootInActiveWindow)}")
    }

    private fun shell(command: String): String {
        if (Build.VERSION.SDK_INT >= 34) {
            // UiAutomation uses Runtime.exec, not shell parsing. Feed a real sh over stdin.
            val streams = automation.executeShellCommandRwe("sh")
            val marker = "UX207_${SystemClock.elapsedRealtimeNanos()}_EXIT="
            return streams[0].use { stdout -> streams[1].use { stdin -> streams[2].use { stderr ->
                ParcelFileDescriptor.AutoCloseOutputStream(stdin).use {
                    it.write(("$command\nux207_status=${'$'}?\nprintf '\\n$marker%d\\n' \"${'$'}ux207_status\"\n").encodeToByteArray())
                }
                val output = ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().use { it.readText() }
                val error = ParcelFileDescriptor.AutoCloseInputStream(stderr).bufferedReader().use { it.readText() }
                val status = Regex("\\n${Regex.escape(marker)}([0-9]+)\\n$").find(output)
                check(status != null && status.groupValues[1] == "0" && error.isBlank()) {
                    "native command failed: $command\nstderr=$error\nstdout=$output"
                }
                output.substring(0, status.range.first)
            } } }
        }
        error("native document acceptance requires the API 34+ stderr-capable managed-device transport")
    }

    private fun descendants(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (index in 0 until node.childCount) yieldAll(descendants(node.getChild(index)))
    }

    private fun tree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return "(no active window)"
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${" ".repeat(depth)}${node.packageName} ${node.className} ${node.viewIdResourceName} " +
            "${node.text} ${node.contentDescription} enabled=${node.isEnabled} clickable=${node.isClickable} " +
            "bounds=${bounds.flattenToString()}\n" +
            (0 until node.childCount).joinToString("") { tree(node.getChild(it), depth + 1) }
    }

    override fun close() {
        val cleanup = documents.map { uri -> { check(target.contentResolver.delete(uri, null, null) == 1); Unit } } +
            savedPaths.map { path -> {
                shell("rm -f '$path'")
                check(shell("if test -e '$path'; then echo exists; fi").isBlank()) { "owned document remains: $path" }
                Unit
            } } + ownedShares.map { file -> { check(file.deleteRecursively()) { "owned share file remains" }; Unit } }
        UxEvidence.finish(null, cleanup + listOf(
            { if (!hadShareDirectory && shares.exists()) check(shares.delete()) { "owned share directory is not empty" } },
            { if (clipboardOwned) {
                val clip = originalClip
                if (clip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(clip)
            } },
            { automation.serviceInfo = automation.serviceInfo.apply { flags = this@UxSystemActions.flags } },
        )) {}
    }
}

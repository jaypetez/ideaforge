package io.github.jaypetez.ideaforge.ux

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
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
    private val originalClip = clipboard.primaryClip
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

    fun snapshot(name: String) {
        check(output.isDirectory || output.mkdirs())
        File(output, "$name-system.txt").writeText(tree(automation.rootInActiveWindow))
        val bitmap = checkNotNull(automation.takeScreenshot()) { "no system screenshot for $name" }
        try {
            UxEvidence.requireRendered(bitmap, UxBounds(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
            File(output, "$name-system.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    fun createDocument(label: String): String {
        awaitExternalWindow()
        downloads()
        val filename = "$prefix-$label"
        val path = "/sdcard/Download/$filename"
        check(shell("test -e '$path' && echo exists").isBlank()) { "refusing an existing document: $filename" }
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
        return shell("cat '$path'")
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

    fun requireShared(export: String) {
        val created = shares.listFiles().orEmpty().filter { it.name !in originalShares }
        check(created.size == 1 && created.single().listFiles().orEmpty().single().readText() == export) {
            "Share did not create one owned file containing the complete canonical markdown"
        }
        ownedShares += created.single()
    }

    private fun downloads() {
        val root = automation.rootInActiveWindow
        val drawer = descendants(root).firstOrNull { it.contentDescription?.toString() == "Show roots" }
        if (drawer != null) {
            click(drawer)
            click(await { it.text?.toString() == "Downloads" })
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var action: AccessibilityNodeInfo? = node
        while (action != null && !action.isClickable) action = action.parent
        check(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            "the native action was not clickable: ${node.text}/${node.contentDescription}"
        }
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

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
                check(shell("test -e '$path' && echo exists").isBlank()) { "owned document remains: $path" }
                Unit
            } } + ownedShares.map { file -> { check(file.deleteRecursively()) { "owned share file remains" }; Unit } }
        UxEvidence.finish(null, cleanup + listOf(
            { if (!hadShareDirectory && shares.exists()) check(shares.delete()) { "owned share directory is not empty" } },
            { if (originalClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(originalClip) },
            { automation.serviceInfo = automation.serviceInfo.apply { flags = this@UxSystemActions.flags } },
        )) {}
    }
}

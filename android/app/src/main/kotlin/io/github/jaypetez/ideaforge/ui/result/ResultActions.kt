package io.github.jaypetez.ideaforge.ui.result

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import java.io.File
import java.security.SecureRandom

/**
 * Share and Copy: the two ways out that need a Context. Both take the export string the
 * ViewModel built with [exportFor], never anything read back off the screen; the web learnt
 * that the rendered view is not the document (`#output`'s textContent has every marker gone).
 */
object ResultActions {
    /** The cache subdirectory res/xml/export_paths.xml exposes, and nothing else. */
    const val SHARE_DIR = "exports"

    fun authority(context: Context): String = "${context.packageName}.exports"

    /**
     * The content URI for a file under cache/exports. A seam for one reason: FileProvider
     * matches roots by `/`-joined canonical paths, so under Robolectric on a Windows host it
     * finds no root for any file. ResultScreenTest swaps this only when a probe through the
     * real provider fails, and requires the real one off Windows; on Linux CI and on devices
     * the real FileProvider, with the manifest's authority and paths, is what runs.
     */
    @VisibleForTesting
    internal var contentUri: (Context, File) -> Uri = { context, file ->
        FileProvider.getUriForFile(context, authority(context), file)
    }

    /** How long a shared file stays readable before a later share or app start removes it. */
    const val SHARE_TTL_MS = 24L * 60 * 60 * 1000

    private val random = SecureRandom()

    /**
     * ACTION_SEND for [export] as a `text/markdown` file named [filename], through the
     * FileProvider, with the text itself as EXTRA_TEXT for targets that ignore streams: the
     * web's shareTextFile, which offers a file and falls back to text.
     *
     * Every share gets its own directory, `cache/exports/<now>-<random>/<filename>`, and is
     * never rewritten: a target may read its URI long after the sheet closes (a queued
     * message, an upload), and the filename is only per idea per day, so writing in place
     * would truncate a file under a reader and deleting siblings would hand an earlier share
     * a dead URI. Only directories older than [SHARE_TTL_MS] are cleaned up, here and at app
     * start ([pruneShares]); the one being written is never among them.
     */
    fun shareIntent(
        context: Context, export: String, filename: String, title: String, now: Long = System.currentTimeMillis(),
    ): Intent {
        val root = File(context.cacheDir, SHARE_DIR)
        pruneShares(root, now)
        val suffix = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val dir = File(root, "$now-$suffix").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeBytes(export.encodeToByteArray())
        val uri = contentUri(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = MARKDOWN_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, export)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            // The grant has to ride on ClipData as well as the flag: that is what carries it
            // through the chooser to whichever app is picked.
            clipData = ClipData.newRawUri(filename, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Removes share directories under [root] created more than [SHARE_TTL_MS] before [now].
     * A directory's age is the timestamp its name starts with (the clock that made it, so a
     * test can inject one), or its modification time when the name has none. Anything
     * younger, and anything created in the future by a clock that moved back, is kept.
     */
    fun pruneShares(root: File, now: Long) {
        for (entry in root.listFiles().orEmpty()) {
            val made = entry.name.substringBefore('-').toLongOrNull() ?: entry.lastModified()
            if (now - made > SHARE_TTL_MS) entry.deleteRecursively()
        }
    }

    /** App start: clear out shares that have aged past [SHARE_TTL_MS]. Never throws. */
    fun pruneShares(context: Context, now: Long) {
        runCatching { pruneShares(File(context.cacheDir, SHARE_DIR), now) }
    }

    /** The system share sheet over [shareIntent]. */
    fun chooser(context: Context, export: String, filename: String, title: String, chooserTitle: String, now: Long = System.currentTimeMillis()): Intent =
        Intent.createChooser(shareIntent(context, export, filename, title, now), chooserTitle)

    /**
     * Copy markdown: exactly [export] on the clipboard. Marked not sensitive on Android 13+
     * (it is the user's own idea, and the system preview is how they see that it worked).
     * Returns whether the app should confirm the copy itself: from Android 13 the system
     * shows its own confirmation, and a second one from the app is the duplicate the platform
     * guidance asks apps not to show.
     */
    fun copy(context: Context, export: String, label: String): Boolean {
        val clip = ClipData.newPlainText(label, export)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        return confirmsCopyItself()
    }

    fun confirmsCopyItself(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk <= Build.VERSION_CODES.S_V2
}

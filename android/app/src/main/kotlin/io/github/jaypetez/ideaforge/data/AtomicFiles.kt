package io.github.jaypetez.ideaforge.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The temp file a write lands in before it is renamed into place. Never read back. */
internal const val ATOMIC_NEW_SUFFIX = ".new"

/**
 * Write-then-rename: the bytes go to `<file>.new`, are synced, then atomically replace
 * `<file>`, and the directory is synced. A crash at any point leaves either the old file or
 * the new one.
 *
 * Not androidx AtomicFile: its finishWrite logs a failed rename and returns normally, so the
 * caller believes a save that never reached disk. That is not hypothetical: on a Windows JVM
 * (Robolectric on a developer machine) File.renameTo refuses to replace an existing file, and
 * every second save of a session silently vanished. Files.move with REPLACE_EXISTING throws
 * instead, and replaces on every platform.
 */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ATOMIC_NEW_SUFFIX)
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
    file.absoluteFile.parentFile?.let { syncDirectory(it.toPath()) }
}

/**
 * Make a rename durable. Until the directory itself is synced, a power cut can revert the
 * directory entry to the old file even though the new file's bytes were synced. That matters
 * most to the keyring: its previous blob may be readable only by a key that a later save is
 * free to replace.
 *
 * On Linux, and so on Android, a directory opens read-only and `force` fsyncs it. A Windows
 * JVM refuses to open a directory as a channel at all (AccessDeniedException); NTFS journals
 * the rename itself, and Windows only ever runs this under unit tests, so the error is
 * ignored there and nowhere else.
 */
internal fun syncDirectory(dir: Path) {
    try {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    } catch (e: IOException) {
        if (!isWindows) throw e
    }
}

private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

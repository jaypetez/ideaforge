package io.github.jaypetez.ideaforge.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
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
internal fun writeAtomically(file: File, bytes: ByteArray) =
    writeAtomicallyWith(file, bytes, renameAttempts = if (isWindows) WINDOWS_RENAME_ATTEMPTS else 1)

/**
 * How many times a Windows host tries the rename before the save fails.
 *
 * Windows refuses to replace a file while any other process has it open without
 * FILE_SHARE_DELETE, and Defender and the search indexer open freshly written files to scan
 * them. The rename then fails with AccessDeniedException for a few milliseconds and succeeds
 * on the next try. Under a loaded full `:app:testDebugUnitTest` run that was about one run in
 * six (#138): a turn's save threw, the interview posted a Failed notice and lost two turns in
 * WalkthroughVectorTest, and KeyringStoreTest failed on the same exception. Linux, and so
 * Android, has no such lock on rename: one attempt there, and any failure is real.
 */
internal const val WINDOWS_RENAME_ATTEMPTS = 6

/** The pause before retry n (1-based): 10, 20, 40, 80, 160 ms, about 0.3 s in the worst case. */
private fun renameBackoffMs(retry: Int): Long = 10L shl (retry - 1)

/**
 * [writeAtomically] with its seams exposed for tests: [move] is the rename, [renameAttempts]
 * bounds the retries, [pause] waits between them. Only a [FileSystemException] that can be a
 * transient lock is retried (access denied, or the generic "in use by another process");
 * a missing directory or any other error fails at once, as does the last attempt.
 */
internal fun writeAtomicallyWith(
    file: File,
    bytes: ByteArray,
    renameAttempts: Int,
    move: (Path, Path) -> Unit = ::replaceAtomically,
    pause: (Long) -> Unit = Thread::sleep,
) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ATOMIC_NEW_SUFFIX)
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        var attempt = 1
        while (true) {
            try {
                move(tmp.toPath(), file.toPath())
                break
            } catch (e: FileSystemException) {
                if (attempt >= renameAttempts || !mayBeTransientLock(e)) throw e
                pause(renameBackoffMs(attempt))
                attempt++
            }
        }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
    file.absoluteFile.parentFile?.let { syncDirectory(it.toPath()) }
}

private fun replaceAtomically(from: Path, to: Path) {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

// Windows reports a sharing violation as a plain FileSystemException ("The process cannot
// access the file because it is being used by another process") and a pending delete or a
// scanner's handle as AccessDeniedException. Subclasses such as NoSuchFileException are
// never transient.
private fun mayBeTransientLock(e: FileSystemException): Boolean =
    e is AccessDeniedException || e.javaClass == FileSystemException::class.java

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

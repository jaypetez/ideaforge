package io.github.jaypetez.ideaforge.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The temp file a write lands in before it is renamed into place. Never read back. */
internal const val ATOMIC_NEW_SUFFIX = ".new"

/**
 * Write-then-rename: the bytes go to `<file>.new`, are synced, then atomically replace
 * `<file>`. A crash at any point leaves either the old file or the new one.
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
}

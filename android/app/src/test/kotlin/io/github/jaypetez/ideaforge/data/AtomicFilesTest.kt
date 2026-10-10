package io.github.jaypetez.ideaforge.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The rename retry in [writeAtomicallyWith]. It exists because a Windows host's scanner or
 * indexer briefly holding the target made a save throw AccessDeniedException about one full
 * test run in six (#138), which ended WalkthroughVectorTest's interview two turns early.
 */
class AtomicFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pauses = mutableListOf<Long>()
    private var moves = 0

    /** Fails the first [failures] renames with [error], then renames for real. */
    private fun flakyMover(failures: Int, error: (Path, Path) -> FileSystemException): (Path, Path) -> Unit = { from, to ->
        moves++
        if (moves <= failures) throw error(from, to)
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun denied(from: Path, to: Path) = AccessDeniedException(from.toString(), to.toString(), null)

    private fun target(): File = File(tmp.root, "s.json").apply { writeText("old") }

    @Test
    fun aRenameRefusedOnceIsRetriedAndLands() {
        val file = target()
        writeAtomicallyWith(file, "new".encodeToByteArray(), renameAttempts = WINDOWS_RENAME_ATTEMPTS, move = flakyMover(1, ::denied), pause = { pauses += it })

        assertArrayEquals("new".encodeToByteArray(), file.readBytes())
        assertFalse("the temp file is gone", File(file.path + ATOMIC_NEW_SUFFIX).exists())
        assertEquals(2, moves)
        assertEquals(listOf(10L), pauses)
    }

    @Test
    fun aSharingViolationIsRetriedToo() {
        // Windows reports "in use by another process" as a plain FileSystemException.
        val file = target()
        val inUse = { from: Path, to: Path -> FileSystemException(from.toString(), to.toString(), "being used by another process") }
        writeAtomicallyWith(file, "new".encodeToByteArray(), renameAttempts = WINDOWS_RENAME_ATTEMPTS, move = flakyMover(2, inUse), pause = { pauses += it })

        assertEquals("new", file.readText())
        assertEquals(listOf(10L, 20L), pauses)
    }

    @Test
    fun oneAttemptMeansTheFirstFailureThrows() {
        // What Android and Linux get: no lock on rename there, so a failure is real.
        val file = target()
        assertThrows(AccessDeniedException::class.java) {
            writeAtomicallyWith(file, "new".encodeToByteArray(), renameAttempts = 1, move = flakyMover(1, ::denied), pause = { pauses += it })
        }
        assertEquals("old", file.readText())
        assertFalse(File(file.path + ATOMIC_NEW_SUFFIX).exists())
        assertEquals(1, moves)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun aLockThatNeverClearsFailsAfterTheBoundWithTheOldFileIntact() {
        val file = target()
        assertThrows(AccessDeniedException::class.java) {
            writeAtomicallyWith(file, "new".encodeToByteArray(), renameAttempts = WINDOWS_RENAME_ATTEMPTS, move = flakyMover(Int.MAX_VALUE, ::denied), pause = { pauses += it })
        }
        assertEquals("old", file.readText())
        assertFalse(File(file.path + ATOMIC_NEW_SUFFIX).exists())
        assertEquals(WINDOWS_RENAME_ATTEMPTS, moves)
        assertEquals(listOf(10L, 20L, 40L, 80L, 160L), pauses)
    }

    @Test
    fun anErrorThatCannotBeALockIsNotRetried() {
        val file = target()
        val missing = { from: Path, _: Path -> NoSuchFileException(from.toString()) }
        assertThrows(NoSuchFileException::class.java) {
            writeAtomicallyWith(file, "new".encodeToByteArray(), renameAttempts = WINDOWS_RENAME_ATTEMPTS, move = flakyMover(1, missing), pause = { pauses += it })
        }
        assertEquals(1, moves)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun theProductionPathStillReplacesAnExistingFile() {
        val file = target()
        writeAtomically(file, "new".encodeToByteArray())
        writeAtomically(file, "newer".encodeToByteArray())
        assertEquals("newer", file.readText())
    }
}

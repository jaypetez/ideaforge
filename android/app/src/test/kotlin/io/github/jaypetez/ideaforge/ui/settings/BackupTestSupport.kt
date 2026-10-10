package io.github.jaypetez.ideaforge.ui.settings

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** test/fixtures in the repository, handed in by app/build.gradle.kts. */
val fixturesDir: File
    get() = File(
        System.getProperty("ideaforge.fixtures")
            ?: error("ideaforge.fixtures is not set; run the tests through Gradle"),
    )

/**
 * SAF documents in memory. Counts every open and every byte handed out, so a test can say
 * that an oversize file was refused before it was opened, or after a bounded read.
 */
class FakeDocuments : BackupDocuments {
    private val inputs = HashMap<Uri, () -> InputStream>()
    private val sizes = HashMap<Uri, Long?>()
    val outputs = HashMap<Uri, ByteArrayOutputStream>()
    var opens = 0
        private set
    var bytesRead = 0L
        private set

    fun file(uri: Uri, bytes: ByteArray, size: Long? = bytes.size.toLong()) =
        stream(uri, size) { ByteArrayInputStream(bytes) }

    fun stream(uri: Uri, size: Long?, open: () -> InputStream) {
        inputs[uri] = open
        sizes[uri] = size
    }

    override fun size(uri: Uri): Long? = sizes[uri]

    override fun openInput(uri: Uri): InputStream {
        opens++
        val inner = inputs.getValue(uri)()
        return object : InputStream() {
            override fun read(): Int = inner.read().also { if (it >= 0) bytesRead++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, len).also { if (it > 0) bytesRead += it }
            override fun close() = inner.close()
        }
    }

    override fun openOutput(uri: Uri): OutputStream = ByteArrayOutputStream().also { outputs[uri] = it }
}

/** An endless stream of spaces: a document whose provider reports no size and never ends. */
class EndlessStream : InputStream() {
    override fun read(): Int = ' '.code
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        b.fill(' '.code.toByte(), off, off + len)
        return len
    }
}

/** Waits for the action just started to settle. */
fun BackupViewModel.settled(): BackupState = runBlocking {
    withTimeout(20_000) { state.first { !it.busy } }
}

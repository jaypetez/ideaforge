package io.github.jaypetez.ideaforge.ui.settings

import android.net.Uri
import io.github.jaypetez.ideaforge.core.buildBackup
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.jsonQuote
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    var outputOpens = 0
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

    override fun openOutput(uri: Uri): OutputStream = ByteArrayOutputStream().also {
        outputOpens++
        outputs[uri] = it
    }
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

/** Follow the UI's preparation-before-picker path, choosing this test destination if allowed. */
fun BackupViewModel.exportTo(uri: Uri): BackupState {
    if (runBlocking { prepareExport() } != null) export(uri)
    return settled()
}

/** The serialized file, including its envelope/newline and escaped multibyte draft, is exactly [bytes]. */
fun backupSizedIdea(bytes: Int, now: Long): JsonObject {
    val session = createSession("s_utf8", num(now))
    val room = bytes - buildBackup(JsonArray(listOf(session)), num(now)).encodeToByteArray().size
    val unit = "😀é漢\"\\\n"
    val unitBytes = jsonQuote(unit).encodeToByteArray().size - 2
    val draft = unit.repeat(room / unitBytes) + "x".repeat(room % unitBytes)
    return JsonObject(session + ("draftAnswer" to str(draft)))
}

package io.github.jaypetez.ideaforge

import android.security.keystore.KeyProperties
import io.github.jaypetez.ideaforge.core.answerQuestion
import io.github.jaypetez.ideaforge.core.askQuestion
import io.github.jaypetez.ideaforge.core.buildBackup
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.maskKey
import io.github.jaypetez.ideaforge.core.parseBackup
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.core.withTts
import io.github.jaypetez.ideaforge.data.AndroidKeystoreKey
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.SoftwareKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyringWiringTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val apiKey = "sk-ant-api03-NEVER-IN-A-BACKUP-91c2"
    private val sttKey = "gsk_dictation_NEVER_IN_A_BACKUP_44"
    private val ttsKey = "sk-proj-speech-NEVER-IN-A-BACKUP-0d"

    private fun text(s: String) = JsonPrimitive(s)

    @Test
    fun theKeyringLivesInNoBackupFilesAndOutsideTheSessionsDirectory() {
        val graph = AppGraph(app, keyringKey = SoftwareKey())
        val file = graph.keyringStore.file.canonicalFile
        assertEquals(app.noBackupFilesDir.canonicalFile, file.parentFile)
        val sessions = File(app.filesDir, "sessions").canonicalFile
        assertFalse("keyring inside the sessions directory", file.path.startsWith(sessions.path))
    }

    @Test
    fun theKeystoreKeyIsAes256GcmWithoutUserAuthentication() {
        val spec = AndroidKeystoreKey.spec()
        assertEquals("ideaforge.keyring.v1", spec.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
        assertEquals(256, spec.keySize)
        assertFalse(spec.isUserAuthenticationRequired)
        assertTrue("the Keystore must choose every IV", spec.isRandomizedEncryptionRequired)
    }

    /**
     * Backups contain sessions and nothing else. With a keyring holding three secrets saved
     * beside the sessions, and a session whose answers name the provider, no secret, nor its
     * masked form, nor any keyring field, appears in the backup's bytes.
     */
    @Test
    fun keysNeverAppearInASessionBackup() = runBlocking {
        val graph = AppGraph(app, keyringKey = SoftwareKey())
        val ring = withTts(
            withStt(withCreds(emptyKeyring(), "anthropic", JsonObject(mapOf("apiKey" to text(apiKey)))),
                JsonObject(mapOf("kind" to text("groq"), "apiKey" to text(sttKey)))),
            JsonObject(mapOf("kind" to text("openai"), "apiKey" to text(ttsKey), "verified" to JsonPrimitive(true))),
        )
        graph.keyringStore.save(ring)
        assertEquals(KeyringState.Status.STORED, graph.keyringStore.load().status)

        var session = createSession("s_backup_00000001", num(1))
        session = askQuestion(session, str("What are you building?"), str("problem"), now = num(2))
        session = answerQuestion(session, str("A tool that calls the anthropic API for me"), now = num(3))
        graph.sessionStore.save(session)
        graph.sessionStore.save(createSession("s_backup_00000002", num(4)))

        val backup = buildBackup(JsonArray(graph.sessionStore.sessions.value), num(5))
        for (secret in listOf(apiKey, sttKey, ttsKey)) {
            assertFalse("a secret reached the backup", backup.contains(secret))
            assertFalse("a masked secret reached the backup", backup.contains(maskKey(secret)))
            assertFalse("a fragment of a secret reached the backup", backup.contains(secret.takeLast(10)))
        }
        for (field in listOf("byKind", "apiKey", "wrapModel", "\"stt\"", "\"tts\"")) {
            assertFalse("$field reached the backup", backup.contains(field))
        }
        val doc = Json.parseToJsonElement(backup).jsonObject
        assertEquals(setOf("format", "version", "exportedAt", "sessions"), doc.keys)
        assertEquals(2, (parseBackup(backup)["sessions"] as JsonArray).size)
    }
}

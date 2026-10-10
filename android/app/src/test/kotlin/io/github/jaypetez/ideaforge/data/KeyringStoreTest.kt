package io.github.jaypetez.ideaforge.data

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.migrateCredentials
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.core.withTts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A software stand-in for [AndroidKeystoreKey]: a plain JVM has no AndroidKeyStore. The
 * cipher, IV handling and file format under test are the production code unchanged.
 */
class SoftwareKey(var key: SecretKey? = null) : KeyringKey {
    var created = 0
    var deleted = 0

    override fun existing(): SecretKey? = key

    override fun getOrCreate(): SecretKey = key ?: fresh().also { key = it; created++ }

    override fun delete() {
        deleted++
        key = null
    }

    companion object {
        fun fresh(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

class KeyringStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val apiKey = "sk-ant-api03-SECRET-do-not-leak-7f3a"
    private val ring = withTts(
        withStt(withCreds(emptyKeyring(), "anthropic", obj("apiKey" to apiKey, "model" to "claude-x")), obj("kind" to "groq", "apiKey" to "gsk-stt-secret")),
        obj("kind" to "openai", "apiKey" to "sk-tts-secret", "voice" to "cedar", "verified" to true),
    )

    private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> error("unsupported")
        }
    })

    private val file get() = File(tmp.root, "nobackup/${KeyringStore.FILE_NAME}")
    private fun store(key: KeyringKey) = KeyringStore(file, key, io = Dispatchers.Unconfined)

    @Test
    fun roundTripsThroughAFreshStore() = runBlocking {
        val key = SoftwareKey()
        store(key).save(ring)
        val loaded = store(key).load()
        assertEquals(KeyringState.Status.STORED, loaded.status)
        assertEquals(ring, loaded.ring)
        assertEquals(apiKey, loaded.ring["byKind"]!!.jsonObject["anthropic"]!!.jsonObject["apiKey"]!!.let { (it as JsonPrimitive).content })
    }

    @Test
    fun nothingStoredIsNoneAndCreatesNoKey() = runBlocking {
        val key = SoftwareKey()
        val state = store(key).load()
        assertEquals(KeyringState.Status.NONE, state.status)
        assertEquals(emptyKeyring(), state.ring)
        assertNull("load must not mint a key", key.key)
    }

    // The point of the exercise: the file holds ciphertext, not the key or even its shape.
    @Test
    fun theFileOnDiskHoldsNoPlaintext() = runBlocking {
        store(SoftwareKey()).save(ring)
        val bytes = file.readBytes()
        val latin = String(bytes, Charsets.ISO_8859_1)
        for (needle in listOf(apiKey, "gsk-stt-secret", "sk-tts-secret", "byKind", "anthropic", "version")) {
            assertFalse("\"$needle\" is readable in the keyring file", latin.contains(needle))
        }
    }

    @Test
    fun theBlobIsFormatThenA12ByteIvThenCiphertextAndA128BitTag() = runBlocking {
        store(SoftwareKey()).save(ring)
        val plainSize = Json.encodeToString(JsonObject.serializer(), ring).encodeToByteArray().size
        val bytes = file.readBytes()
        assertEquals(KeyringStore.FORMAT_V1, bytes[0])
        assertEquals(1 + 12 + plainSize + 16, bytes.size)
    }

    // GCM under one key with a repeated IV leaks the XOR of plaintexts and the auth key.
    @Test
    fun everyWriteUsesAFreshIv() = runBlocking {
        val key = SoftwareKey()
        val s = store(key)
        val ivs = HashSet<String>()
        val blobs = HashSet<String>()
        repeat(32) {
            s.save(ring)
            val bytes = file.readBytes()
            ivs += bytes.copyOfRange(1, 13).joinToString(",")
            blobs += bytes.joinToString(",")
        }
        assertEquals("IVs repeated across 32 writes of the same ring", 32, ivs.size)
        assertEquals(32, blobs.size)
        val direct = (1..64).map { KeyringStore.encrypt(key.key!!, ByteArray(8)).copyOfRange(1, 13).joinToString(",") }.toSet()
        assertEquals(64, direct.size)
    }

    @Test
    fun aTamperedByteAnywhereReadsAsUnreadableNotACrash() = runBlocking {
        val key = SoftwareKey()
        store(key).save(ring)
        val good = file.readBytes()
        for (i in good.indices) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            file.writeBytes(bad)
            val state = store(key).load()
            assertEquals("byte $i flipped", KeyringState.Status.UNREADABLE, state.status)
            assertEquals(emptyKeyring(), state.ring)
            assertNotNull(state.problem)
        }
    }

    @Test
    fun truncatedEmptyAndForeignFilesReadAsUnreadable() = runBlocking {
        val key = SoftwareKey()
        store(key).save(ring)
        val good = file.readBytes()
        for (bytes in listOf(ByteArray(0), good.copyOf(20), good.copyOf(good.size - 1), "{\"version\":2}".toByteArray())) {
            file.writeBytes(bytes)
            assertEquals(KeyringState.Status.UNREADABLE, store(key).load().status)
        }
    }

    @Test
    fun aLostOrDifferentKeyReadsAsUnreadableAndMintsNothing() = runBlocking {
        val key = SoftwareKey()
        store(key).save(ring)
        val lost = SoftwareKey(null)
        assertEquals(KeyringState.Status.UNREADABLE, store(lost).load().status)
        assertNull("decrypting must not create a key", lost.key)
        assertEquals(KeyringState.Status.UNREADABLE, store(SoftwareKey(SoftwareKey.fresh())).load().status)
    }

    // A transient Keystore failure looks like a lost key; deleting would make it permanent.
    @Test
    fun anUnreadableBlobIsLeftForTheNextSaveToReplace() = runBlocking {
        val key = SoftwareKey()
        store(key).save(ring)
        store(SoftwareKey(SoftwareKey.fresh())).load()
        assertTrue(file.exists())
        assertEquals(KeyringState.Status.STORED, store(key).load().status)
    }

    @Test
    fun decryptedJunkMigratesToAnEmptyKeyring() = runBlocking {
        val key = SoftwareKey()
        for ((plain, status) in listOf("[1,2]" to KeyringState.Status.STORED, "not json" to KeyringState.Status.UNREADABLE)) {
            file.parentFile!!.mkdirs()
            file.writeBytes(KeyringStore.encrypt(key.getOrCreate(), plain.toByteArray()))
            val state = store(key).load()
            assertEquals(plain, status, state.status)
            assertEquals(emptyKeyring(), state.ring)
        }
    }

    // saveCredentials migrates a v1 blob on the way in, so nothing writes a shape the loader
    // has to guess at.
    @Test
    fun savingAV1BlobStoresItsV2Form() = runBlocking {
        val key = SoftwareKey()
        val v1 = obj("kind" to "groq", "apiKey" to "gsk-1", "sttKind" to "openai", "sttKey" to "sk-2")
        val saved = store(key).save(v1)
        assertEquals(migrateCredentials(v1), saved)
        val loaded = store(key).load().ring
        assertEquals(saved, loaded)
        assertEquals(JsonPrimitive("groq"), loaded["active"])
    }

    @Test
    fun aKeyThatCannotEncryptIsReplacedOnSave() = runBlocking {
        val key = SoftwareKey(SecretKeySpec(ByteArray(7), "AES")) // an invalid AES key length
        store(key).save(ring)
        assertEquals(1, key.deleted)
        assertEquals(1, key.created)
        assertEquals(ring, store(key).load().ring)
    }

    @Test
    fun forgetDeletesTheBlobTheTempAndTheKey() = runBlocking {
        val key = SoftwareKey()
        val s = store(key)
        s.save(ring)
        File(file.path + ATOMIC_NEW_SUFFIX).writeBytes(byteArrayOf(1, 2, 3)) // an interrupted write
        s.forget()
        assertFalse(file.exists())
        assertFalse(File(file.path + ATOMIC_NEW_SUFFIX).exists())
        assertNull("the key alias survived Forget", key.key)
        assertEquals(1, key.deleted)
        assertEquals(KeyringState.Status.NONE, s.state.value.status)
        assertEquals(KeyringState.Status.NONE, store(key).load().status)
    }

    @Test
    fun forgetWithNothingStoredStillDeletesTheKey() = runBlocking {
        val key = SoftwareKey(SoftwareKey.fresh())
        store(key).forget()
        assertNull(key.key)
    }

    @Test
    fun forgetStillDeletesTheKeyWhenTheFileCannotBeDeleted() = runBlocking {
        val key = SoftwareKey(SoftwareKey.fresh())
        // A non-empty directory where the file should be: deleteIfExists throws.
        File(file, "child").apply { parentFile!!.mkdirs(); writeText("x") }
        val failure = runCatching { store(key).forget() }.exceptionOrNull()
        assertNotNull("a failed delete must be reported", failure)
        assertNull(key.key)
    }

    @Test
    fun stateFollowsSaveAndLoad() = runBlocking {
        val key = SoftwareKey()
        val s = store(key)
        assertEquals(KeyringState.Status.NOT_LOADED, s.state.value.status)
        s.save(ring)
        assertEquals(KeyringState.Status.STORED, s.state.value.status)
        assertEquals(ring, s.state.value.ring)
    }

    @Test
    fun decryptRejectsAnUnknownFormatByte() {
        val key = SoftwareKey.fresh()
        val blob = KeyringStore.encrypt(key, "{}".toByteArray())
        assertArrayEquals("{}".toByteArray(), KeyringStore.decrypt(key, blob))
        blob[0] = 2
        assertTrue(runCatching { KeyringStore.decrypt(key, blob) }.isFailure)
    }
}

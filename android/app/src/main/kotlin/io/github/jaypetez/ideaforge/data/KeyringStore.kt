package io.github.jaypetez.ideaforge.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.jaypetez.ideaforge.core.CREDENTIALS_VERSION
import io.github.jaypetez.ideaforge.core.Keyring
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.jsEq
import io.github.jaypetez.ideaforge.core.migrateCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the keyring's AES key comes from. Production is [AndroidKeystoreKey]; tests inject a
 * software key, because Robolectric and a plain JVM have no AndroidKeyStore. Everything else
 * in [KeyringStore] (the cipher, the IV, the file format) is the same code in both.
 *
 * There are two key slots, [SLOTS]. The blob's header names the slot that sealed it, so a
 * key the Keystore has invalidated can be replaced under the other slot while the old key
 * and the old blob stay intact until the new blob is durable. Deleting the only key that
 * reads the file on disk before its replacement has landed is how a failed save loses
 * every credential.
 */
interface KeyringKey {
    /** The key in [slot] if one exists. Never creates one: decrypting needs the old key or nothing. */
    fun existing(slot: Int): SecretKey?

    fun getOrCreate(slot: Int): SecretKey

    /** Removes the key in [slot]; a no-op when there is none. */
    fun delete(slot: Int)

    companion object {
        val SLOTS = 0..1
    }
}

/**
 * A 256-bit AES key generated inside AndroidKeyStore. It is non-exportable: the app can ask
 * the Keystore to encrypt and decrypt with it, but its bytes never enter this process, so a
 * copy of the app's files (a rooted backup, a disk image) is ciphertext with no key.
 *
 * No user authentication: the key must be usable for every turn of a hands-free interview,
 * with the phone in a car mount, and a lock-screen prompt mid-drive is not an option. The
 * threat this answers is the web's: the stored file, not a malicious process running as us.
 */
class AndroidKeystoreKey(private val alias: String = ALIAS) : KeyringKey {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Slot 0 is [ALIAS]; slot 1, used only to replace an invalidated key, is `ALIAS.b`. */
    fun alias(slot: Int): String {
        require(slot in KeyringKey.SLOTS) { "no key slot $slot" }
        return if (slot == 0) alias else "$alias.b"
    }

    override fun existing(slot: Int): SecretKey? = keyStore().getKey(alias(slot), null) as? SecretKey

    override fun getOrCreate(slot: Int): SecretKey = existing(slot)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec(alias(slot)))
            generateKey()
        }

    override fun delete(slot: Int) {
        val ks = keyStore()
        if (ks.containsAlias(alias(slot))) ks.deleteEntry(alias(slot))
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** Versioned so a future change of key parameters can mint a new alias beside it. */
        const val ALIAS = "ideaforge.keyring.v1"

        /**
         * Randomized encryption stays required (the default): the Keystore then refuses a
         * caller-chosen IV and generates a fresh one per encryption, so IV reuse under one
         * GCM key, which would forfeit both confidentiality and integrity, cannot happen.
         */
        fun spec(alias: String = ALIAS): KeyGenParameterSpec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
    }
}

/** What the store last found, so Settings can tell "never saved" from "could not read". */
data class KeyringState(val ring: Keyring, val status: Status, val problem: String? = null) {
    enum class Status {
        /** [KeyringStore.load] has not run yet. */
        NOT_LOADED,

        /** Nothing is stored. */
        NONE,

        /** A keyring was decrypted (or just saved). */
        STORED,

        /**
         * A blob exists but could not be decrypted or parsed: the key is gone, the file was
         * damaged, or it is not ours. [ring] is empty, exactly as if nothing were stored, and
         * [problem] says why.
         */
        UNREADABLE,
    }
}

/**
 * The credential keyring at rest: the v2 JSON of src/store/secrets.js, encrypted with
 * AES-256-GCM under [key] and written atomically to one app-private file.
 *
 * The file belongs in `noBackupFilesDir`, and the manifest turns Android backup off besides:
 * a keyring restored to another device is ciphertext whose Keystore key stayed behind.
 *
 * File format: `[FORMAT_V1][key slot][12-byte IV][ciphertext || 128-bit tag]`, the first two
 * bytes authenticated as GCM associated data.
 *
 * Never the session backup. Backups contain sessions and nothing else (CLAUDE.md); nothing
 * here is reachable from [io.github.jaypetez.ideaforge.core.buildBackup].
 */
class KeyringStore(
    val file: File,
    private val key: KeyringKey,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // A seam for tests that need the write itself to fail (a full disk). Production always
    // uses [writeAtomically].
    private val writer: (File, ByteArray) -> Unit = ::writeAtomically,
    // A seam for tests that need reading the file to fail (an I/O error). Production reads it.
    private val reader: (File) -> ByteArray = File::readBytes,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(KeyringState(emptyKeyring(), KeyringState.Status.NOT_LOADED))
    val state: StateFlow<KeyringState> = _state.asStateFlow()

    /**
     * The credential generation: the web's credentialEpoch. [revoke] bumps it the moment a
     * Forget begins, before anything is deleted, and [forget] bumps it again under the lock.
     * Work that began under an older generation (a speech check, a transcription save, a
     * hosted request) must not write or spend with what it read: [saveIf] refuses it.
     */
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /** Forget is starting: invalidate every credential read before now, synchronously. */
    fun revoke() { _generation.update { it + 1 } }

    /**
     * [save], only if no Forget began since [generation] was read. Checked under the same lock
     * Forget takes, so a check cannot land between Forget's revoke and its delete. False when
     * refused; nothing is written.
     */
    suspend fun saveIf(generation: Long, ring: JsonElement?): Boolean {
        mutex.withLock {
            if (_generation.value != generation) return false
            saveLocked(ring)
        }
        return true
    }

    /**
     * Read and decrypt. Never throws: an unreadable blob reads as an empty keyring with
     * [KeyringState.Status.UNREADABLE], because an app that will not start over a credential
     * it could re-ask for is worse than one that asks.
     *
     * Unlike the web, an unreadable blob is not deleted here. A Keystore that is briefly
     * unavailable (it is a system service, and some devices fail it transiently after an
     * update) looks the same as a lost key from in here, and deleting would turn a hiccup
     * into a lost credential. The next [save] replaces the blob, and [forget] removes it.
     */
    suspend fun load(): KeyringState = mutex.withLock {
        val next = withContext(io) { read() }
        _state.value = next
        next
    }

    private fun read(): KeyringState {
        if (!file.isFile) return KeyringState(emptyKeyring(), KeyringState.Status.NONE)
        return try {
            val blob = reader(file)
            val slot = slotOf(blob)
            val k = key.existing(slot) ?: throw GeneralSecurityException("the keyring's key (slot $slot) is missing")
            val plain = decrypt(k, blob)
            val text = try { plain.decodeToString() } finally { plain.fill(0) }
            KeyringState(migrateCredentials(Json.parseToJsonElement(text)), KeyringState.Status.STORED)
        } catch (e: Exception) {
            KeyringState(emptyKeyring(), KeyringState.Status.UNREADABLE, e.javaClass.simpleName + ": " + e.message)
        }
    }

    /**
     * Encrypt and write [ring]; a v1 blob is migrated on the way in, as saveCredentials does,
     * so no call site can write a shape the loader would have to guess at. Throws on a failed
     * write: "your key was not saved" must reach the user.
     *
     * A key the Keystore has permanently invalidated (an [InvalidKeyException], which
     * KeyPermanentlyInvalidatedException is) is replaced under the other slot. The old key is
     * never deleted here: until the directory entry is durable a power cut can revert the
     * rename to the old blob, which only the old key reads. A stale key is cleared only when
     * a later replacement needs its slot, or by [forget]. If anything fails, the file on disk
     * and the key that reads it are untouched and the state still describes them. Any other
     * failure (a Keystore that is busy or briefly away, an unreadable file header) is reported
     * and touches no key at all.
     */
    suspend fun save(ring: JsonElement?): Keyring = mutex.withLock { saveLocked(ring) }

    private suspend fun saveLocked(ring: JsonElement?): Keyring {
        val payload = if (ring is JsonObject && jsEq(ring["version"], CREDENTIALS_VERSION)) ring else migrateCredentials(ring)
        val result = withContext(NonCancellable + io) {
            val plain = Json.encodeToString(JsonElement.serializer(), payload).encodeToByteArray()
            val current = currentSlot()
            var slot = current
            val blob = try {
                try {
                    encrypt(key.getOrCreate(current), plain, current)
                } catch (e: InvalidKeyException) {
                    // The blob on disk names `current` (read reliably, or there is no blob any
                    // key reads), so the other slot holds nothing anyone reads: clearing it
                    // first is safe, and gives a fresh key.
                    slot = 1 - current
                    key.delete(slot)
                    encrypt(key.getOrCreate(slot), plain, slot)
                }
            } finally {
                plain.fill(0)
            }
            writer(file, blob)
            val saved = migrateCredentials(payload)
            _state.value = KeyringState(saved, KeyringState.Status.STORED)
            saved
        }
        return result
    }

    /**
     * The slot that sealed the blob on disk. 0 when there is no file or its format is not one
     * this build wrote: no key reads such a file, so either slot is free to replace.
     *
     * An I/O error is NOT read as 0. With the blob on slot 1 and a stale key in slot 0,
     * guessing 0 would hit the stale key, "replace" it into slot 1 and delete the live key.
     * The error propagates and fails the save before any key is touched.
     */
    private fun currentSlot(): Int {
        if (!file.exists()) return 0
        val header = reader(file)
        val ok = header.size >= HEADER_BYTES && header[0] == FORMAT_V1 && header[1].toInt() in KeyringKey.SLOTS
        return if (ok) header[1].toInt() else 0
    }

    /** Delete the blob AND every key alias. Both are attempted even if the first fails. */
    suspend fun forget(): Unit = mutex.withLock {
        _generation.update { it + 1 }
        withContext(NonCancellable + io) {
            var failure: Throwable? = null
            try {
                Files.deleteIfExists(file.toPath())
                Files.deleteIfExists(File(file.path + ATOMIC_NEW_SUFFIX).toPath())
            } catch (e: IOException) {
                failure = e
            }
            for (slot in KeyringKey.SLOTS) {
                try {
                    key.delete(slot)
                } catch (e: Exception) {
                    failure = failure ?: e
                }
            }
            failure?.let { throw it }
            _state.value = KeyringState(emptyKeyring(), KeyringState.Status.NONE)
        }
    }

    companion object {
        const val FILE_NAME = "keyring.bin"
        const val FORMAT_V1: Byte = 1
        /** Format byte, then key slot. */
        const val HEADER_BYTES = 2
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * The IV is never chosen here: the cipher generates it (AndroidKeyStore insists, and
         * the JVM provider does the same when given none), so every write gets a fresh one.
         */
        internal fun encrypt(key: SecretKey, plain: ByteArray, slot: Int = 0): ByteArray {
            require(slot in KeyringKey.SLOTS) { "no key slot $slot" }
            val header = byteArrayOf(FORMAT_V1, slot.toByte())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected GCM IV length ${iv?.size}")
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(plain)
            return ByteArray(HEADER_BYTES + IV_BYTES + sealed.size).also { out ->
                header.copyInto(out, 0)
                iv.copyInto(out, HEADER_BYTES)
                sealed.copyInto(out, HEADER_BYTES + IV_BYTES)
            }
        }

        /** The key slot a blob names. Throws on a blob too short or of an unknown format. */
        internal fun slotOf(blob: ByteArray): Int {
            if (blob.size < HEADER_BYTES + IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("keyring file is truncated")
            if (blob[0] != FORMAT_V1) throw GeneralSecurityException("unknown keyring format ${blob[0]}")
            val slot = blob[1].toInt()
            if (slot !in KeyringKey.SLOTS) throw GeneralSecurityException("unknown key slot $slot")
            return slot
        }

        /** Throws on anything that is not an intact blob sealed by [key]. */
        internal fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
            slotOf(blob)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, HEADER_BYTES, IV_BYTES))
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            return cipher.doFinal(blob, HEADER_BYTES + IV_BYTES, blob.size - HEADER_BYTES - IV_BYTES)
        }
    }
}

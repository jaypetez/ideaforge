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
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the keyring's AES key comes from. Production is [AndroidKeystoreKey]; tests inject a
 * software key, because Robolectric and a plain JVM have no AndroidKeyStore. Everything else
 * in [KeyringStore] (the cipher, the IV, the file format) is the same code in both.
 */
interface KeyringKey {
    /** The key if one exists. Never creates one: decrypting needs the old key or nothing. */
    fun existing(): SecretKey?

    fun getOrCreate(): SecretKey

    /** Removes the key; a no-op when there is none. */
    fun delete()
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

    override fun existing(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    override fun getOrCreate(): SecretKey = existing()
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec(alias))
            generateKey()
        }

    override fun delete() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
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
 * File format: `[FORMAT_V1][12-byte IV][ciphertext || 128-bit tag]`.
 *
 * Never the session backup. Backups contain sessions and nothing else (CLAUDE.md); nothing
 * here is reachable from [io.github.jaypetez.ideaforge.core.buildBackup].
 */
class KeyringStore(
    val file: File,
    private val key: KeyringKey,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(KeyringState(emptyKeyring(), KeyringState.Status.NOT_LOADED))
    val state: StateFlow<KeyringState> = _state.asStateFlow()

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
            val k = key.existing() ?: throw GeneralSecurityException("the keyring's key is missing")
            val plain = decrypt(k, file.readBytes())
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
     * If the existing key cannot encrypt at all (the Keystore invalidated it), it is replaced
     * and the write retried once. Nothing is lost by that: this write supersedes the whole
     * blob the old key protected.
     */
    suspend fun save(ring: JsonElement?): Keyring = mutex.withLock {
        val payload = if (ring is JsonObject && jsEq(ring["version"], CREDENTIALS_VERSION)) ring else migrateCredentials(ring)
        withContext(NonCancellable + io) {
            val plain = Json.encodeToString(JsonElement.serializer(), payload).encodeToByteArray()
            val blob = try {
                try {
                    encrypt(key.getOrCreate(), plain)
                } catch (e: GeneralSecurityException) {
                    key.delete()
                    encrypt(key.getOrCreate(), plain)
                } catch (e: ProviderException) {
                    key.delete()
                    encrypt(key.getOrCreate(), plain)
                }
            } finally {
                plain.fill(0)
            }
            writeAtomically(file, blob)
            val saved = migrateCredentials(payload)
            _state.value = KeyringState(saved, KeyringState.Status.STORED)
            saved
        }
    }

    /** Delete the blob AND the key alias. Both are attempted even if the first fails. */
    suspend fun forget(): Unit = mutex.withLock {
        withContext(NonCancellable + io) {
            var failure: Throwable? = null
            try {
                Files.deleteIfExists(file.toPath())
                Files.deleteIfExists(File(file.path + ATOMIC_NEW_SUFFIX).toPath())
            } catch (e: IOException) {
                failure = e
            }
            try {
                key.delete()
            } catch (e: Exception) {
                failure = failure ?: e
            }
            failure?.let { throw it }
            _state.value = KeyringState(emptyKeyring(), KeyringState.Status.NONE)
        }
    }

    companion object {
        const val FILE_NAME = "keyring.bin"
        const val FORMAT_V1: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * The IV is never chosen here: the cipher generates it (AndroidKeyStore insists, and
         * the JVM provider does the same when given none), so every write gets a fresh one.
         */
        internal fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected GCM IV length ${iv?.size}")
            val sealed = cipher.doFinal(plain)
            return ByteArray(1 + IV_BYTES + sealed.size).also { out ->
                out[0] = FORMAT_V1
                iv.copyInto(out, 1)
                sealed.copyInto(out, 1 + IV_BYTES)
            }
        }

        /** Throws on anything that is not an intact blob sealed by [key]. */
        internal fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
            if (blob.size < 1 + IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("keyring file is truncated")
            if (blob[0] != FORMAT_V1) throw GeneralSecurityException("unknown keyring format ${blob[0]}")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
            return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        }
    }
}

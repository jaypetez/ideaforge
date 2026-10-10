package io.github.jaypetez.ideaforge.data

import java.security.SecureRandom

/** Wall time in epoch milliseconds. Injected everywhere; only [System] reads the real clock. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Session ids in the web format, `s_<base36 epoch ms>_<8 lowercase hex>`, so a backup made
 * on either platform imports on the other without its ids looking foreign. The web takes
 * the suffix from `crypto.randomUUID().slice(0, 8)` (src/store/sessions.js, newSessionId):
 * eight hex digits, which is four random bytes.
 */
class IdGen(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    fun newSessionId(): String {
        val bytes = ByteArray(4).also(random::nextBytes)
        val suffix = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "s_${clock.now().toString(36)}_$suffix"
    }
}

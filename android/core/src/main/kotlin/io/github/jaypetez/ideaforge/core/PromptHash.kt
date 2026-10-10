package io.github.jaypetez.ideaforge.core

/**
 * Stable non-cryptographic hash of a prompt (FNV-1a, 32-bit), the Kotlin twin of
 * `promptHash` in src/core/engine.js. An interrupted turn proves it is resuming the same
 * prompt by comparing these, so it must agree with the JS bit for bit:
 *
 * - it walks UTF-16 code units, as JS `charCodeAt` does, not code points;
 * - the multiply is `Math.imul` followed by `>>> 0`, i.e. a 32-bit wrap read as unsigned;
 * - the result is written in base 36, as `Number.prototype.toString(36)` writes it.
 */
fun promptHash(str: String): String {
    var h = 0x811c9dc5.toInt()
    for (unit in str) {
        h = h xor unit.code
        h *= 0x01000193
    }
    return (h.toLong() and 0xffffffffL).toString(36)
}

/**
 * UTF-8 byte length, the twin of `utf8Length` in src/core/digest.js. A lone surrogate counts
 * as three bytes, because JS `codePointAt` returns it unchanged and the JS falls into the
 * `<= 0xffff` branch; `Char`-walking here reproduces that exactly.
 */
fun utf8Length(str: String): Int {
    var bytes = 0
    var i = 0
    while (i < str.length) {
        val c = str.codePointAt(i)
        when {
            c <= 0x7f -> bytes += 1
            c <= 0x7ff -> bytes += 2
            c <= 0xffff -> bytes += 3
            else -> { bytes += 4; i++ }
        }
        i++
    }
    return bytes
}

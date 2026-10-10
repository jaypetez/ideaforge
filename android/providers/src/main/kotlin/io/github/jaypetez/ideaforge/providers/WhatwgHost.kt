package io.github.jaypetez.ideaforge.providers

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN

/**
 * Just enough of the WHATWG URL parser to answer `new URL(u).hostname` and `.host` the way
 * a browser does, for [isLoopback] and [hostOf]. java.net.URI and OkHttp's HttpUrl both
 * disagree with the browser where it matters here: neither normalises `127.1` to
 * `127.0.0.1`, and neither treats a backslash as a path separator in http URLs.
 *
 * Not a general URL parser: it returns null for anything it is unsure of, and both callers
 * treat null as "not a URL" (false, or the input unchanged), which is the safe direction.
 */
internal data class WhatwgHost(val hostname: String, val port: String) {
    /** `URL.host`: the hostname plus a non-default port. */
    val host: String get() = if (port.isEmpty()) hostname else "$hostname:$port"

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
        private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443", "ws" to "80", "wss" to "443", "ftp" to "21")
        private const val FORBIDDEN_HOST = " #%/:<>?@[\\]^|"

        fun parse(input: String): WhatwgHost? {
            // Leading/trailing C0 controls and spaces are stripped; tabs and newlines anywhere.
            val s = input.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
            val m = SCHEME.find(s) ?: return null
            val scheme = m.groupValues[1].lowercase()
            var rest = s.substring(m.value.length)
            val special = scheme in DEFAULT_PORTS || scheme == "file"
            if (special) {
                rest = rest.trimStart('/', '\\')
                if (scheme == "file") {
                    // file:///etc/passwd has an empty host; only file://host/... has one.
                    val afterScheme = s.substring(m.value.length)
                    if (!afterScheme.startsWith("//") || afterScheme.startsWith("///")) return WhatwgHost("", "")
                }
            } else {
                if (!rest.startsWith("//")) return WhatwgHost("", "")
                rest = rest.substring(2)
            }
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || (special && it == '\\') }
            var authority = if (end == -1) rest else rest.substring(0, end)
            authority = authority.substring(authority.lastIndexOf('@') + 1)

            val rawHost: String
            val rawPort: String
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close == -1) return null
                rawHost = authority.substring(0, close + 1)
                val after = authority.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                rawPort = after.removePrefix(":")
            } else {
                val colon = authority.indexOf(':')
                rawHost = if (colon == -1) authority else authority.substring(0, colon)
                rawPort = if (colon == -1) "" else authority.substring(colon + 1)
            }
            if (rawPort.isNotEmpty() && (!rawPort.all { it in '0'..'9' } || rawPort.toBigInteger() > 65535.toBigInteger())) return null
            val port = rawPort.takeIf { it.isNotEmpty() }?.toInt()?.toString()?.takeIf { it != DEFAULT_PORTS[scheme] } ?: ""

            if (!special) return WhatwgHost(rawHost, port)   // an opaque host keeps its case
            if (rawHost.isEmpty()) return if (scheme == "file") WhatwgHost("", "") else null
            val hostname = if (rawHost.startsWith("[")) ipv6(rawHost) else domain(rawHost)
            return hostname?.let { WhatwgHost(it, port) }
        }

        /** A bracketed IPv6 literal, serialised the way URL does (compressed, lowercase). */
        private fun ipv6(bracketed: String): String? {
            val inner = bracketed.substring(1, bracketed.length - 1)
            if (inner.isEmpty() || !inner.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
            // OkHttp compresses exactly as URL does, except that it renders an IPv4-mapped
            // address as dotted IPv4; URL keeps it as hex, [::ffff:7f00:1].
            return "http://[$inner]/".toHttpUrlOrNull()?.host?.let { h ->
                if (':' in h) return "[$h]"
                val b = h.split('.').map { it.toInt() }
                "[::ffff:%x:%x]".format((b[0] shl 8) or b[1], (b[2] shl 8) or b[3])
            }
        }

        private fun domain(raw: String): String? {
            val decoded = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()
                ?: return null
            val ascii = runCatching { IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
            if (ascii.isEmpty() || ascii.any { it in FORBIDDEN_HOST || it < ' ' || it == '\u007f' }) return null
            if (endsInANumber(ascii)) return ipv4(ascii)
            return ascii
        }

        private fun endsInANumber(host: String): Boolean {
            val parts = host.split('.').toMutableList()
            if (parts.last().isEmpty()) {
                if (parts.size == 1) return false
                parts.removeAt(parts.size - 1)
            }
            val last = parts.last()
            if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
            return ipv4Number(last) != null
        }

        /** The WHATWG IPv4 parser: 1 to 4 parts, each decimal, 0x-hex or 0-octal. */
        private fun ipv4(host: String): String? {
            val parts = host.split('.').toMutableList()
            if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.size - 1)
            if (parts.size > 4) return null
            val numbers = parts.map { ipv4Number(it) ?: return null }
            if (numbers.dropLast(1).any { it > 255 }) return null
            val limit = java.math.BigInteger.valueOf(256).pow(5 - numbers.size)
            if (numbers.last().toBigInteger() >= limit) return null
            var value = numbers.last()
            numbers.dropLast(1).forEachIndexed { i, n -> value += n shl (8 * (3 - i)) }
            return (3 downTo 0).joinToString(".") { ((value shr (8 * it)) and 0xFF).toString() }
        }

        private fun ipv4Number(part: String): Long? {
            if (part.isEmpty()) return null
            var p = part
            var radix = 10
            if (p.length >= 2 && (p.startsWith("0x") || p.startsWith("0X"))) { p = p.substring(2); radix = 16 }
            else if (p.length >= 2 && p.startsWith("0")) { p = p.substring(1); radix = 8 }
            if (p.isEmpty()) return 0
            // Overlong values are rejected rather than overflowing; a real address never needs them.
            if (p.length > 12) return null
            return p.toLongOrNull(radix)?.takeIf { it >= 0 && p.all { c -> c.isLetterOrDigit() } }
        }
    }
}

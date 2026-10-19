package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The native replacement for the browser's `assertLoopback`, and the only definition of
 * "local" on Android.
 *
 * ## Why the browser rule does not carry over
 *
 * In the web app the CSP allowlist is what stands between a stored key and an injected script,
 * so a server the user types may only be on their own machine: loopback, nothing else. On a
 * phone the model server is almost never on the same device. It is on the user's desktop
 * (`192.168.1.20`), behind Tailscale (`100.x`, CGNAT), reached from the emulator through
 * `10.0.2.2`, or forwarded with `adb reverse tcp:11434 tcp:11434` to the phone's own
 * loopback. So the native rule is "on a private network", not "on this machine".
 *
 * ## The rule
 *
 * - **Hosted presets** use their fixed `https` URLs only ([NetworkPolicy.Hosted]); nothing
 *   typed by the user ever reaches a hosted client.
 * - **Local providers** (Ollama, LM Studio, another local server) may reach:
 *   loopback `127/8` and `::1`; RFC 1918 `10/8`, `172.16/12`, `192.168/16`; CGNAT
 *   `100.64/10`; link-local `169.254/16` and `fe80::/10`; IPv6 ULA `fc00::/7`; and the names
 *   `localhost`, `*.localhost` and mDNS `*.local`. Nothing else — `localhost.evil.com` is a
 *   public name whatever it starts with.
 * - **Local providers never attach a key** ([NetworkPolicy.carriesCredentials]). A server
 *   on the LAN that wants one is not a local model server.
 *
 * ## Where it is enforced
 *
 * Checking the URL is not enough: a `.local` name, or any name, is only as private as what it
 * resolves to, and a hostile resolver can answer "private" to a pre-flight check and "public"
 * to the connection (DNS rebinding). So the gate is [LocalNetworkDns]: OkHttp connects to
 * exactly the addresses a `Dns` returns, and this one refuses the whole lookup if any
 * resolved address is public. The check and the use are the same list, so there is nothing
 * to rebind between them.
 *
 * OkHttp does not consult `Dns` for an IP literal, which is why [check] validates literals
 * itself, and why numeric spellings it cannot classify (`127.1`, `0x7f.1`, which Java would
 * still connect to) are refused rather than guessed at.
 *
 * The app's network_security_config permits cleartext because a LAN model server speaks
 * plain http; that file is not the gate, this is.
 */
object LocalNetworkPolicy {
    /** Presentation only: [check] still decides whether the request is permitted. */
    fun needsCleartextWarning(url: HttpUrl): Boolean {
        if (url.isHttps) return false
        val normalized = url.newBuilder().host(url.host.removeSuffix(".")).build()
        if (isLoopback(normalized.toString())) return false
        val mappedLoopback = ':' in url.host && literal("[${url.host}]")?.isLoopbackAddress == true
        return !mappedLoopback
    }

    /** True for an address inside one of the private ranges listed above. */
    fun isLocalAddress(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 0xFF }
        return when (b.size) {
            4 -> isLocalV4(b)
            16 -> when {
                b.subList(0, 15).all { it == 0 } && b[15] == 1 -> true                 // ::1
                b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF ->
                    isLocalV4(b.subList(12, 16))                                          // ::ffff:a.b.c.d
                (b[0] and 0xFE) == 0xFC -> true                                           // fc00::/7
                b[0] == 0xFE && (b[1] and 0xC0) == 0x80 -> true                           // fe80::/10
                else -> false
            }
            else -> false
        }
    }

    private fun isLocalV4(b: List<Int>): Boolean = when {
        b[0] == 127 -> true
        b[0] == 10 -> true
        b[0] == 172 && b[1] in 16..31 -> true
        b[0] == 192 && b[1] == 168 -> true
        b[0] == 100 && b[1] in 64..127 -> true
        b[0] == 169 && b[1] == 254 -> true
        else -> false
    }

    /** A name the policy will even try to resolve. Prefix matching is exactly the old bug. */
    fun isLocalName(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        return h == "localhost" || h.endsWith(".localhost") || (h.endsWith(".local") && h.length > ".local".length)
    }

    /**
     * Refuse a URL a local provider may not use, as a `config` error the settings screen can
     * show. Names that pass still have to resolve privately in [LocalNetworkDns].
     */
    fun check(url: HttpUrl) {
        val host = url.host
        val ok = when {
            ':' in host -> literal("[$host]")?.let(::isLocalAddress) ?: false
            host.all { it.isDigit() || it == '.' } -> dottedQuad(host)?.let(::isLocalAddress) ?: false
            else -> isLocalName(host)
        }
        if (!ok) throw ProviderException(ProviderErrorCode.CONFIG, refusal(host))
    }

    internal fun refusal(host: String) =
        "$host is not on a private network. A local server has to be at localhost, a private " +
            "address (10.x, 172.16-31.x, 192.168.x, 100.64-127.x, 169.254.x, fc00::/7, fe80::/10) " +
            "or a .local name, which is what stops a request meant for your own server reaching the internet."

    /** An IPv6 literal in brackets; InetAddress parses these without any lookup. */
    private fun literal(bracketed: String): InetAddress? =
        runCatching { InetAddress.getByName(bracketed) }.getOrNull()

    /** Strict a.b.c.d only; any other numeric spelling is refused, never interpreted. */
    private fun dottedQuad(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { p ->
            if (p.isEmpty() || p.length > 3 || (p.length > 1 && p[0] == '0')) return null
            p.toInt().takeIf { it <= 255 } ?: return null
        }
        return InetAddress.getByAddress(host, ByteArray(4) { bytes[it].toByte() })
    }
}

/**
 * The local-network guard as resolution. Every lookup a local provider's client makes goes
 * through here: the name must be one [LocalNetworkPolicy.isLocalName] accepts, and every
 * address [delegate] returns must be private, or the whole lookup is refused.
 */
class LocalNetworkDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!LocalNetworkPolicy.isLocalName(hostname)) throw LocalNetworkRefused(LocalNetworkPolicy.refusal(hostname))
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("$hostname did not resolve")
        val public = addresses.filterNot(LocalNetworkPolicy::isLocalAddress)
        if (public.isNotEmpty()) {
            throw LocalNetworkRefused(
                "$hostname resolves to ${public.joinToString { it.hostAddress }}, which is not on a private network. " +
                    "A local server may only be reached at a private address.",
            )
        }
        return addresses
    }
}

/**
 * The guard's refusal. An UnknownHostException because that is what `Dns.lookup` may throw;
 * its own type so [callFailure] reports it as `config` rather than a retryable `network`.
 */
class LocalNetworkRefused(message: String) : UnknownHostException(message)

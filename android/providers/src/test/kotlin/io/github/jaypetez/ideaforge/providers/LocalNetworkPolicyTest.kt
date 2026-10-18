package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A resolver that answers from a table and never touches the network. */
private class FakeDns(private val table: Map<String, List<String>>) : Dns {
    val asked = mutableListOf<String>()
    override fun lookup(hostname: String): List<InetAddress> {
        asked += hostname
        val ips = table[hostname] ?: throw UnknownHostException(hostname)
        // getByAddress builds the InetAddress from bytes: no lookup, even for the name.
        return ips.map { ip -> InetAddress.getByAddress(hostname, ipBytes(ip)) }
    }
}

private fun ipBytes(ip: String): ByteArray =
    if (':' in ip) InetAddress.getByName("[$ip]").address.let { if (it.size == 4) v4mapped(it) else it }
    else ip.split('.').map { it.toInt().toByte() }.toByteArray()

private fun v4mapped(v4: ByteArray) = ByteArray(10) + byteArrayOf(-1, -1) + v4

private fun addr(ip: String): InetAddress = InetAddress.getByAddress(ipBytes(ip))

class LocalNetworkPolicyTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun start() { server = MockWebServer(); server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
    @AfterTest fun stop() { server.close() }

    @Test
    fun privateRangesAreLocal() {
        for (ip in listOf(
            "127.0.0.1", "127.255.0.9", "10.0.2.2", "10.255.255.255", "172.16.0.1", "172.31.255.255",
            "192.168.1.20", "100.64.0.1", "100.127.255.254", "169.254.1.1",
            "::1", "fc00::1", "fd12:3456::1", "fe80::1", "febf::1", "::ffff:192.168.0.5",
        )) assertTrue(LocalNetworkPolicy.isLocalAddress(addr(ip)), ip)
    }

    @Test
    fun publicAndEdgeAddressesAreNot() {
        for (ip in listOf(
            "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.0", "192.169.0.1", "100.63.255.255",
            "100.128.0.0", "169.253.0.1", "11.0.0.1", "0.0.0.0", "255.255.255.255",
            "2001:4860:4860::8888", "fec0::1", "::", "::2", "::ffff:8.8.8.8", "64:ff9b::a00:1",
        )) assertFalse(LocalNetworkPolicy.isLocalAddress(addr(ip)), ip)
    }

    @Test
    fun namesAreMatchedWholeNeverByPrefix() {
        for (h in listOf("localhost", "LOCALHOST", "a.localhost", "ollama.local", "Desk-Top.LOCAL", "studio.local."))
            assertTrue(LocalNetworkPolicy.isLocalName(h), h)
        for (h in listOf("localhost.evil.com", "local", ".local", "evil.com", "notlocal", "local.evil.com", "localhostx"))
            assertFalse(LocalNetworkPolicy.isLocalName(h), h)
    }

    @Test
    fun urlCheckRefusesPublicHostsAndOddNumericSpellings() {
        for (u in listOf("http://10.0.2.2:11434", "http://192.168.1.20:1234/v1", "http://[::1]:11434",
            "http://[fe80::1]/", "http://ollama.local:11434", "http://localhost:11434",
            "https://192.168.1.20:1234/v1", "https://ollama.local:11434")) {
            LocalNetworkPolicy.check(u.toHttpUrl())
        }
        for (u in listOf("http://localhost.evil.com/v1", "http://8.8.8.8/", "http://[2001:db8::1]/",
            "http://127.1/", "http://0x7f.0.0.1/", "http://010.0.0.1/", "http://evil.com/", "https://api.openai.com/v1")) {
            val e = assertFailsWith<ProviderException>(u) { LocalNetworkPolicy.check(u.toHttpUrl()) }
            assertEquals("config", e.code, u)
        }
    }

    @Test
    fun cleartextWarningsDistinguishLoopbackFromPrivateNetworkTraffic() {
        val loopback = listOf(
            "http://localhost:11434", "http://LOCALHOST.:11434", "http://model.localhost:11434",
            "http://model.localhost.:11434", "http://127.0.0.1", "http://127.255.255.254",
            "http://[::1]", "http://[::ffff:127.0.0.1]",
        )
        val network = listOf(
            "http://10.0.2.2:11434", "http://172.16.0.1", "http://192.168.1.20",
            "http://100.64.0.1", "http://169.254.1.1", "http://[fd12:3456::1]",
            "http://[fe80::1]", "http://[::ffff:192.168.0.5]", "http://ollama.local",
        )
        for (text in loopback + network) {
            val url = text.toHttpUrl()
            LocalNetworkPolicy.check(url)
            assertEquals(text in network, LocalNetworkPolicy.needsCleartextWarning(url), text)
            assertFalse(LocalNetworkPolicy.needsCleartextWarning(url.newBuilder().scheme("https").build()), text)
        }
    }

    @Test
    fun aPrivateHostnameConnectsOverTrustedHttpsThroughTheRealLocalClient() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("ollama.local")
            .addSubjectAlternativeName("ollama.local").build()
        val serving = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { secure ->
            secure.useHttps(serving.sslSocketFactory())
            secure.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            secure.enqueue(MockResponse.Builder().body("{}").build())
            val dns = FakeDns(mapOf("ollama.local" to listOf("127.0.0.1")))
            val base = OkHttpClient.Builder().sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager).build()
            val http = ProviderHttp(NetworkPolicy.Local, ProviderHttpClients.create(NetworkPolicy.Local, base = base, dns = dns))
            val url = "https://ollama.local:${secure.port}/api/chat"
            LocalNetworkPolicy.check(url.toHttpUrl())
            http.execute(http.request(url, AUTH_BEARER, "scripted-key-must-not-leave").build(), "Ollama").use {
                assertEquals(200, it.code)
                assertNotNull(it.handshake)
            }
            val request = secure.takeRequest()
            assertNotNull(request.handshake)
            assertNull(request.headers["authorization"])
            assertEquals(listOf("ollama.local"), dns.asked)

            secure.enqueue(MockResponse.Builder().body("{}").build())
            val untrusted = localHttp(FakeDns(mapOf("ollama.local" to listOf("127.0.0.1"))))
            val refused = assertFailsWith<ProviderException> {
                untrusted.execute(untrusted.request(url).build(), "Ollama")
            }
            assertEquals("network", refused.code)
            assertTrue(refused.cause is SSLHandshakeException, refused.toString())
        }
    }

    @Test
    fun dnsGuardRefusesAPublicAnswerAndAllowsAPrivateOne() {
        val fake = FakeDns(mapOf(
            "ollama.local" to listOf("192.168.1.20"),
            "printer.local" to listOf("93.184.216.34"),
            "mixed.local" to listOf("192.168.1.20", "8.8.8.8"),
            "localhost" to listOf("127.0.0.1", "::1"),
        ))
        val guard = LocalNetworkDns(fake)
        assertEquals(listOf(addr("192.168.1.20")), guard.lookup("ollama.local"))
        assertEquals(2, guard.lookup("localhost").size)
        assertFailsWith<LocalNetworkRefused> { guard.lookup("printer.local") }
        // One public address in the answer refuses the lot: that is what a rebinding answer looks like.
        assertFailsWith<LocalNetworkRefused> { guard.lookup("mixed.local") }
        // A public NAME is refused before anyone is asked about it.
        assertFailsWith<LocalNetworkRefused> { guard.lookup("localhost.evil.com") }
        assertFalse("localhost.evil.com" in fake.asked)
    }

    @Test
    fun aLocalClientReachesAPrivateNameEndToEnd() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val http = localHttp(FakeDns(mapOf("ollama.local" to listOf("127.0.0.1"))))
        val url = "http://ollama.local:${server.port}/api/chat"
        http.execute(http.request(url).build(), "Ollama").use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aNameResolvingPubliclyIsRefusedBeforeAnyByteIsSent() = runBlocking {
        // The fake answers with a public address for a .local name; the server, on loopback,
        // would have answered if the guard let the connection through to any address.
        val http = localHttp(FakeDns(mapOf("evil.local" to listOf("127.0.0.1", "93.184.216.34"))))
        val e = assertFailsWith<ProviderException> {
            http.execute(http.request("http://evil.local:${server.port}/").build(), "Ollama")
        }
        assertEquals("config", e.code)
        assertTrue("93.184.216.34" in e.message.orEmpty(), e.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun localhostEvilComIsRefusedForALocalProvider() {
        val http = localHttp(FakeDns(mapOf("localhost.evil.com" to listOf("127.0.0.1"))))
        val e = assertFailsWith<ProviderException> { http.request("http://localhost.evil.com:${server.port}/") }
        assertEquals("config", e.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theDnsGuardHoldsEvenIfTheUrlCheckIsBypassed() = runBlocking {
        // A request built by hand skips request(); execute() checks again, and the client's
        // own Dns would refuse it a third time.
        val http = localHttp(FakeDns(mapOf("localhost.evil.com" to listOf("127.0.0.1"))))
        val raw = okhttp3.Request.Builder().url("http://localhost.evil.com:${server.port}/").build()
        assertEquals("config", assertFailsWith<ProviderException> { http.execute(raw, "x") }.code)
        val e = assertFailsWith<java.io.IOException> { http.client.newCall(raw).execute() }
        assertTrue(e is LocalNetworkRefused, e.toString())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aLocalProviderNeverAttachesAKey() {
        val http = localHttp(FakeDns(emptyMap()))
        val req = http.request("http://localhost:11434/v1/chat", AUTH_BEARER, apiKey = "sk-secret",
            headers = mapOf("content-type" to "application/json")).build()
        assertNull(req.header("authorization"))
        val header = http.request("http://localhost:11434/", AuthDescriptor(AuthScheme.HEADER, header = "x-api-key"), "sk-secret").build()
        assertNull(header.header("x-api-key"))
        val query = http.request("http://localhost:11434/", AuthDescriptor(AuthScheme.QUERY, param = "key"), "sk-secret").build()
        assertNull(query.url.queryParameter("key"))
    }

    @Test
    fun executeRefusesAHandBuiltRequestCarryingAKey() = runBlocking {
        val http = localHttp(FakeDns(emptyMap()))
        val url = "http://127.0.0.1:${server.port}/v1/chat"
        for ((name, value) in listOf("Authorization" to "Bearer sk-x", "x-api-key" to "sk-x", "x-goog-api-key" to "k")) {
            val req = okhttp3.Request.Builder().url(url).header(name, value).build()
            val e = assertFailsWith<ProviderException>(name) { http.execute(req, "Ollama") }
            assertEquals("config", e.code, name)
        }
        val query = okhttp3.Request.Builder().url("$url?key=sk-x").build()
        assertEquals("config", assertFailsWith<ProviderException> { http.execute(query, "Ollama") }.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aLocalClientBuiltFromAHostedBaseDropsItsTlsOnlySpecsAndInterceptors() = runBlocking {
        val tagged = okhttp3.Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("x-api-key", "leak").build()) }
        val hosted = ProviderHttpClients.create(NetworkPolicy.Hosted("https://api.openai.com/v1"))
            .newBuilder().addInterceptor(tagged).addNetworkInterceptor(tagged).build()
        val local = ProviderHttpClients.create(NetworkPolicy.Local, base = hosted, dns = FakeDns(emptyMap()))
        assertTrue(local.interceptors.isEmpty() && local.networkInterceptors.isEmpty())
        assertTrue(okhttp3.ConnectionSpec.CLEARTEXT in local.connectionSpecs)
        // And the reverse: a hosted client from a local base is TLS-only again.
        val back = ProviderHttpClients.create(NetworkPolicy.Hosted("https://api.openai.com/v1"), base = local)
        assertTrue(back.connectionSpecs.all { it.isTls })
        // End to end: plain http to a loopback server works through the derived client.
        server.enqueue(MockResponse.Builder().body("{}").build())
        val http = ProviderHttp(NetworkPolicy.Local, local)
        http.execute(http.request("http://127.0.0.1:${server.port}/").build(), "Ollama").use { assertEquals(200, it.code) }
        assertNull(server.takeRequest().headers["x-api-key"])
    }

    @Test
    fun aLocalClientUsesTheGuardAndNoProxy() {
        val client = ProviderHttpClients.create(NetworkPolicy.Local)
        assertTrue(client.dns is LocalNetworkDns)
        assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
    }

    private fun localHttp(dns: Dns) = ProviderHttp(NetworkPolicy.Local, ProviderHttpClients.create(NetworkPolicy.Local, dns = dns))
}

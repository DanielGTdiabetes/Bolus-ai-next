package org.bolusai.next.mfp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * An HTTPS server on the loopback interface of the phone, for the one thing a synthetic `WebResourceResponse` cannot
 * do: answer with a real HTTP redirect (ADR 0032, "Límite de red impuesto por el sistema"). Nothing leaves the phone.
 *
 * Its certificate is self-signed and generated for each server in the Android key store under a synthetic alias that
 * [close] deletes; no key is committed. Nothing trusts it: the test that uses it accepts the certificate error for
 * this server alone.
 *
 * - `/page` is a page that loads the style sheet `/hop`;
 * - `/hop` redirects to `/target` on [TARGET_HOST], another host name of the same server, or to [redirectTo];
 * - `/target` is the style sheet.
 */
internal class LoopbackTlsServer(private val redirectTo: String? = null) : Closeable {
    private val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
    private val workers = Executors.newCachedThreadPool()
    private val socket: SSLServerSocket

    /** Path of every request that completed its handshake and was read, in order. */
    val served = CopyOnWriteArrayList<String>()

    val port: Int get() = socket.localPort
    val pageUrl: String get() = "https://$PAGE_HOST:$port/page"
    val hopUrl: String get() = "https://$PAGE_HOST:$port/hop"
    val targetUrl: String get() = "https://$TARGET_HOST:$port/target"

    init {
        keyStore.deleteEntry(ALIAS)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEY_STORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
                    .build(),
            )
        }.generateKeyPair()
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val chain = arrayOf(keyStore.getCertificate(ALIAS) as X509Certificate)
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(SingleKey(key, chain)), null, null)
        socket = context.serverSocketFactory
            .createServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) as SSLServerSocket
        workers.execute(::accept)
    }

    private fun accept() {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            workers.execute { answer(connection) }
        }
    }

    /** A client that refuses the certificate ends its connection during the handshake: that is not a request. */
    private fun answer(connection: Socket) {
        try {
            connection.use {
                it.soTimeout = 5_000
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
                var header = reader.readLine()
                while (!header.isNullOrEmpty()) header = reader.readLine()
                served += path
                it.getOutputStream().apply {
                    write(response(path).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }
        } catch (_: IOException) {
            // Refused certificate, reset or timeout.
        }
    }

    private fun response(path: String): String = when (path) {
        "/page" -> ok("text/html", "<html><head><link rel=\"stylesheet\" href=\"/hop\"></head><body>page</body></html>")
        "/hop" -> "HTTP/1.1 302 Found\r\nLocation: ${redirectTo ?: targetUrl}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        "/target" -> ok("text/css", "body { color: black; }")
        else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }

    private fun ok(type: String, body: String) =
        "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    override fun close() {
        socket.close()
        workers.shutdownNow()
        keyStore.deleteEntry(ALIAS)
    }

    private class SingleKey(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    companion object {
        const val PAGE_HOST = "localhost"

        /** The loopback address written as a host: for the web view it is a different host from [PAGE_HOST]. */
        const val TARGET_HOST = "127.0.0.1"

        private const val KEY_STORE = "AndroidKeyStore"
        private const val ALIAS = "synthetic-mfp-loopback-test"
    }
}

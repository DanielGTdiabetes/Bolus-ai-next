package org.bolusai.next.mfp

import android.annotation.SuppressLint
import android.net.http.SslError
import android.net.http.X509TrustManagerExtensions
import android.os.Build
import android.security.NetworkSecurityPolicy
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ADR 0032, "Límite de red impuesto por el sistema", without any network.
 *
 * Two things are shown on the phone itself:
 * 1. the platform read the packaged network security configuration as intended: for this app no certificate
 *    authority is trusted by default, cleartext is never permitted, and only the hosts of the link have another trust;
 * 2. why that configuration is needed: when a resource answers with a real HTTP redirect, the web view of this phone
 *    follows it **without** offering the destination to `shouldInterceptRequest`, so the request filter of the
 *    session never sees it. Only a redirect of the page shown is offered, to `shouldOverrideUrlLoading`. A synthetic
 *    `WebResourceResponse` cannot carry a 3xx status, so a loopback HTTPS server answers it.
 *
 * The second point documents the installed web view. If an update of the web view changes it, the redirect tests fail:
 * nothing is less safe then, but ADR 0032 must be brought up to date. That the web view applies the configuration to
 * a host with a publicly valid certificate needs the network and is the opt-in [MfpNetworkBoundaryProbe].
 *
 * The names of the phone itself (`localhost`, loopback addresses) are left out on purpose: Android gives them a
 * configuration of its own that an app cannot fully replace, and those connections never leave the phone.
 */
@RunWith(AndroidJUnit4::class)
class MfpNetworkBoundaryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var server: LoopbackTlsServer? = null
    private var view: WebView? = null

    @After fun close() {
        instrumentation.runOnMainSync { view?.destroy() }
        server?.close()
    }

    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    @Test fun byDefaultTheAppTrustsNoCertificateAuthorityAndNeverAllowsCleartext() {
        assertEquals(0, defaultTrustManager().acceptedIssuers.size)
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        listOf(MfpHosts.DIARY_HOST, MfpHosts.CHALLENGE_HOST, "example.org").forEach {
            assertFalse(it, policy.isCleartextTrafficPermitted(it))
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    @Test fun onlyTheHostsOfTheLinkHaveATrustOfTheirOwn() {
        val trust = X509TrustManagerExtensions(defaultTrustManager())
        fun same(first: String, second: String) = trust.isSameTrustConfiguration(first, second)

        assertTrue(same(MfpHosts.DIARY_HOST, MfpHosts.DOMAIN))
        assertTrue(same(MfpHosts.DIARY_HOST, "api.${MfpHosts.DOMAIN}"))
        assertTrue(same(MfpHosts.DIARY_HOST, MfpHosts.CHALLENGE_HOST))
        listOf(
            "cloudflare.com",
            "sub.${MfpHosts.CHALLENGE_HOST}",
            "not${MfpHosts.DOMAIN}",
            "${MfpHosts.DOMAIN}.example.org",
        ).forEach { outside ->
            assertFalse(outside, same(MfpHosts.DIARY_HOST, outside))
            assertTrue(outside, same("example.org", outside))
        }
    }

    @Test fun aRedirectedResourceIsFollowedWithoutAskingTheRequestFilterAgain() {
        val server = LoopbackTlsServer().also { server = it }
        val client = load(server, server.pageUrl)

        waitUntil { "/target" in server.served }

        assertTrue(server.served.toString(), server.served.containsAll(listOf("/page", "/hop", "/target")))
        assertTrue(client.offered.toString(), client.offered.containsAll(listOf(server.pageUrl, server.hopUrl)))
        assertFalse(
            "The web view now offers a redirect destination to shouldInterceptRequest: update ADR 0032",
            server.targetUrl in client.offered,
        )
    }

    @Test fun aRedirectOfThePageShownIsOfferedBeforeItIsFollowedAndCanBeRefused() {
        val server = LoopbackTlsServer().also { server = it }
        val client = load(server, server.hopUrl)

        waitUntil { server.targetUrl in client.redirects }
        Thread.sleep(SETTLE_MS)

        assertEquals(listOf(server.targetUrl), client.redirects.toList())
        assertEquals(listOf("/hop"), server.served.toList())
    }

    private fun load(server: LoopbackTlsServer, url: String): Recording {
        val client = Recording(listOf(server.pageUrl, server.targetUrl).map { it.substringBeforeLast('/') })
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                webViewClient = client
                loadUrl(url)
            }
        }
        return client
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
    }

    /** Lets every request through, records what each callback was offered and refuses a redirect of the page. */
    private class Recording(private val loopback: List<String>) : WebViewClient() {
        val offered = CopyOnWriteArrayList<String>()
        val redirects = CopyOnWriteArrayList<String>()

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            offered += request.url.toString()
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || !request.isRedirect) return false
            redirects += request.url.toString()
            return true
        }

        /** Only the self-signed certificate of the loopback server of this test is accepted; production never does. */
        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (loopback.any { error.url.startsWith("$it/") }) handler.proceed() else handler.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 1_000L
    }
}

package org.bolusai.next.mfp

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADR 0032, "Límite de red impuesto por el sistema": the checks that need the Internet, so they are **not** part of
 * `verify.ps1 -DeviceTests` and do nothing unless asked for:
 *
 * ```
 * adb shell am instrument -w -e mfpNetworkProbe true -e class org.bolusai.next.mfp.MfpNetworkBoundaryProbe \
 *     org.bolusai.next.test/org.bolusai.next.ProfileIsolationTestRunner
 * ```
 *
 * They show that the web view itself, with no request filter at all, applies the network security configuration to
 * real servers: a host outside the link with a publicly valid certificate is refused as untrusted, also when a
 * resource is redirected to it and the filter never sees the destination, and the challenge host of the link still
 * loads. Nothing here contacts MyFitnessPal, sends a cookie of its session or uses [MfpWebSession]: each test makes
 * at most one anonymous connection to a public address.
 */
@RunWith(AndroidJUnit4::class)
class MfpNetworkBoundaryProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var server: LoopbackTlsServer? = null
    private var view: WebView? = null

    @Before fun onlyOnRequest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mfpNetworkProbe") == "true")
    }

    @After fun close() {
        instrumentation.runOnMainSync { view?.destroy() }
        server?.close()
    }

    @Test fun aHostOutsideTheLinkIsRefusedAsUntrusted() {
        val client = load(OUTSIDE)

        assertTrue("no answer: is the phone online?", client.settled.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals(listOf("${SslError.SSL_UNTRUSTED} $OUTSIDE"), client.certificateErrors.toList())
    }

    @Test fun aResourceRedirectedOutsideTheLinkIsRefusedAlthoughTheFilterNeverSawIt() {
        val server = LoopbackTlsServer(redirectTo = OUTSIDE).also { server = it }
        val client = load(server.pageUrl, loopback = server.pageUrl.substringBeforeLast('/'))

        assertTrue("no answer: is the phone online?", client.settled.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals(listOf("${SslError.SSL_UNTRUSTED} $OUTSIDE"), client.certificateErrors.toList())
        assertTrue(server.served.toString(), server.served.containsAll(listOf("/page", "/hop")))
        assertFalse(client.offered.toString(), OUTSIDE in client.offered)
    }

    @Test fun theChallengeHostOfTheLinkIsStillTrusted() {
        val client = load(CHALLENGE)

        assertTrue("the page did not finish: is the phone online?", client.finished.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue(client.certificateErrors.toString(), client.certificateErrors.isEmpty())
        assertTrue(client.failures.toString(), client.failures.isEmpty())
    }

    private fun load(url: String, loopback: String? = null): Recording {
        val client = Recording(loopback)
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                webViewClient = client
                loadUrl(url)
            }
        }
        return client
    }

    /** Records and lets every request through. Only the certificate of the loopback server of the test is accepted. */
    private class Recording(private val loopback: String?) : WebViewClient() {
        val offered = CopyOnWriteArrayList<String>()
        val certificateErrors = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)

        /** Counted down by the first certificate error that is not the loopback server's own. */
        val settled = CountDownLatch(1)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            offered += request.url.toString()
            return null
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (loopback != null && error.url.startsWith("$loopback/")) {
                handler.proceed()
                return
            }
            certificateErrors += "${error.primaryError} ${error.url}"
            handler.cancel()
            settled.countDown()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) failures += "${error.errorCode} ${error.description}"
        }

        override fun onPageFinished(view: WebView, url: String) = finished.countDown()
    }

    private companion object {
        const val TIMEOUT_S = 30L

        /** A public page reserved for documentation, with a certificate any browser accepts. */
        const val OUTSIDE = "https://example.org/"

        /** The script MyFitnessPal's sign-in page loads from [MfpHosts.CHALLENGE_HOST]: fetched as a document, not run in a page. */
        const val CHALLENGE = "https://${MfpHosts.CHALLENGE_HOST}/turnstile/v0/api.js"
    }
}

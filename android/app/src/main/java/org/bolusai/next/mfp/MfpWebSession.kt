package org.bolusai.next.mfp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.bolusai.mfp.MfpDate
import org.bolusai.mfp.MfpFailure
import org.bolusai.mfp.MfpPageLoad
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Barrier for instrumentation, like the storage guards. Once the test runner activates it, the process may only build
 * a session whose every request is answered by the test, and may not touch the app's own cookies or its switch.
 */
internal object MfpLinkGuard {
    const val REAL_SESSION_DENIED = "mfp.session.real_session_denied_in_instrumentation"

    @Volatile var active: Boolean = false
        private set

    fun activateForInstrumentation() {
        active = true
    }
}

/**
 * The MyFitnessPal session of this phone (ADR 0032, second delivery): the only place of Next that opens a connection.
 *
 * - The user signs in on MyFitnessPal's own page inside this view. Next never sees, stores or types the password.
 * - The session lives in the cookie store of this app's own web view. Next never reads, copies or logs a cookie, and
 *   the store is excluded from backups like every other file of the app.
 * - Every request is checked against [MfpHosts] before it leaves; anything else is answered locally with an empty
 *   403 and its host is listed in [blockedHosts] for the user.
 * - Read only: the view loads pages the user opens and the diary of one day. Nothing is posted by Next, and the
 *   extraction script only reads the loaded page. Nothing reaches Legacy, Hermes or Telegram.
 * - No page can call into the app: no JavaScript interface is ever added.
 *
 * It exists only while the link is switched on and its screen is open; `MainActivity` destroys it otherwise.
 * [answers] is the instrumentation seam: when set, no request reaches the network.
 */
internal class MfpWebSession(context: Context, private val answers: ((String) -> WebResourceResponse?)? = null) {
    private val main = Handler(Looper.getMainLooper())
    private val blocked = Collections.synchronizedSet(LinkedHashSet<String>())
    private val pending = AtomicReference<Pending?>(null)

    /** Called on the main thread when the page or the blocked list changed. */
    var changed: (() -> Unit)? = null

    private class Pending(val latch: CountDownLatch) {
        @Volatile var result: MfpPageLoad = MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED)
        @Volatile var httpStatus: Int? = null
        @Volatile var failed = false
    }

    init {
        check(!MfpLinkGuard.active || answers != null) { MfpLinkGuard.REAL_SESSION_DENIED }
    }

    @SuppressLint("SetJavaScriptEnabled") // MyFitnessPal's sign-in page needs it; requests stay limited by MfpHosts.
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Client()
    }

    val blockedHosts: List<String> get() = synchronized(blocked) { blocked.toList() }

    /** Shows a page in the view. Only for an explicit action of the user. */
    fun open(url: String) {
        if (MfpHosts.mainFrameAllowed(url)) view.loadUrl(url) else block(url)
    }

    /**
     * Loads the diary of [date] and returns its text. Blocks the calling worker thread, never the main thread. Every
     * call is a new load; a failure or a timeout returns nothing of an earlier page.
     */
    fun read(date: MfpDate): MfpPageLoad {
        check(Looper.myLooper() != Looper.getMainLooper()) { "mfp.session.read_on_main_thread" }
        val request = Pending(CountDownLatch(1))
        if (!pending.compareAndSet(null, request)) return MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED)
        main.post { if (pending.get() === request) view.loadUrl(MfpHosts.diaryUrl(date)) }
        if (!request.latch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            pending.compareAndSet(request, null)
            main.post { view.stopLoading() }
            return MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED)
        }
        return request.result
    }

    /** Forgets the MyFitnessPal session of this phone: cookies, web storage, cache and history of the view. */
    fun signOut(done: () -> Unit) {
        check(!MfpLinkGuard.active) { MfpLinkGuard.REAL_SESSION_DENIED }
        view.stopLoading()
        view.loadUrl("about:blank")
        view.clearCache(true)
        view.clearHistory()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            done()
        }
    }

    fun pause() = view.onPause()
    fun resume() = view.onResume()

    /** Ends the view and any load in progress, which then reports a transport failure. */
    fun destroy() {
        changed = null
        pending.getAndSet(null)?.latch?.countDown()
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
    }

    private fun block(url: String) {
        if (blocked.size < MAX_BLOCKED_LISTED && blocked.add(MfpHosts.blockedLabel(url))) {
            main.post { changed?.invoke() }
        }
    }

    private fun finish(request: Pending, result: MfpPageLoad) {
        if (!pending.compareAndSet(request, null)) return
        request.result = result
        request.latch.countDown()
    }

    private inner class Client : WebViewClient() {
        /** Runs on a web view thread, for every request of the page, the page itself included. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val allowed = if (request.isForMainFrame) MfpHosts.mainFrameAllowed(url) else MfpHosts.resourceAllowed(url)
            if (!allowed) {
                block(url)
                return empty(403, "Blocked")
            }
            val test = answers ?: return null
            return test(url) ?: empty(404, "Not Found")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || MfpHosts.mainFrameAllowed(request.url.toString())) return false
            block(request.url.toString())
            return true
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) pending.get()?.httpStatus = response.statusCode
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) pending.get()?.failed = true
        }

        override fun onPageFinished(view: WebView, url: String) {
            changed?.invoke()
            val request = pending.get() ?: return
            if (request.failed) {
                finish(request, MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED))
                return
            }
            view.evaluateJavascript(MfpExtraction.SCRIPT) { answer ->
                val page = MfpExtraction.decode(answer, request.httpStatus)
                finish(request, page?.let { MfpPageLoad.Loaded(it) } ?: MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED))
            }
        }
    }

    companion object {
        /** How long one page load may take. A technical bound of the transport, not a clinical value. */
        const val LOAD_TIMEOUT_MS: Long = 30_000

        /** How many blocked hosts are listed for the user. */
        const val MAX_BLOCKED_LISTED = 50

        private fun empty(status: Int, reason: String) =
            WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

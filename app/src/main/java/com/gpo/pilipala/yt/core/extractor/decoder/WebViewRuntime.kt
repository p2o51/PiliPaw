package com.gpo.pilipala.yt.core.extractor.decoder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.gpo.pilipala.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One headless WebView for the process, used as a JavaScript engine (V8) by [WebViewJsDecoder].
 * Ported from PipePipe's SharedWebViewRuntime (PipePipeClient, GPL-3.0), without its SABR /
 * PO-token bridge: a blank first-party page whose own script reports ready over a bridge
 * (onPageFinished is unreliable for local pages), one retry, network loads blocked.
 *
 * Every blocking call must come from a background thread; the WebView itself lives on main.
 */
class WebViewRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var latch: CountDownLatch? = null
    private var failure: AtomicReference<Throwable?>? = null
    private var attempt = 0L
    private var view: WebView? = null
    @Volatile private var ready = false

    /** Waits (up to [timeoutMs]) until the page is up, starting it if nobody has. */
    fun ensureReady(timeoutMs: Long, operation: String) {
        val l: CountDownLatch
        val f: AtomicReference<Throwable?>
        synchronized(lock) {
            if (ready) return
            if (latch == null) start()
            l = latch!!
            f = failure!!
        }
        check(Looper.myLooper() != Looper.getMainLooper()) { "$operation cannot wait on the main thread" }
        check(l.await(timeoutMs, TimeUnit.MILLISECONDS)) { "$operation timed out waiting for the WebView" }
        f.get()?.let { throw IllegalStateException("$operation: WebView failed to start", it) }
    }

    /** Runs [script] and returns its JSON-encoded result (as WebView's evaluateJavascript gives it). */
    fun evaluateBlocking(script: String, timeoutMs: Long, operation: String): String? {
        ensureReady(timeoutMs, operation)
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        main.post {
            try {
                val v = view ?: error("WebView runtime is not initialized")
                v.evaluateJavascript(script) { value ->
                    result.set(value)
                    done.countDown()
                }
            } catch (t: Throwable) {
                error.set(t)
                done.countDown()
            }
        }
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "$operation timed out" }
        error.get()?.let { throw IllegalStateException("$operation failed", it) }
        return result.get()
    }

    fun loadAsset(path: String): String = app.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun start() {
        latch = CountDownLatch(1)
        failure = AtomicReference(null)
        val id = ++attempt
        main.post { create(id, retriesLeft = 1) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(id: Long, retriesLeft: Int) {
        val v = try {
            WebView(app)
        } catch (t: Throwable) {
            fail(t)
            return
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            blockNetworkLoads = true
            safeBrowsingEnabled = false
            userAgentString = UserAgent
        }
        v.addJavascriptInterface(Bridge(id, v), BridgeName)
        v.loadDataWithBaseURL(
            "https://www.youtube.com/",
            "<!doctype html><html><head><script>$BridgeName.ready('$id');</script></head><body></body></html>",
            "text/html",
            "UTF-8",
            null,
        )
        main.postDelayed({
            synchronized(lock) { if (ready || attempt != id) return@postDelayed }
            runCatching { v.destroy() }
            if (retriesLeft > 0) {
                val next = synchronized(lock) { ++attempt }
                Log.w(Tag, "WebView ready callback timed out, retrying")
                create(next, retriesLeft - 1)
            } else {
                fail(IllegalStateException("WebView ready callback timed out"))
            }
        }, ReadyTimeoutMs)
    }

    private fun fail(t: Throwable) {
        Log.e(Tag, "WebView runtime failed", t)
        synchronized(lock) {
            failure?.compareAndSet(null, t)
            latch?.countDown()
            // A later call starts over.
            latch = null
        }
    }

    private inner class Bridge(private val id: Long, private val v: WebView) {
        @JavascriptInterface
        fun ready(attemptId: String) {
            main.post {
                synchronized(lock) {
                    if (ready || attempt != id || attemptId != id.toString()) return@post
                    view = v
                    ready = true
                    latch?.countDown()
                }
            }
        }
    }

    companion object {
        private const val Tag = "WebViewRuntime"
        private const val BridgeName = "PawWebViewBridge"
        private const val ReadyTimeoutMs = 5_000L
        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        @Volatile private var instance: WebViewRuntime? = null

        fun get(context: Context): WebViewRuntime =
            instance ?: synchronized(this) { instance ?: WebViewRuntime(context).also { instance = it } }
    }
}

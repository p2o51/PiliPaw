package com.gpo.pilipala.yt.core.extractor

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The extractor's network: PipePipeClient's DownloaderImpl in Kotlin, without the other
 * services' special cases (Bilibili referers, NicoNico cookies) and the benchmark cache.
 * Extractor headers win over the defaults; a 429 is YouTube's captcha wall.
 */
class PawDownloader(base: OkHttpClient) : Downloader() {
    val client: OkHttpClient = base.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    /** Cookies by key (restricted mode, a solved captcha, later the signed-in session). */
    private val cookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setCookie(key: String, value: String) { cookies[key] = value }
    fun removeCookie(key: String) { cookies.remove(key) }

    private fun cookiesFor(url: String): String =
        cookies.values.filter { it.isNotEmpty() }.joinToString("; ").takeIf { url.contains("youtube.com") || url.contains("google") }.orEmpty()

    private fun build(request: Request): okhttp3.Request {
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            request.httpMethod() == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val headers = request.headers()
        val builder = okhttp3.Request.Builder().method(request.httpMethod(), body).url(request.url())
        if (!headers.containsKey("User-Agent")) builder.header("User-Agent", USER_AGENT)
        val cookie = cookiesFor(request.url())
        if (!headers.containsKey("Cookie") && cookie.isNotEmpty()) builder.header("Cookie", cookie)
        for ((name, values) in headers) {
            if (values.size > 1) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            } else if (values.size == 1) {
                builder.header(name, values[0])
            }
        }
        return builder.build()
    }

    private fun clientFor(request: Request, timeoutMs: Long? = null): OkHttpClient {
        if (request.followRedirects() && timeoutMs == null && !request.url().contains("pipepipe.dev")) return client
        return client.newBuilder()
            .followRedirects(request.followRedirects())
            .followSslRedirects(request.followRedirects())
            .apply {
                if (timeoutMs != null) {
                    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                }
            }
            .build()
    }

    /** DNS hiccups on a waking radio: a couple of quiet retries before giving up. */
    private fun executeWithRetry(http: OkHttpClient, req: okhttp3.Request): okhttp3.Response {
        var attempt = 0
        while (true) {
            try {
                return http.newCall(req).execute()
            } catch (e: UnknownHostException) {
                if (++attempt > MaxDnsRetries) throw e
                Thread.sleep(DnsRetryDelayMs)
            }
        }
    }

    override fun execute(request: Request): Response {
        val response = executeWithRetry(clientFor(request), build(request))
        response.use { res ->
            if (res.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val raw = res.body.bytes()
            return Response(res.code, res.message, res.headers.toMultimap(), String(raw, Charsets.UTF_8), raw, res.request.url.toString())
        }
    }

    override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
        val call = clientFor(request).newCall(build(request))
        val cancellable = CancellableCall(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try {
                    callback.onError(e)
                } finally {
                    cancellable.setFinished()
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    if (response.code == 429) {
                        callback.onError(ReCaptchaException("reCaptcha Challenge requested", request.url()))
                        return
                    }
                    val raw = response.body.bytes()
                    callback.onSuccess(
                        Response(response.code, response.message, response.headers.toMultimap(), String(raw, Charsets.UTF_8), raw, response.request.url.toString()),
                    )
                } catch (e: Exception) {
                    callback.onError(e)
                } finally {
                    response.close()
                    cancellable.setFinished()
                }
            }
        })
        return cancellable
    }

    override fun getStreaming(url: String, headers: Map<String, List<String>>?, localization: Localization?): StreamingResponse =
        executeStreaming(Request.newBuilder().get(url).headers(headers).localization(localization).build(), null)

    override fun getStreaming(url: String, headers: Map<String, List<String>>?, localization: Localization?, timeoutMs: Long): StreamingResponse =
        executeStreaming(Request.newBuilder().get(url).headers(headers).localization(localization).build(), timeoutMs.coerceAtLeast(1))

    override fun postStreaming(url: String, headers: Map<String, List<String>>?, dataToSend: ByteArray?, localization: Localization?): StreamingResponse =
        executeStreaming(Request.newBuilder().post(url, dataToSend).headers(headers).localization(localization).build(), null)

    /** The body as a stream (SABR batches run to 150 MB at 4K); the caller closes it. */
    private fun executeStreaming(request: Request, timeoutMs: Long?): StreamingResponse {
        val response = executeWithRetry(clientFor(request, timeoutMs), build(request))
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        return StreamingResponse(response.code, response.headers.toMultimap(), response.body.byteStream())
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val MaxDnsRetries = 2
        private const val DnsRetryDelayMs = 500L
    }
}

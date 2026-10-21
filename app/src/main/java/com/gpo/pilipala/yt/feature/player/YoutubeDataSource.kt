package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gpo.pilipala.yt.core.extractor.PawDownloader
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * How googlevideo wants to be asked, ported from PipePipe's YoutubeHttpDataSource (PipePipeClient,
 * GPL-3.0) onto OkHttp and a ResolvingDataSource:
 *
 * - `/videoplayback` takes its byte range as a query parameter (`&range=a-b`) with a request
 *   counter (`&rn=`); a Range header on these URLs is throttled or refused. The rewritten
 *   request starts at 0 of the slice.
 * - Each client's URLs are fetched as that client: the Android and iOS apps' user agents (and
 *   POST, as the mobile apps do), the web player's Origin / Referer / Sec-Fetch headers for
 *   WEB and TV-embedded URLs, the desktop browser's user agent for the rest.
 *
 * A 403 (an expired or refused URL) is not retried here: [Player] re-extracts the stream and
 * picks up where it was, as PipePipe's PlayerErrorHandler does.
 */
object YoutubeDataSource {

    /** The HTTP layer under every YouTube source: the app's one OkHttp, the browser user agent by default. */
    fun upstream(client: OkHttpClient): OkHttpDataSource.Factory =
        // The user agent as a default property, not setUserAgent(): a per-request one then replaces it.
        OkHttpDataSource.Factory(client).setDefaultRequestProperties(mapOf("User-Agent" to PawDownloader.USER_AGENT))

    /** One video's sources: [upstream] with googlevideo's ranges and per-client requests; the `rn` counter is per video. */
    fun forVideo(upstream: DataSource.Factory): DataSource.Factory {
        val counter = AtomicLong()
        return ResolvingDataSource.Factory(upstream) { spec -> resolve(spec, counter) }
    }

    private fun resolve(spec: DataSpec, counter: AtomicLong): DataSpec {
        val uri = spec.uri
        if (uri.path?.startsWith("/videoplayback") != true) return spec
        val url = uri.toString()
        var out = spec
        if (uri.getQueryParameter("range") == null) {
            val end = if (spec.length != C.LENGTH_UNSET.toLong()) (spec.position + spec.length - 1).toString() else ""
            val rewritten = uri.buildUpon()
                .appendQueryParameter("range", "${spec.position}-$end")
                .appendQueryParameter("rn", counter.getAndIncrement().toString())
                .build()
            out = out.buildUpon().setUri(rewritten).setPosition(0).setUriPositionOffset(0).setLength(C.LENGTH_UNSET.toLong()).build()
        }
        val headers = HashMap(out.httpRequestHeaders)
        val android = YoutubeParsingHelper.isAndroidStreamingUrl(url)
        val ios = YoutubeParsingHelper.isIosStreamingUrl(url)
        when {
            android -> headers["User-Agent"] = YoutubeParsingHelper.getAndroidUserAgent(null)
            ios -> headers["User-Agent"] = YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isWebStreamingUrl(url) || YoutubeParsingHelper.isTvHtml5SimplyEmbeddedPlayerStreamingUrl(url) -> {
                headers["Origin"] = YouTubeOrigin
                headers["Referer"] = YouTubeOrigin
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
            }
        }
        val builder = out.buildUpon().setHttpRequestHeaders(headers)
        // The mobile apps fetch their media with POST (an empty body).
        if (android || ios) builder.setHttpMethod(DataSpec.HTTP_METHOD_POST)
        return builder.build()
    }

    private const val YouTubeOrigin = "https://www.youtube.com"
}

package com.gpo.pilipala

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

class PiliApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        com.gpo.pilipala.core.util.CrashLog.install(this)
        BiliClient.init(this)
        AppSettings.init(this)
        com.gpo.pilipala.feature.player.cdn.CdnRouter.init(this)
        Account.init(this)
        com.gpo.pilipala.feature.widget.WidgetStore.appContext = this
    }

    /**
     * Covers get their own client: OkHttp's default 5-per-host limit queued a
     * 6-column grid fling's covers (nearly all on i0/i1/i2.hdslb.com) five at
     * a time behind each other and behind API calls. Same pool and UA/Referer
     * interceptor; no cookie jar — the image CDN takes none.
     */
    private val imageHttp: OkHttpClient by lazy {
        BiliClient.http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

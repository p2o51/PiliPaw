package com.gpo.pilipala

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.transitionFactory
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
        com.gpo.pilipala.feature.player.lyrics.LyricsStore.init(this)
        com.gpo.pilipala.yt.feature.listen.LyricsStore.init(this)
        AppSettings.init(this)
        // Chinese in Google Sans Flex's weights: start fetching Noto Sans SC now.
        com.gpo.pilipala.ui.theme.CjkFonts.preload(this)
        com.gpo.pilipala.feature.player.cdn.CdnRouter.init(this)
        Account.init(this)
        com.gpo.pilipala.feature.widget.WidgetStore.appContext = this
        com.gpo.pilipala.core.source.Sources.init(this)
        com.gpo.pilipala.yt.YtApp.init(this, active = com.gpo.pilipala.core.source.Sources.isOn(com.gpo.pilipala.core.source.VideoSource.YouTube))
        // Mix lists that follow a 收藏夹 or a playlist catch up once per launch.
        com.gpo.pilipala.feature.sources.MixSync.syncAll()
        // A Mix playing on with the app away (its B站 videos then play without a page).
        com.gpo.pilipala.feature.sources.MixPlayback.init(this)
        // 云同步, when it is on: merge what other devices left in Drive, leave this one's.
        com.gpo.pilipala.feature.sources.PawCloud.init(this)
        com.gpo.pilipala.feature.sources.PawCloud.syncIfOn()
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            // Fades in only what came over the network; counts what is still loading.
            .transitionFactory(com.gpo.pilipala.ui.components.NetworkCrossfade)
            .eventListener(com.gpo.pilipala.ui.components.ImageLoads)
            .build()
}

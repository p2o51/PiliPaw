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

class PiliApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        BiliClient.init(this)
        AppSettings.init(this)
        com.gpo.pilipala.feature.player.cdn.CdnRouter.init(this)
        Account.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { BiliClient.http }))
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

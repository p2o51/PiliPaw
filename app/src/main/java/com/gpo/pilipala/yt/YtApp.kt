package com.gpo.pilipala.yt

import android.app.Application
import com.gpo.pilipala.yt.core.account.AccountSubscriptions
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.extractor.decoder.WebViewJsDecoder
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.feature.account.SignIn
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.feature.player.Segments
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import java.util.concurrent.TimeUnit

/** The YouTube part's start-up (what PipePaw's Application did), called from the app's. */
object YtApp {
    /** One connection pool for the extractor and the player. */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
    }

    /** [active]: YouTube is ticked. Otherwise nothing touches the network or warms a WebView until it is. */
    fun init(app: Application, active: Boolean) {
        AppSettings.init(app)
        Accounts.init(app)
        Subscriptions.init(app)
        Library.init(app)
        Yt.init(app, http)
        // Signatures and n solved on the phone (V8 in a WebView); the extractor falls back to
        // PipePipe's remote decoder if this ever fails. Warmed off the main thread at start.
        val decoder = WebViewJsDecoder(app)
        YoutubeApiDecoder.setLocalDecoder(decoder)
        this.decoder = decoder
        Player.init(app)
        Segments.configure()
        if (active) activate()
    }

    private var decoder: WebViewJsDecoder? = null
    private var activated = false

    /** YouTube was ticked (at start, or later): warm the decoder, refresh the account. */
    fun activate() {
        if (activated) return
        activated = true
        decoder?.let { d -> Thread({ d.prewarm() }, "decoder-prewarm").apply { isDaemon = true }.start() }
        SignIn.refresh()
        AccountSubscriptions.start()
    }
}

package com.gpo.pilipala.yt.feature.account

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.YtAccount
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.ui.components.PawTopBar
import com.gpo.pilipala.ui.components.rememberToast

/**
 * Signing in and out of YouTube. Signing in happens on Google's own page in a WebView (the
 * person types their credentials there; the app never sees them), as PipePipe does: once
 * youtube.com holds a session (its SAPISID / SID cookies), the cookies are the account.
 */
object SignIn {
    const val Url = "https://www.youtube.com/signin"

    /** A session YouTube's API accepts: the cookie the SAPISIDHASH authorization is made from, and the session id. */
    fun hasSession(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        val names = cookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        return ("SAPISID" in names || "__Secure-3PAPISID" in names) && ("SID" in names || "__Secure-3PSID" in names)
    }

    /** youtube.com's cookies as the WebView holds them now. */
    fun youtubeCookies(): String? = CookieManager.getInstance().getCookie("https://www.youtube.com")

    private val scope = MainScope()

    /**
     * The WebView found a session: sign in with its [cookies] at once, then fill in who it is
     * (name, handle, avatar) when YouTube answers. Also refreshes a stored profile at start.
     */
    fun complete(cookies: String) {
        Accounts.setYouTube(Profile.SignedIn(cookies = cookies))
        refresh()
    }

    /** Re-reads who the signed-in account is (a new avatar, a renamed channel). Quiet on failure. */
    fun refresh() {
        val signedIn = Accounts.youtube.value as? Profile.SignedIn ?: return
        scope.launch {
            val me = runCatching { YtAccount.me() }.onFailure { Log.w("SignIn", "account_menu", it) }.getOrNull() ?: return@launch
            if ((Accounts.youtube.value as? Profile.SignedIn)?.cookies != signedIn.cookies) return@launch
            Accounts.setYouTube(signedIn.copy(name = me.name, handle = me.handle, avatar = me.avatar))
        }
    }

    /** Back to signed out: the profile, the WebView's cookies and its storage all go. */
    fun signOut() {
        Accounts.setYouTube(Profile.SignedOut)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}

/**
 * 登录: Google's sign-in page continuing to youtube.com, full screen under a plain top bar.
 * Every page load checks youtube.com's cookies; the first time they hold a session the
 * profile becomes signed in and the page closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen() {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    var loading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PawTopBar(title = "登录 YouTube", onBack = { nav.pop() })
        Box(Modifier.fillMaxWidth()) {
            if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // A phone browser: Google signs in a WebView it recognises as one.
                    settings.userAgentString = MobileUserAgent
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        // A renderer the system killed would otherwise take the app down with it.
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = true

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            if (done) return
                            val cookies = SignIn.youtubeCookies()
                            if (SignIn.hasSession(cookies)) {
                                done = true
                                CookieManager.getInstance().flush()
                                SignIn.complete(cookies!!)
                                toast("已登录")
                                nav.pop()
                            }
                        }
                    }
                    loadUrl(SignIn.Url)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private const val MobileUserAgent =
    "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

package com.gpo.pilipala.feature.login

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.feature.settings.SettingsBackButton
import com.gpo.pilipala.feature.web.createBiliWebView
import com.gpo.pilipala.feature.web.importWebViewCookies
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

private const val WebLoginUrl = "https://passport.bilibili.com/h5-app/passport/login"

/**
 * Secondary login: bilibili's own h5 login page. Once the WebView holds SESSDATA
 * for bilibili.com, every bilibili cookie is copied into BiliClient's jar.
 */
@Composable
internal fun WebLoginPane(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val dark = LocalPiliDark.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var finished by remember { mutableStateOf(false) }
    val onDone by rememberUpdatedState(onLoggedIn)

    val check: (manual: Boolean) -> Unit = { manual ->
        if (!finished) {
            if (importWebViewCookies()) {
                finished = true
                scope.launch {
                    runCatching { Account.refresh() }
                    onDone()
                }
            } else if (manual) {
                toast("未登录")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("网页登录") },
                    navigationIcon = { SettingsBackButton(onClick = onBack) },
                    actions = {
                        IconButton(onClick = { check(true) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(YoinSymbols.Check, contentDescription = "刷新登录状态")
                        }
                        IconButton(onClick = { webView?.reload() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(YoinSymbols.Refresh, contentDescription = "刷新")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                AnimatedVisibility(
                    visible = loading,
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + YoinMotion.fadeIn(YoinMotionRole.Standard),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + YoinMotion.fadeOut(YoinMotionRole.Standard),
                ) {
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { inner ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
            factory = { ctx ->
                // A fresh session, as pilipala does for its login webview.
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                }
                createBiliWebView(ctx, desktop = false, dark = dark).apply {
                    clearCache(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                            loading = newProgress < 100
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // A renderer the system killed would otherwise take the app down with it.
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = true

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme.orEmpty()
                            // App schemes (bilibili://, intent://) would leave the login flow.
                            return scheme != "http" && scheme != "https"
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            loading = true
                            check(false)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            CookieManager.getInstance().flush()
                            check(false)
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            check(false)
                        }
                    }
                    loadUrl(WebLoginUrl)
                    webView = this
                }
            },
        )
    }
}

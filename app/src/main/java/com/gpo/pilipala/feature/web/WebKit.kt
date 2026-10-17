package com.gpo.pilipala.feature.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.gpo.pilipala.core.net.BiliClient

/** Shared WebView setup for the browser page and web login. */
@SuppressLint("SetJavaScriptEnabled")
internal fun createBiliWebView(context: Context, desktop: Boolean, dark: Boolean): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (desktop) BiliClient.UA_PC else BiliClient.UA_MOBILE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = dark
            }
        }
        val view = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

/** Copies the app's bilibili cookies into the WebView cookie store (for .bilibili.com). */
internal fun syncCookiesToWebView() {
    val cm = CookieManager.getInstance()
    runCatching {
        BiliClient.cookies.all()
            .filter { it.domain.endsWith("bilibili.com") }
            .forEach { c ->
                val domain = c.domain.removePrefix(".")
                cm.setCookie(
                    "https://$domain",
                    "${c.name}=${c.value}; Domain=.bilibili.com; Path=${c.path}" + if (c.secure) "; Secure" else "",
                )
            }
        cm.flush()
    }
}

/** Parses a CookieManager header ("a=b; c=d") into pairs. */
internal fun parseCookieHeader(raw: String?): List<Pair<String, String>> =
    raw.orEmpty().split(';').mapNotNull { part ->
        val p = part.trim()
        val eq = p.indexOf('=')
        if (eq <= 0) null else p.substring(0, eq).trim() to p.substring(eq + 1).trim()
    }

/** Copies every bilibili cookie the WebView holds into BiliClient's jar. Returns true when signed in. */
internal fun importWebViewCookies(): Boolean {
    val cm = CookieManager.getInstance()
    val pairs = listOf(
        "https://www.bilibili.com",
        "https://passport.bilibili.com",
        "https://m.bilibili.com",
    ).flatMap { parseCookieHeader(cm.getCookie(it)) }.distinctBy { it.first }
    if (pairs.none { it.first == "SESSDATA" && it.second.isNotEmpty() }) return false
    pairs.forEach { (name, value) ->
        if (value.isNotEmpty()) runCatching { BiliClient.cookies.put(name, value) }
    }
    return BiliClient.isLoggedIn
}

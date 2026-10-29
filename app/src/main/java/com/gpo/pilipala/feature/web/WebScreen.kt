package com.gpo.pilipala.feature.web

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * In-app browser for bilibili pages we don't have natively. The app's cookies are
 * copied in first so the pages see the signed-in account; navigations to video /
 * space / live / dynamic / article links leave the WebView for the native page.
 */
@Composable
fun WebScreen(route: Route.Web) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val dark = LocalPiliDark.current
    val desktop = widthClass() == WidthClass.Expanded
    val startUrl = remember(route.url) { normalizeUrl(route.url) }

    var title by remember { mutableStateOf(route.title) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val navState by rememberUpdatedState(nav)

    BackHandler(enabled = canGoBack) { webView?.goBack() }

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
            TopAppBar(
                title = {
                    Column {
                        AnimatedContent(
                            targetState = title.ifEmpty { hostOf(currentUrl) },
                            transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard) },
                            label = "webTitle",
                        ) { t ->
                            Text(t, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            hostOf(currentUrl),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { webView?.reload() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.Refresh, contentDescription = "刷新")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("在浏览器打开") },
                                leadingIcon = { Icon(YoinSymbols.Launch, null) },
                                onClick = {
                                    menuOpen = false
                                    openExternal(context, currentUrl)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("分享") },
                                leadingIcon = { Icon(YoinSymbols.Share, null) },
                                onClick = {
                                    menuOpen = false
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                                        putExtra(Intent.EXTRA_TITLE, title)
                                    }
                                    context.startActivity(Intent.createChooser(send, null))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制链接") },
                                leadingIcon = { Icon(YoinSymbols.Link, null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("url", currentUrl)))
                                    }
                                    toast("已复制")
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { inner ->
        // The progress bar floats over the page's top edge and only fades, so the WebView
        // keeps a constant height (no per-frame reflow while it shows / hides).
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    syncCookiesToWebView()
                    createBiliWebView(ctx, desktop = desktop, dark = dark).apply {
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                                loading = newProgress < 100
                            }

                            override fun onReceivedTitle(view: WebView, t: String?) {
                                if (!t.isNullOrBlank() && !t.startsWith("http")) title = t
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // A renderer the system killed would otherwise take the app down with it.
                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = true

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                val scheme = request.url.scheme.orEmpty()
                                if (scheme != "http" && scheme != "https") {
                                    // bilibili:// deep links map to native pages; other app schemes stay blocked.
                                    if (scheme == "bilibili") {
                                        val target = LinkRouter.route(url)
                                        if (target !is Route.Web) navState.push(target)
                                    }
                                    return true
                                }
                                if (!request.isForMainFrame) return false
                                val host = request.url.host.orEmpty()
                                if (host.contains("bilibili.com") || host.contains("b23.tv")) {
                                    val target = LinkRouter.route(url)
                                    if (target !is Route.Web) {
                                        // A short link that redirects straight to a native page replaces this one.
                                        if (request.isRedirect && !view.canGoBack()) navState.replace(target) else navState.push(target)
                                        return true
                                    }
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loading = true
                                currentUrl = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                currentUrl = url
                                canGoBack = view.canGoBack()
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                                canGoBack = view.canGoBack()
                            }
                        }
                        loadUrl(startUrl)
                        webView = this
                    }
                },
            )
            AnimatedVisibility(
                visible = loading,
                enter = YoinMotion.fadeIn(YoinMotionRole.Standard),
                exit = YoinMotion.fadeOut(YoinMotionRole.Standard),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

internal fun normalizeUrl(raw: String): String {
    val u = raw.trim()
    return when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") || u.startsWith("https://") -> u
        else -> "https://$u"
    }
}

internal fun hostOf(url: String): String = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

private fun openExternal(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

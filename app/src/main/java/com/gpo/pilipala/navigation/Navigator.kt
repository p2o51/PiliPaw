package com.gpo.pilipala.navigation

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.gpo.pilipala.core.util.IdUtils

/** App-wide navigation handle: `LocalNavigator.current.push(Route.Video(bvid))`. */
class Navigator(private val backStack: NavBackStack<NavKey>) {
    fun push(route: Route) {
        backStack.add(route)
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun replace(route: Route) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    fun popToMain() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Open any bilibili link (or anything else) — native page when we know it, WebView otherwise. */
    fun openUrl(url: String, title: String = "") = push(LinkRouter.route(url, title))
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("LocalNavigator not provided") }

/** Maps bilibili URLs / schemes to native routes. */
object LinkRouter {
    fun route(raw: String, title: String = ""): Route {
        val url = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Route.Web(url, title)
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        val segs = uri.pathSegments
        IdUtils.findBvid(url)?.let { if (host.contains("bilibili") || uri.scheme == "bilibili" || host.contains("b23")) return Route.Video(it) }
        return when {
            uri.scheme == "bilibili" && host == "video" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Video(IdUtils.av2bv(it), aid = it) } ?: Route.Web(url, title)
            uri.scheme == "bilibili" && host == "space" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Member(it) } ?: Route.Web(url, title)
            uri.scheme == "bilibili" && host == "live" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.LiveRoom(it) } ?: Route.Web(url, title)
            uri.scheme == "bilibili" && (host == "opus" || host == "following") -> segs.lastOrNull()?.let { Route.DynamicDetail(it) } ?: Route.Web(url, title)
            host.startsWith("space.bilibili.com") -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Member(it) } ?: Route.Web(url, title)
            host.startsWith("live.bilibili.com") -> segs.firstOrNull()?.toLongOrNull()?.let { Route.LiveRoom(it) } ?: Route.Web(url, title)
            host.startsWith("t.bilibili.com") -> segs.firstOrNull()?.let { Route.DynamicDetail(it) } ?: Route.Web(url, title)
            host.contains("bilibili.com") && path.startsWith("/opus/") -> Route.DynamicDetail(segs.last())
            host.contains("bilibili.com") && path.startsWith("/read/cv") -> path.removePrefix("/read/cv").toLongOrNull()?.let { Route.Read(it, title) } ?: Route.Web(url, title)
            host.contains("bilibili.com") && path.startsWith("/bangumi/play/ss") -> Route.Bangumi(seasonId = path.removePrefix("/bangumi/play/ss").toLongOrNull() ?: 0)
            host.contains("bilibili.com") && path.startsWith("/bangumi/play/ep") -> Route.Bangumi(epId = path.removePrefix("/bangumi/play/ep").toLongOrNull() ?: 0)
            host.contains("bilibili.com") && path.startsWith("/video/av") -> path.removePrefix("/video/av").trimEnd('/').toLongOrNull()?.let { Route.Video(IdUtils.av2bv(it), aid = it) } ?: Route.Web(url, title)
            else -> Route.Web(url, title)
        }
    }
}

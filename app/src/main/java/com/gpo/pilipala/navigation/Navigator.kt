package com.gpo.pilipala.navigation

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.navigation.transition.PageTransitions

/**
 * App-wide navigation handle: `LocalNavigator.current.push(Route.Video(bvid))`.
 *
 * Every change goes through the host's [PageTransitions] once AppNavHost has
 * attached it: a push freezes the page it covers (what a later back reveals)
 * and picks container transform vs forward mirror; a pop plays the same
 * commit ride as a 3-button back and removes the entry only when it lands.
 */
class Navigator(internal val backStack: NavBackStack<NavKey>) {
    internal var transitions: PageTransitions? = null

    fun push(route: Route) {
        transitions?.beforePush(route)
        backStack.add(route)
    }

    /** True when there was a page to leave; the entry is removed once the close motion lands. */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        val t = transitions
        if (t != null) t.requestPop() else backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun replace(route: Route) {
        transitions?.beforeReplace(route)
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    fun popToMain() {
        // Drop what sits between, then close the top page onto the shell.
        while (backStack.size > 2) backStack.removeAt(backStack.lastIndex - 1)
        pop()
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
        // Votes: the web vote page t.bilibili.com/vote/h5/index/#/result?vote_id=… (also what inline
        // RICH_TEXT_NODE_TYPE_VOTE links point at) opens the native vote page. Other pages that
        // merely carry a vote_id (activity pages in the WebView) stay where they are.
        if (host.equals("t.bilibili.com", ignoreCase = true) && path.startsWith("/vote/")) {
            VoteIdRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { voteId ->
                val dynId = DynamicIdRegex.find(url)?.groupValues?.get(1).orEmpty()
                return Route.Vote(voteId, dynamicId = dynId.takeIf { it.isDigitId() }.orEmpty(), title = title)
            }
        }
        return when {
            uri.scheme == "bilibili" && host == "video" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Video(IdUtils.av2bv(it), aid = it) } ?: Route.Web(url, title)
            uri.scheme == "bilibili" && host == "space" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Member(it) } ?: Route.Web(url, title)
            uri.scheme == "bilibili" && host == "live" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.LiveRoom(it) } ?: Route.Web(url, title)
            uri.scheme == "bilibili" && (host == "opus" || host == "following") -> segs.lastOrNull()?.takeIf { it.isDigitId() }?.let { Route.DynamicDetail(it) } ?: Route.Web(url, title)
            host.startsWith("space.bilibili.com") -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Member(it) } ?: Route.Web(url, title)
            host.startsWith("live.bilibili.com") -> segs.firstOrNull()?.toLongOrNull()?.let { Route.LiveRoom(it) } ?: Route.Web(url, title)
            host.startsWith("t.bilibili.com") -> segs.firstOrNull()?.takeIf { it.isDigitId() }?.let { Route.DynamicDetail(it) } ?: Route.Web(url, title)
            host.contains("bilibili.com") && path.startsWith("/opus/") -> segs.lastOrNull()?.takeIf { it.isDigitId() }?.let { Route.DynamicDetail(it) } ?: Route.Web(url, title)
            host.contains("bilibili.com") && path.startsWith("/read/cv") -> path.removePrefix("/read/cv").toLongOrNull()?.let { Route.Read(it, title) } ?: Route.Web(url, title)
            host.contains("bilibili.com") && path.startsWith("/bangumi/play/ss") -> Route.Bangumi(seasonId = path.removePrefix("/bangumi/play/ss").toLongOrNull() ?: 0)
            host.contains("bilibili.com") && path.startsWith("/bangumi/play/ep") -> Route.Bangumi(epId = path.removePrefix("/bangumi/play/ep").toLongOrNull() ?: 0)
            host.contains("bilibili.com") && path.startsWith("/video/av") -> path.removePrefix("/video/av").trimEnd('/').toLongOrNull()?.let { Route.Video(IdUtils.av2bv(it), aid = it) } ?: Route.Web(url, title)
            else -> Route.Web(url, title)
        }
    }
}

private val VoteIdRegex = Regex("""[?&]vote_id=(\d+)""")
private val DynamicIdRegex = Regex("""[?&]dynamic_id=(\d+)""")

/** A dynamic id is all digits: `t.bilibili.com/vote/…` or `/h5/…` must not become DynamicDetail("vote"). */
private fun String.isDigitId(): Boolean = isNotEmpty() && all { it in '0'..'9' }

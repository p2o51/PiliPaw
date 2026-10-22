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

    fun push(route: Route) = pushKey(route)

    /** Any page of the app, the YouTube part's included (yt.navigation.Route). */
    fun pushKey(route: NavKey) {
        if (route is Route.Video) {
            val trail = com.gpo.pilipala.feature.sources.WatchTrail
            // 继续播放: a video opened on the video page (a related one, a link) takes the page's
            // place there instead of stacking another page.
            if (trail.inPlace?.invoke(route) == true) return
            // Any other open starts a new watch (the docked picture reopening its page keeps it).
            if (!com.gpo.pilipala.feature.sources.KeepPlaying.openingFromDock) trail.clear()
        }
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

    fun replace(route: Route) = replaceKey(route)

    fun replaceKey(route: NavKey) {
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

/**
 * Maps bilibili URLs / schemes to native routes; anything it doesn't know (festival and
 * activity pages, b23.tv short links that weren't expanded first) opens in the WebView.
 * Links from outside the app go through [LinkResolver.expand] first, which turns
 * b23.tv / bili2233.cn short links into the page they redirect to.
 */
object LinkRouter {
    fun route(raw: String, title: String = ""): Route {
        val url = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Route.Web(url, title)
        val scheme = uri.scheme.orEmpty().lowercase()
        val host = uri.host.orEmpty().lowercase()
        // Mobile share links sometimes come as /s/video/…: the same page.
        val path = uri.path.orEmpty().removePrefix("/s/").let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
        val segs = path.split('/').filter { it.isNotEmpty() }
        val bili = scheme == "bilibili" || host == "bilibili.com" || host.endsWith(".bilibili.com") || LinkResolver.isShortHost(host)
        if (!bili) return Route.Web(url, title)
        IdUtils.findBvid(url)?.let { return Route.Video(it) }
        // Votes: the web vote page t.bilibili.com/vote/h5/index/#/result?vote_id=… (also what inline
        // RICH_TEXT_NODE_TYPE_VOTE links point at) opens the native vote page. Other pages that
        // merely carry a vote_id (activity pages in the WebView) stay where they are.
        if (host == "t.bilibili.com" && path.startsWith("/vote/")) {
            VoteIdRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { voteId ->
                val dynId = DynamicIdRegex.find(url)?.groupValues?.get(1).orEmpty()
                return Route.Vote(voteId, dynamicId = dynId.takeIf { it.isDigitId() }.orEmpty(), title = title)
            }
        }
        if (scheme == "bilibili") return schemeRoute(uri, host, segs, url, title)
        val web = Route.Web(url, title)
        return when {
            host == "space.bilibili.com" -> segs.firstOrNull()?.toLongOrNull()?.let { Route.Member(it) } ?: web
            // live.bilibili.com/123, /h5/123, /blanc/123
            host == "live.bilibili.com" -> segs.lastOrNull()?.takeIf { it.isDigitId() }?.toLongOrNull()?.let { Route.LiveRoom(it) } ?: web
            host == "t.bilibili.com" -> segs.firstOrNull()?.takeIf { it.isDigitId() }?.let { Route.DynamicDetail(it) } ?: web
            path.startsWith("/opus/") || path.startsWith("/dynamic/") ->
                segs.getOrNull(1)?.takeIf { it.isDigitId() }?.let { Route.DynamicDetail(it) } ?: web
            // m.bilibili.com/space/123
            path.startsWith("/space/") -> segs.getOrNull(1)?.toLongOrNull()?.let { Route.Member(it) } ?: web
            path.startsWith("/read/cv") -> segs.getOrNull(1)?.removePrefix("cv")?.toLongOrNull()?.let { Route.Read(it, title) } ?: web
            // m.bilibili.com/read/mobile/123, /read/mobile?id=123
            path.startsWith("/read/mobile") ->
                (segs.getOrNull(2) ?: uri.getQueryParameter("id"))?.removePrefix("cv")?.toLongOrNull()?.let { Route.Read(it, title) } ?: web
            path.startsWith("/bangumi/play/ss") -> segs.getOrNull(2)?.removePrefix("ss")?.toLongOrNull()?.let { Route.Bangumi(seasonId = it) } ?: web
            path.startsWith("/bangumi/play/ep") -> segs.getOrNull(2)?.removePrefix("ep")?.toLongOrNull()?.let { Route.Bangumi(epId = it) } ?: web
            path.startsWith("/video/av") -> segs.getOrNull(1)?.removePrefix("av")?.toLongOrNull()?.let { Route.Video(IdUtils.av2bv(it), aid = it) } ?: web
            // /festival/…, /blackboard/…, the home page and the rest.
            else -> web
        }
    }

    /** bilibili://video/170001, //space/2, //live/1, //opus/…, //bangumi/season/1, //pgc/season/ep/1, //article/1, //search?keyword= */
    private fun schemeRoute(uri: Uri, host: String, segs: List<String>, url: String, title: String): Route {
        val web = Route.Web(url, title)
        val first = segs.firstOrNull()
        return when (host) {
            "video" -> first?.toLongOrNull()?.let { Route.Video(IdUtils.av2bv(it), aid = it) } ?: web
            "space", "author" -> first?.toLongOrNull()?.let { Route.Member(it) } ?: web
            "live" -> first?.toLongOrNull()?.let { Route.LiveRoom(it) } ?: web
            "opus", "following" -> segs.lastOrNull()?.takeIf { it.isDigitId() }?.let { Route.DynamicDetail(it) } ?: web
            "article" -> first?.toLongOrNull()?.let { Route.Read(it, title) } ?: web
            "bangumi" -> when (first) {
                "season" -> segs.getOrNull(1)?.toLongOrNull()?.let { Route.Bangumi(seasonId = it) } ?: web
                "ep" -> segs.getOrNull(1)?.toLongOrNull()?.let { Route.Bangumi(epId = it) } ?: web
                else -> web
            }
            "pgc" -> when {
                first == "season" && segs.getOrNull(1) == "ep" -> segs.getOrNull(2)?.toLongOrNull()?.let { Route.Bangumi(epId = it) } ?: web
                first == "season" -> segs.getOrNull(1)?.toLongOrNull()?.let { Route.Bangumi(seasonId = it) } ?: web
                else -> web
            }
            "search" -> uri.getQueryParameter("keyword")?.takeIf { it.isNotBlank() }?.let { Route.SearchResult(it) } ?: web
            else -> web
        }
    }
}

/**
 * Links that arrive from outside the app: b23.tv / bili2233.cn short links are expanded
 * (read-only requests, redirects followed by hand, a few hops at most) into the page they
 * point at; [firstLink] pulls a link out of shared text.
 */
object LinkResolver {
    private val ShortHosts = setOf("b23.tv", "www.b23.tv", "bili2233.cn", "www.bili2233.cn", "bili22.cn", "bili33.cn")

    private const val MaxHops = 5
    private const val TimeoutMs = 6_000L

    fun isShortHost(host: String): Boolean = host.lowercase() in ShortHosts

    private val client by lazy {
        com.gpo.pilipala.core.net.BiliClient.http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(TimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /** The first bilibili / short link in [text] (a share from the official app: "【title】 https://b23.tv/xxxx"). */
    fun firstLink(text: String): String? {
        LinkRegex.find(text)?.value?.let { found ->
            val trimmed = found.trimEnd('.', ',', '。', '，', ')', '）', '】', '」', '"', '\'')
            return if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
        }
        // No link, only an id ("BV1xx411c7mD"): the video.
        return IdUtils.findBvid(text)?.let { "https://www.bilibili.com/video/$it" }
    }

    /** [url] with any short link expanded; [url] itself when it isn't one or the expansion fails. */
    suspend fun expand(url: String): String {
        var current = url
        repeat(MaxHops) {
            val uri = runCatching { Uri.parse(current) }.getOrNull() ?: return current
            if (!isShortHost(uri.host.orEmpty())) return current
            // b23.tv/BV… carries the id already.
            if (IdUtils.findBvid(uri.path.orEmpty()) != null) return current
            val next = runCatching { location(current) }.getOrNull() ?: return current
            current = next
        }
        return current
    }

    /** One GET (no body read), the Location it answers with. */
    private suspend fun location(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isRedirect) return@withContext null
            val loc = res.header("Location") ?: return@withContext null
            res.request.url.resolve(loc)?.toString()
        }
    }

    private val LinkRegex = Regex(
        """(https?://)?([a-z0-9-]+\.)*(bilibili\.com|b23\.tv|bili2233\.cn|bili22\.cn|bili33\.cn)(/[^\s"'<>，。、）】」]*)?""",
        RegexOption.IGNORE_CASE,
    )
}

private val VoteIdRegex = Regex("""[?&]vote_id=(\d+)""")
private val DynamicIdRegex = Regex("""[?&]dynamic_id=(\d+)""")

/** A dynamic id is all digits: `t.bilibili.com/vote/…` or `/h5/…` must not become DynamicDetail("vote"). */
private fun String.isDigitId(): Boolean = isNotEmpty() && all { it in '0'..'9' }

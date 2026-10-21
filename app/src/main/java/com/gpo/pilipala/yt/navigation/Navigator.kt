package com.gpo.pilipala.yt.navigation

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.navigation.transition.PageTransitions

/**
 * App-wide navigation handle: `LocalNavigator.current.push(Route.Channel(url))`.
 *
 * Every change goes through the host's [PageTransitions] once AppNavHost has attached it:
 * a push freezes the page it covers (what a later back reveals); a pop plays the same
 * commit ride as a 3-button back and removes the entry only when it lands.
 */
class Navigator(
    internal val backStack: NavBackStack<NavKey>,
    private val context: Context,
    /** The app's one navigator: YouTube pages push onto the same stack as Bilibili's. */
    private val host: com.gpo.pilipala.navigation.Navigator? = null,
) {
    internal var transitions: PageTransitions? = null

    fun push(route: Route) {
        host?.let { it.pushKey(route); return }
        transitions?.beforePush(route)
        backStack.add(route)
    }

    /** True when there was a page to leave; the entry is removed once the close motion lands. */
    fun pop(): Boolean {
        host?.let { return it.pop() }
        if (backStack.size <= 1) return false
        val t = transitions
        if (t != null) t.requestPop() else backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun replace(route: Route) {
        host?.let { it.replaceKey(route); return }
        transitions?.beforeReplace(route)
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    fun popToMain() {
        host?.let { it.popToMain(); return }
        while (backStack.size > 2) backStack.removeAt(backStack.lastIndex - 1)
        pop()
    }

    /** A YouTube link opens natively (video → the player, channel → its page); anything else in a Custom Tab. */
    fun openUrl(url: String) {
        when (val link = LinkRouter.parse(url)) {
            is LinkRouter.Link.Video -> Player.play(link.url)
            is LinkRouter.Link.Channel -> push(Route.Channel(link.url))
            is LinkRouter.Link.Search -> push(Route.SearchResult(link.query))
            null -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("LocalNavigator not provided") }

/** youtube.com / youtu.be / m.youtube.com links → what they open in the app. */
object LinkRouter {
    sealed interface Link {
        data class Video(val url: String) : Link
        data class Channel(val url: String) : Link
        data class Search(val query: String) : Link
    }

    fun parse(raw: String): Link? {
        val url = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val segs = uri.pathSegments.orEmpty()
        return when (host) {
            "youtu.be" -> segs.firstOrNull()?.let { Link.Video("https://www.youtube.com/watch?v=$it") }
            "youtube.com", "youtube-nocookie.com" -> when {
                uri.getQueryParameter("v") != null -> Link.Video("https://www.youtube.com/watch?v=${uri.getQueryParameter("v")}")
                segs.firstOrNull() in setOf("shorts", "live", "embed") && segs.size > 1 -> Link.Video("https://www.youtube.com/watch?v=${segs[1]}")
                segs.firstOrNull()?.startsWith("@") == true -> Link.Channel("https://www.youtube.com/${segs[0]}")
                segs.firstOrNull() in setOf("channel", "c", "user") && segs.size > 1 -> Link.Channel("https://www.youtube.com/${segs[0]}/${segs[1]}")
                segs.firstOrNull() == "results" -> uri.getQueryParameter("search_query")?.let { Link.Search(it) }
                else -> null
            }
            else -> null
        }
    }

    /** The first YouTube link in shared text ("Check this out https://youtu.be/…"). */
    fun firstLink(text: String): String? =
        LinkRegex.find(text)?.value?.trimEnd('.', ',', '。', '，', ')', '）')?.let { if (it.startsWith("http")) it else "https://$it" }

    private val LinkRegex = Regex("""(https?://)?([a-z0-9-]+\.)*(youtube\.com|youtu\.be)(/[^\s"'<>，。]*)?""", RegexOption.IGNORE_CASE)
}

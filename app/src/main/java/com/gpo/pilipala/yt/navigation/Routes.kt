package com.gpo.pilipala.yt.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every pushed destination. `Main` is the shell (floating bar sections); the rest push on top
 * with the page transitions. The player is NOT a route: it is the shell overlay
 * (feature/player, predictive-back class ShellOverlayDown) and floats over every page.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Main : Route

    /** [hint]: the field starts with this text (editing the last query). */
    @Serializable data class Search(val hint: String = "") : Route
    @Serializable data class SearchResult(val query: String) : Route

    @Serializable data class Channel(val url: String, val name: String = "", val avatar: String = "") : Route

    @Serializable data object Settings : Route

    /** Google's sign-in page in a WebView (feature/account). */
    @Serializable data object SignIn : Route

    /** A signed-in account list ([com.gpo.pilipala.yt.feature.account.AccountList] name). */
    @Serializable data class AccountVideos(val list: String) : Route

    /** 本机 history / watch later ([com.gpo.pilipala.yt.feature.library.LocalList] name). */
    @Serializable data class LocalList(val kind: String) : Route
    @Serializable data object Playlists : Route
    @Serializable data class LocalPlaylist(val id: String) : Route

    /** 新建 / 编辑 Feed ([id] null: a new one). */
    @Serializable data class FeedEditor(val id: String? = null) : Route
    @Serializable data object Mutes : Route
}

package com.gpo.pilipala.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every destination. `Main` is the shell (bottom bar tabs); everything else is
 * pushed on top with the native-style slide. Screens receive their route object.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Main : Route

    // ── Video / PGC / Live ──────────────────────────────────────────────────
    @Serializable data class Video(
        val bvid: String,
        val cid: Long = 0,
        val aid: Long = 0,
        val cover: String = "",
        val title: String = "",
        /** Resume position, seconds. */
        val progress: Long = 0,
        /** In-memory play queue (`PlayQueues`) this video plays from; empty = a single video. */
        val playlistId: String = "",
    ) : Route

    @Serializable data class Bangumi(
        val seasonId: Long = 0,
        val epId: Long = 0,
        val cover: String = "",
        /** Resume position of [epId], seconds (继续看). */
        val progress: Long = 0,
    ) : Route
    @Serializable data class LiveRoom(val roomId: Long, val cover: String = "", val title: String = "") : Route

    // ── Search ──────────────────────────────────────────────────────────────
    @Serializable data class Search(val hint: String = "") : Route
    @Serializable data class SearchResult(val keyword: String) : Route

    // ── Custom feeds ────────────────────────────────────────────────────────
    /** Feed editor: [feedId] edits that feed; empty creates one, prefilled with [keyword] ([order]) or the UP [upMid]. */
    @Serializable data class FeedEditor(
        val feedId: String = "",
        val keyword: String = "",
        val order: String = "",
        val upMid: Long = 0,
        val upName: String = "",
        val upFace: String = "",
    ) : Route

    // ── Member (UP space) ───────────────────────────────────────────────────
    @Serializable data class Member(val mid: Long, val face: String = "", val name: String = "") : Route
    @Serializable data class MemberArchive(val mid: Long, val name: String = "") : Route
    @Serializable data class MemberDynamics(val mid: Long, val name: String = "") : Route
    @Serializable data class MemberSeasons(val mid: Long, val seasonId: Long, val category: Int = 0, val title: String = "") : Route
    @Serializable data class MemberCoin(val mid: Long) : Route
    @Serializable data class MemberLike(val mid: Long) : Route
    @Serializable data class MemberArticle(val mid: Long) : Route

    // ── Dynamics / replies / articles ───────────────────────────────────────
    @Serializable data class DynamicDetail(val id: String) : Route
    /** New dynamic (text, emotes, up to 9 pictures); grows out of the 动态 tab's FAB. */
    @Serializable data object DynamicCompose : Route
    /** Reply thread. type: 1 = video, 11/17 = dynamic, 12 = article … */
    @Serializable data class ReplyDetail(val oid: Long, val type: Int, val rpid: Long) : Route
    @Serializable data class Opus(val id: String, val title: String = "") : Route
    @Serializable data class Read(val cvid: Long, val title: String = "") : Route
    /** Native vote page (t.bilibili.com/vote links, the vote block of a dynamic). */
    @Serializable data class Vote(val voteId: Long, val dynamicId: String = "", val title: String = "") : Route

    // ── Library (mine) ──────────────────────────────────────────────────────
    @Serializable data class Fav(val mid: Long = 0) : Route
    @Serializable data class FavDetail(val mediaId: Long, val title: String = "", val cover: String = "") : Route
    @Serializable data object Later : Route
    @Serializable data object History : Route
    @Serializable data class Subscription(val mid: Long) : Route
    @Serializable data class SubDetail(val seasonId: Long, val type: Int, val title: String = "", val cover: String = "") : Route
    @Serializable data class Follow(val mid: Long, val name: String = "") : Route
    @Serializable data class Fans(val mid: Long, val name: String = "") : Route
    @Serializable data object Blacklist : Route
    /** 离线缓存 (feature/download). */
    @Serializable data object Downloads : Route
    /** A finished cache, played from its file ([id]: the cache's id). */
    @Serializable data class OfflinePlayer(val id: String) : Route

    // ── Messages ────────────────────────────────────────────────────────────
    @Serializable data object Messages : Route
    /** kind: "reply" | "at" | "like" | "system" */
    @Serializable data class MessageList(val kind: String) : Route
    @Serializable data class WhisperDetail(val talkerId: Long, val name: String = "", val face: String = "") : Route

    // ── Account / settings / misc ───────────────────────────────────────────
    @Serializable data object Login : Route
    @Serializable data object Settings : Route
    /** page: "play" | "style" | "danmaku" | "privacy" | "recommend" | "extra" */
    @Serializable data class SettingsPage(val page: String) : Route
    @Serializable data object About : Route
    @Serializable data class Web(val url: String, val title: String = "") : Route
}

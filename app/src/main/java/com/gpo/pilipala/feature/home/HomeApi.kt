package com.gpo.pilipala.feature.home

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.AppSign
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.has
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── Feature-owned settings (recommend filter, mirrors Flutter's RecommendFilter) ──

/** Seconds; recommended videos shorter than this are dropped (0 = off). */
internal val RcmdMinDuration = AppSettings.register("rcmd_min_duration", 0)

/** Percent; videos whose like/view ratio is below this are dropped (0 = off). */
internal val RcmdMinLikeRatio = AppSettings.register("rcmd_min_like_ratio", 0)

/** Followed UPs skip the filters above. */
internal val RcmdExemptFollowed = AppSettings.register("rcmd_exempt_followed", true)

/** App-API access token (set by the login flow when it obtains one). Empty = none. */
internal val AppAccessKey = AppSettings.register("access_key", "")

// ── Models ──────────────────────────────────────────────────────────────────

@Immutable
internal data class LiveRoomItem(
    val roomId: Long,
    val title: String,
    val cover: String,
    val uname: String,
    val face: String,
    val area: String,
    /** "1.2万" style watched count, already formatted by the API. */
    val watched: String,
    val online: Long,
)

@Immutable
internal data class LiveFollowItem(
    val roomId: Long,
    val uname: String,
    val face: String,
    val title: String,
    val cover: String,
    val area: String,
)

@Immutable
internal data class BangumiItem(
    val seasonId: Long,
    val title: String,
    val cover: String,
    val badge: String,
    val indexShow: String,
    /** "553万追番" */
    val order: String,
    val score: String,
    /** "看到第3话" (follow list only). */
    val progress: String,
)

// ── Parsing helpers ─────────────────────────────────────────────────────────

/** "28.6万" / "1.2亿" / "1234" → number, -1 when unknown. */
internal fun parseCount(text: String): Long {
    val s = text.trim()
    if (s.isEmpty() || s == "-") return -1
    return runCatching {
        when {
            s.endsWith("亿") -> (s.dropLast(1).toDouble() * 100_000_000).toLong()
            s.endsWith("万") -> (s.dropLast(1).toDouble() * 10_000).toLong()
            else -> s.toLong()
        }
    }.getOrDefault(-1)
}

private fun JsonElement?.looseText(): String = when (this) {
    is JsonPrimitive -> if (isString) content else content.takeIf { it != "null" }.orEmpty()
    is JsonObject -> str("text").ifEmpty { str("content") }
    else -> ""
}

private fun bangumiRoute(uri: String, param: String, cover: String): Route? {
    Regex("ep/?(\\d+)").find(uri)?.groupValues?.get(1)?.toLongOrNull()?.let { return Route.Bangumi(epId = it, cover = cover) }
    Regex("season/?(\\d+)").find(uri)?.groupValues?.get(1)?.toLongOrNull()?.let { return Route.Bangumi(seasonId = it, cover = cover) }
    Regex("/ss(\\d+)").find(uri)?.groupValues?.get(1)?.toLongOrNull()?.let { return Route.Bangumi(seasonId = it, cover = cover) }
    return param.toLongOrNull()?.takeIf { it > 0 }?.let { Route.Bangumi(seasonId = it, cover = cover) }
}

/** Standard archive object (popular, ranking, related…). */
internal fun JsonObject.toArchiveItem(): VideoItem {
    val owner = obj("owner")
    val stat = obj("stat")
    val cover = str("pic")
    val redirect = str("redirect_url")
    val target = redirect.takeIf { it.isNotEmpty() }?.let { LinkRouter.route(it) }?.takeIf { it is Route.Bangumi }
    return VideoItem(
        bvid = str("bvid"),
        aid = long("aid"),
        cid = long("cid"),
        title = str("title"),
        cover = cover,
        duration = long("duration"),
        ownerMid = owner.long("mid"),
        ownerName = owner.str("name"),
        ownerFace = owner.str("face"),
        views = stat.long("view", -1),
        danmaku = stat.long("danmaku", -1),
        likes = stat.long("like", -1),
        pubdate = long("pubdate"),
        reason = obj("rcmd_reason").str("content"),
        target = target,
    )
}

/** Web recommend item (`/x/web-interface/wbi/index/top/feed/rcmd`). */
private fun JsonObject.toWebRcmd(): Pair<VideoItem, Boolean>? {
    val goto = str("goto")
    val owner = obj("owner")
    val stat = obj("stat")
    val followed = int("is_followed") == 1
    val cover = str("pic")
    val title = str("title")
    val base = VideoItem(
        bvid = str("bvid"),
        aid = long("id"),
        cid = long("cid"),
        title = title,
        cover = cover,
        duration = long("duration"),
        ownerMid = owner.long("mid"),
        ownerName = owner.str("name"),
        ownerFace = owner.str("face"),
        views = stat.long("view", -1),
        danmaku = stat.long("danmaku", -1),
        likes = stat.long("like", -1),
        pubdate = long("pubdate"),
        badge = if (followed) FollowedBadge else "",
        reason = obj("rcmd_reason").str("content"),
    )
    val item = when (goto) {
        "av" -> base.takeIf { it.bvid.isNotEmpty() || it.aid > 0 }
            ?.let { if (it.bvid.isEmpty()) it.copy(bvid = IdUtils.av2bv(it.aid)) else it }
        "live" -> {
            val roomId = obj("room_info").long("room_id").takeIf { it > 0 }
                ?: (LinkRouter.route(str("uri")) as? Route.LiveRoom)?.roomId
            roomId?.let { base.copy(badge = "直播", target = Route.LiveRoom(it, cover, title)) }
        }
        "ogv" -> bangumiRoute(str("uri"), "", cover)?.let { base.copy(badge = "番剧", target = it) }
        else -> null
    } ?: return null
    return item to followed
}

/** App recommend item (`app.bilibili.com/x/v2/feed/index`). */
private fun JsonObject.toAppRcmd(): Triple<VideoItem, Boolean, AppFeedRef>? {
    val cardGoto = str("card_goto")
    if (cardGoto.startsWith("ad") || has("ad_info")) return null
    val goto = str("goto")
    val args = obj("args")
    val player = obj("player_args")
    val cover = str("cover")
    val title = str("title")
    val reasonRaw = get("bottom_rcmd_reason").looseText().ifEmpty { get("top_rcmd_reason").looseText() }
    val followed = Regex("已关注|新关注").containsMatchIn(reasonRaw)
    val reason = if (followed) "" else reasonRaw
    val param = str("param")
    val ref = AppFeedRef(goto = goto, id = param, reasonId = dislikeReasonId())
    val pair: Pair<VideoItem, Boolean> = when (goto) {
        "av" -> {
            val aid = player.long("aid").takeIf { it > 0 } ?: param.toLongOrNull() ?: return null
            VideoItem(
                bvid = IdUtils.av2bv(aid),
                aid = aid,
                cid = player.long("cid"),
                title = title,
                cover = cover,
                duration = player.long("duration").takeIf { it > 0 } ?: 0,
                ownerMid = args.long("up_id"),
                ownerName = args.str("up_name"),
                views = parseCount(str("cover_left_text_1")),
                danmaku = parseCount(str("cover_left_text_2")),
                badge = if (followed) FollowedBadge else "",
                reason = reason,
            ) to followed
        }
        "bangumi", "pgc" -> {
            val route = bangumiRoute(str("uri"), param, cover) ?: return null
            VideoItem(
                title = title,
                cover = cover,
                ownerName = obj("desc_button").str("text"),
                views = parseCount(str("cover_left_text_1")),
                badge = str("badge").ifEmpty { "番剧" },
                reason = reason,
                target = route,
            ) to followed
        }
        "live" -> {
            val roomId = param.toLongOrNull() ?: return null
            VideoItem(
                title = title,
                cover = cover,
                ownerName = args.str("up_name").ifEmpty { obj("desc_button").str("text") },
                badge = "直播",
                reason = reason,
                target = Route.LiveRoom(roomId, cover, title),
            ) to followed
        }
        else -> null
    } ?: return null
    return Triple(pair.first, pair.second, ref)
}

/** The "不感兴趣" reason from `three_point_v2` / `three_point`, if the card offers one. */
private fun JsonObject.dislikeReasonId(): Int? {
    val reasons = arr("three_point_v2").objects()
        .filter { it.str("type") == "dislike" }
        .flatMap { it.arr("reasons").objects() } + obj("three_point").arr("dislike_reasons").objects()
    val pick = reasons.firstOrNull { it.str("name").contains("不感兴趣") } ?: reasons.firstOrNull()
    return pick?.int("id", -1)?.takeIf { it >= 0 }
}

/** Cover badge of a recommended video by an UP the user follows; [MutualBadge] when they follow back. */
private const val FollowedBadge = "已关注"
private const val MutualBadge = "互相关注"

/**
 * The 已关注 badges of a page read 互相关注 for UPs who follow the user back: one read-only
 * GET /x/relation/relations for the page's followed UPs (attribute 6), logged in only. A failed
 * lookup keeps 已关注.
 */
private suspend fun List<VideoItem>.withMutualBadges(): List<VideoItem> {
    if (!BiliClient.isLoggedIn) return this
    val mids = filter { it.badge == FollowedBadge && it.ownerMid > 0 }.map { it.ownerMid }.distinct().take(50)
    if (mids.isEmpty()) return this
    val mutual = try {
        val data = BiliClient.getData("/x/relation/relations", mapOf("fids" to mids.joinToString(","))).asObj
        mids.filter { data.obj(it.toString()).int("attribute") == 6 }.toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptySet()
    }
    if (mutual.isEmpty()) return this
    return map { if (it.badge == FollowedBadge && it.ownerMid in mutual) it.copy(badge = MutualBadge) else it }
}

/** Flutter's RecommendFilter: true = keep. */
private fun keepRcmd(item: VideoItem, followed: Boolean): Boolean {
    if (followed && RcmdExemptFollowed.value) return true
    val minDuration = RcmdMinDuration.value
    if (minDuration > 0 && item.duration > 0 && item.duration < minDuration) return false
    val ratio = RcmdMinLikeRatio.value
    if (ratio > 0 && item.views > 0 && item.likes >= 0 && item.likes * 100 < ratio * item.views) return false
    return true
}

// ── Endpoints ───────────────────────────────────────────────────────────────

internal object HomeApi {
    private val liveHeaders = mapOf("Referer" to "https://live.bilibili.com/")

    suspend fun searchDefault(): String {
        val data = BiliClient.getData("/x/web-interface/wbi/search/default", wbi = true).asObj
        return data.str("show_name").ifEmpty { data.str("name") }
    }

    suspend fun rcmdWeb(freshIdx: Int, ps: Int = 20): List<VideoItem> {
        val data = BiliClient.getData(
            "/x/web-interface/wbi/index/top/feed/rcmd",
            mapOf(
                "version" to 1,
                "feed_version" to "V3",
                "homepage_ver" to 1,
                "ps" to ps,
                "fresh_idx" to freshIdx,
                "brush" to freshIdx,
                "fresh_type" to 4,
            ),
            wbi = true,
        ).asObj
        return data.arr("item").objects()
            .mapNotNull { it.toWebRcmd() }
            .filter { (item, followed) -> keepRcmd(item, followed) }
            .map { it.first }
            .withoutBlocked()
            .withMutualBadges()
    }

    class AppRcmdPage(val items: List<VideoItem>, val refs: List<AppFeedRef>, val nextIdx: Long)

    /** Items (with their dislike refs, same order) and the smallest `idx` (the next cursor). */
    suspend fun rcmdApp(idx: Long, pull: Boolean, accessKey: String): AppRcmdPage {
        val params = buildMap {
            put("idx", idx.toString())
            put("flush", "5")
            put("column", "4")
            put("device", "pad")
            put("device_type", "0")
            put("device_name", "vivo")
            put("pull", if (pull) "true" else "false")
            if (accessKey.isNotEmpty()) put("access_key", accessKey)
            put("ts", (System.currentTimeMillis() / 1000).toString())
        }
        val data = BiliClient.getData(BiliHosts.APP + "/x/v2/feed/index", AppSign.sign(params)).asObj
        val raw = data.arr("items").objects()
        val nextIdx = raw.minOfOrNull { it.long("idx") }?.takeIf { it > 0 } ?: (idx + raw.size)
        val blocked = Blacklist.mids.value
        val list = raw.mapNotNull { it.toAppRcmd() }
            .filter { (item, followed, _) -> keepRcmd(item, followed) && item.ownerMid !in blocked }
        // Same length and order as the refs: the badge pass only relabels.
        return AppRcmdPage(list.map { it.first }.withMutualBadges(), list.map { it.third }, nextIdx)
    }

    /** Popular list; second = has more. */
    suspend fun hot(pn: Int, ps: Int = 20): Pair<List<VideoItem>, Boolean> {
        val data = BiliClient.getData("/x/web-interface/popular", mapOf("pn" to pn, "ps" to ps)).asObj
        val all = data.arr("list").objects().map { it.toArchiveItem() }.filter { it.bvid.isNotEmpty() }
        val list = all.withoutBlocked()
        return list to (!data.bool("no_more") && all.isNotEmpty())
    }

    suspend fun liveRecommend(page: Int): Pair<List<LiveRoomItem>, Boolean> {
        val data = BiliClient.getData(
            BiliHosts.LIVE + "/xlive/web-interface/v1/second/getUserRecommend",
            mapOf("page" to page, "page_size" to 30, "platform" to "web"),
            wbi = true,
            headers = liveHeaders,
        ).asObj
        val list = data.arr("list").objects().map { o ->
            val watched = o.obj("watched_show")
            LiveRoomItem(
                roomId = o.long("roomid"),
                title = o.str("title"),
                cover = o.str("cover").ifEmpty { o.str("user_cover") }.ifEmpty { o.str("system_cover") },
                uname = o.str("uname"),
                face = o.str("face"),
                area = o.str("area_v2_name").ifEmpty { o.str("area_name") },
                watched = if (watched.bool("switch", true)) watched.str("text_small") else "",
                online = o.long("online"),
            )
        }.filter { it.roomId > 0 }
        val hasMore = if (data?.containsKey("has_more") == true) data.int("has_more") == 1 || data.bool("has_more") else list.isNotEmpty()
        return list to (hasMore && list.isNotEmpty())
    }

    /** Followed UPs that are live right now (logged in only). */
    suspend fun liveFollowing(): List<LiveFollowItem> {
        val data = BiliClient.getData(
            BiliHosts.LIVE + "/xlive/web-ucenter/user/following",
            mapOf("page" to 1, "page_size" to 20, "platform" to "web", "ignoreRecord" to 1, "hit_ab" to true),
            headers = liveHeaders,
        ).asObj
        return data.arr("list").objects()
            .filter { it.int("live_status") == 1 && it.long("record_live_time") == 0L }
            .map { o ->
                LiveFollowItem(
                    roomId = o.long("roomid"),
                    uname = o.str("uname"),
                    face = o.str("face"),
                    title = o.str("title"),
                    cover = o.str("room_cover"),
                    area = o.str("area_name_v2").ifEmpty { o.str("area_name") },
                )
            }
            .filter { it.roomId > 0 }
    }

    private fun JsonObject.toBangumi(): BangumiItem = BangumiItem(
        seasonId = long("season_id"),
        title = str("title"),
        cover = str("cover"),
        badge = str("badge"),
        indexShow = str("index_show").ifEmpty { obj("new_ep").str("index_show") },
        order = str("order"),
        score = str("score"),
        progress = str("progress"),
    )

    suspend fun bangumiIndex(page: Int): Pair<List<BangumiItem>, Boolean> {
        val data = BiliClient.getData(
            "/pgc/season/index/result",
            mapOf(
                "st" to 1, "order" to 3, "season_version" to -1, "spoken_language_type" to -1, "area" to -1,
                "is_finish" to -1, "copyright" to -1, "season_status" to -1, "season_month" to -1, "year" to -1,
                "style_id" to -1, "sort" to 0, "season_type" to 1, "pagesize" to 20, "type" to 1, "page" to page,
            ),
        ).asObj
        val list = data.arr("list").objects().map { it.toBangumi() }.filter { it.seasonId > 0 }
        return list to (data.int("has_next") == 1 && list.isNotEmpty())
    }

    suspend fun bangumiFollow(mid: Long): List<BangumiItem> {
        val data = BiliClient.getData(
            "/x/space/bangumi/follow/list",
            mapOf("type" to 1, "follow_status" to 0, "pn" to 1, "ps" to 15, "vmid" to mid),
        ).asObj
        return data.arr("list").objects().map { it.toBangumi() }.filter { it.seasonId > 0 }
    }
}

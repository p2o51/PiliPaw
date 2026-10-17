package com.gpo.pilipala.feature.member

import android.util.Base64
import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/** Everything the space header shows. Merged from card + acc/info + upstat. */
@Immutable
internal data class MemberProfile(
    val mid: Long,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    val level: Int = 0,
    val isSenior: Boolean = false,
    val vip: Boolean = false,
    val vipLabel: String = "",
    val officialRole: Int = 0,
    val officialTitle: String = "",
    val following: Long = -1,
    val fans: Long = -1,
    val likes: Long = -1,
    val views: Long = -1,
    val archiveCount: Long = -1,
    val articleCount: Long = -1,
    val banner: String = "",
    val liveRoomId: Long = 0,
    val liveOn: Boolean = false,
    val liveTitle: String = "",
    val liveCover: String = "",
    val isFollowedCard: Boolean = false,
) {
    val officialLabel: String
        get() = when {
            officialTitle.isEmpty() -> ""
            officialRole in setOf(1, 2, 7, 9) -> "个人认证"
            else -> "机构认证"
        }
}

/** /x/relation attribute: 0 none, 1 whisper-follow, 2 following, 6 mutual, 128 blocked. -1 = unknown. */
@Immutable
internal data class Relation(val attribute: Int = -1, val special: Boolean = false) {
    val following: Boolean get() = attribute == 1 || attribute == 2 || attribute == 6
    val blocked: Boolean get() = attribute == 128
    val label: String
        get() = when (attribute) {
            1 -> "悄悄关注"
            2 -> "已关注"
            6 -> "已互关"
            128 -> "已拉黑"
            else -> "关注"
        }
}

/** One 合集 (category 0) or 系列 (category 1) with its preview archives. */
@Immutable
internal data class MemberCollection(
    val id: Long,
    val category: Int,
    val title: String,
    val cover: String,
    val total: Int,
    val archives: List<VideoItem>,
)

@Immutable
internal data class MemberArticle(
    val id: Long,
    val title: String,
    val summary: String,
    val cover: String,
    val category: String,
    val views: Long,
    val likes: Long,
    val replies: Long,
    val publishTime: Long,
)

internal enum class ArchiveOrder(val key: String, val label: String) {
    Pubdate("pubdate", "最新发布"),
    Click("click", "最多播放"),
    Stow("stow", "最多收藏"),
}

internal object MemberRepo {

    // ── Profile ───────────────────────────────────────────────────────────

    suspend fun profile(mid: Long): MemberProfile = coroutineScope {
        val cardD = async { BiliClient.getData("/x/web-interface/card", mapOf("mid" to mid, "photo" to true)).asObj }
        val accD = async {
            runCatching {
                BiliClient.getData(
                    "/x/space/wbi/acc/info",
                    mapOf("mid" to mid, "token" to "", "platform" to "web", "web_location" to 1550101) + dmParams(),
                    wbi = true,
                ).asObj
            }.getOrNull()
        }
        val upD = async {
            if (!BiliClient.isLoggedIn) null
            else runCatching { BiliClient.getData("/x/space/upstat", mapOf("mid" to mid)).asObj }.getOrNull()
        }
        val data = cardD.await()
        val acc = accD.await()
        val up = upD.await()
        val card = data.obj("card")
        val official = card.obj("Official")
        val vip = card.obj("vip")
        val live = acc.obj("live_room")
        val upLikes = up.long("likes", -1)
        MemberProfile(
            mid = mid,
            name = acc.str("name").ifEmpty { card.str("name") },
            face = acc.str("face").ifEmpty { card.str("face") },
            sign = acc.str("sign").ifEmpty { card.str("sign") }.trim(),
            level = card.obj("level_info").int("current_level", acc.int("level")),
            isSenior = card.int("is_senior_member") == 1 || acc.int("is_senior_member") == 1,
            vip = vip.int("status") == 1 || vip.int("vipStatus") == 1,
            vipLabel = vip.obj("label").str("text"),
            officialRole = official.int("role"),
            officialTitle = official.str("title").ifEmpty { acc.obj("official").str("title") },
            following = card.long("attention", -1),
            fans = data.long("follower", card.long("fans", -1)),
            likes = if (upLikes > 0) upLikes else data.long("like_num", -1),
            views = up.obj("archive").long("view", -1),
            archiveCount = data.long("archive_count", -1),
            articleCount = data.long("article_count", -1),
            banner = acc.str("top_photo").ifEmpty { data.obj("space").str("l_img") },
            liveRoomId = live.long("roomid"),
            liveOn = live.int("liveStatus") == 1,
            liveTitle = live.str("title"),
            liveCover = live.str("cover"),
            isFollowedCard = data.bool("following"),
        )
    }

    suspend fun relation(mid: Long): Relation {
        if (!BiliClient.isLoggedIn || mid == BiliClient.mid) return Relation()
        val d = BiliClient.getData("/x/relation", mapOf("fid" to mid)).asObj
        return Relation(attribute = d.int("attribute"), special = d.int("special") == 1)
    }

    /** act: 1 follow, 2 unfollow, 5 block, 6 unblock. */
    suspend fun modifyRelation(mid: Long, act: Int) {
        BiliClient.postData("/x/relation/modify", mapOf("fid" to mid, "act" to act, "re_src" to 11))
    }

    // ── Archive ───────────────────────────────────────────────────────────

    class ArchivePage(val page: Page<VideoItem>, val total: Long)

    /**
     * cursor: null = first page, "<n>" = web page n, "app:<aid>" = app cursor.
     * The web endpoint (wbi + dm params) is risk-controlled (-352 / 412) on many
     * networks; on that we switch to the app-signed space/archive/cursor feed.
     */
    suspend fun archive(mid: Long, cursor: String?, order: ArchiveOrder, ps: Int = 30): ArchivePage {
        if (cursor?.startsWith("app:") == true) return appArchive(mid, cursor.removePrefix("app:"), order)
        val pn = cursor?.toIntOrNull() ?: 1
        val params = mapOf(
            "mid" to mid,
            "ps" to ps,
            "tid" to 0,
            "pn" to pn,
            "keyword" to "",
            "order" to order.key,
            "platform" to "web",
            "web_location" to 1550101,
            "order_avoided" to true,
        ) + dmParams()
        val root = try {
            BiliClient.getData("/x/space/wbi/arc/search", params, wbi = true).asObj
        } catch (e: BiliException) {
            if (pn == 1 && (e.code == -352 || e.code == -412 || e.code == 412)) return appArchive(mid, "", order)
            throw e
        }
        val list = root.obj("list").arr("vlist").objects().map { it.archiveItem(mid) }
        val total = root.obj("page").long("count")
        val hasMore = list.isNotEmpty() && pn.toLong() * ps < total
        return ArchivePage(Page(list, hasMore, (pn + 1).toString()), total)
    }

    private suspend fun appArchive(mid: Long, aid: String, order: ArchiveOrder): ArchivePage {
        val params = mutableMapOf(
            "vmid" to mid.toString(),
            "order" to order.key,
            "ps" to "20",
            "ts" to (System.currentTimeMillis() / 1000).toString(),
        )
        if (aid.isNotEmpty()) params["aid"] = aid
        val signed = com.gpo.pilipala.core.net.AppSign.sign(
            params,
            com.gpo.pilipala.core.net.AppSign.ANDROID_HD_KEY,
            com.gpo.pilipala.core.net.AppSign.ANDROID_HD_SEC,
        )
        val root = BiliClient.getData(
            "${com.gpo.pilipala.core.net.BiliHosts.APP}/x/v2/space/archive/cursor",
            signed,
            headers = mapOf("User-Agent" to "okhttp/5.0.0"),
        ).asObj
        val items = root.arr("item").objects()
        val list = items.map { o ->
            VideoItem(
                bvid = o.str("bvid"),
                aid = o.str("param").toLongOrNull() ?: 0,
                title = o.str("title"),
                cover = o.str("cover"),
                duration = o.long("duration"),
                ownerMid = mid,
                ownerName = o.str("author"),
                views = o.long("play", -1),
                danmaku = o.long("danmaku", -1),
                pubdate = o.long("ctime"),
                badge = if (o.bool("is_ugcpay") || o.int("ugc_pay") == 1) "付费" else "",
            )
        }
        val next = items.lastOrNull()?.str("param").orEmpty()
        return ArchivePage(Page(list, root.bool("has_next") && next.isNotEmpty(), "app:$next"), root.long("count"))
    }

    private fun JsonObject.archiveItem(mid: Long): VideoItem = VideoItem(
        bvid = str("bvid"),
        aid = long("aid"),
        title = Fmt.stripTags(str("title")),
        cover = str("pic"),
        duration = Fmt.parseDuration(str("length")),
        ownerMid = long("mid", mid),
        views = long("play", -1),
        danmaku = long("video_review", -1),
        pubdate = long("created"),
        badge = if (bool("is_charging_arc")) "充电专属" else "",
    )

    // ── Seasons & series ──────────────────────────────────────────────────

    suspend fun collections(mid: Long, page: Int, size: Int = 10): Page<MemberCollection> {
        val root = BiliClient.getData(
            "/x/polymer/web-space/seasons_series_list",
            mapOf("mid" to mid, "page_num" to page, "page_size" to size),
        ).asObj.obj("items_lists")
        val seasons = root.arr("seasons_list").objects().map { it.collection(0) }
        val series = root.arr("series_list").objects().map { it.collection(1) }
        val total = root.obj("page").int("total")
        val items = seasons + series
        return Page(items, items.isNotEmpty() && page * size < total, (page + 1).toString())
    }

    private fun JsonObject.collection(category: Int): MemberCollection {
        val meta = obj("meta")
        return MemberCollection(
            id = if (category == 0) meta.long("season_id") else meta.long("series_id"),
            category = category,
            title = meta.str("title").ifEmpty { meta.str("name") },
            cover = meta.str("cover"),
            total = meta.int("total"),
            archives = arr("archives").objects().map { it.seasonArchive() },
        )
    }

    private fun JsonObject.seasonArchive(): VideoItem = VideoItem(
        bvid = str("bvid"),
        aid = long("aid"),
        title = str("title"),
        cover = str("pic"),
        duration = long("duration"),
        views = obj("stat").long("view", -1),
        danmaku = obj("stat").long("danmaku", -1),
        pubdate = long("pubdate"),
    )

    class SeasonPage(val page: Page<VideoItem>, val total: Int)

    suspend fun seasonArchives(mid: Long, seasonId: Long, pn: Int, reverse: Boolean, ps: Int = 30): SeasonPage {
        val root = BiliClient.getData(
            "/x/polymer/web-space/seasons_archives_list",
            mapOf("mid" to mid, "season_id" to seasonId, "sort_reverse" to reverse, "page_num" to pn, "page_size" to ps),
        ).asObj
        val list = root.arr("archives").objects().map { it.seasonArchive() }
        val total = root.obj("page").int("total")
        return SeasonPage(Page(list, list.isNotEmpty() && pn * ps < total, (pn + 1).toString()), total)
    }

    suspend fun seriesArchives(mid: Long, seriesId: Long, pn: Int, reverse: Boolean, ps: Int = 30): SeasonPage {
        val root = BiliClient.getData(
            "/x/series/archives",
            mapOf(
                "mid" to mid,
                "series_id" to seriesId,
                "only_normal" to true,
                "sort" to if (reverse) "asc" else "desc",
                "pn" to pn,
                "ps" to ps,
            ),
        ).asObj
        val list = root.arr("archives").objects().map { it.seasonArchive() }
        val total = root.obj("page").int("total")
        return SeasonPage(Page(list, list.isNotEmpty() && pn * ps < total, (pn + 1).toString()), total)
    }

    // ── Coin / like ───────────────────────────────────────────────────────

    suspend fun coinVideos(mid: Long): List<VideoItem> {
        val data = BiliClient.getData(
            "/x/space/coin/video",
            mapOf("vmid" to mid, "gaia_source" to "main_web", "web_location" to "333.999"),
        )
        val list = data.asArr?.objects() ?: data.asObj.arr("list").objects()
        return list.map { it.ownedVideo() }
    }

    suspend fun likeVideos(mid: Long): List<VideoItem> {
        val data = BiliClient.getData(
            "/x/space/like/video",
            mapOf("vmid" to mid, "gaia_source" to "main_web", "web_location" to "333.999"),
        )
        val list = data.asObj?.arr("list")?.objects() ?: data.asArr?.objects().orEmpty()
        return list.map { it.ownedVideo() }
    }

    private fun JsonObject.ownedVideo(): VideoItem {
        val owner = obj("owner")
        val stat = obj("stat")
        return VideoItem(
            bvid = str("bvid"),
            aid = long("aid"),
            cid = long("cid"),
            title = str("title"),
            cover = str("pic"),
            duration = long("duration"),
            ownerMid = owner.long("mid"),
            ownerName = owner.str("name"),
            ownerFace = owner.str("face"),
            views = stat.long("view", -1),
            danmaku = stat.long("danmaku", -1),
            pubdate = long("pubdate"),
        )
    }

    // ── Articles ──────────────────────────────────────────────────────────

    suspend fun articles(mid: Long, pn: Int, ps: Int = 30): Page<MemberArticle> {
        val root = BiliClient.getData(
            "/x/space/wbi/article",
            mapOf("mid" to mid, "pn" to pn, "ps" to ps, "sort" to "publish_time", "platform" to "web", "web_location" to 1550101),
            wbi = true,
        ).asObj
        val list = root.arr("articles").objects().map { a ->
            val stats = a.obj("stats")
            MemberArticle(
                id = a.long("id"),
                title = a.str("title"),
                summary = a.str("summary"),
                cover = a.arr("image_urls").firstOrNull().asString().ifEmpty { a.str("banner_url") },
                category = a.obj("category").str("name"),
                views = stats.long("view", -1),
                likes = stats.long("like", -1),
                replies = stats.long("reply", -1),
                publishTime = a.long("publish_time"),
            )
        }
        val count = root.int("count")
        return Page(list, list.isNotEmpty() && pn * ps < count, (pn + 1).toString())
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Fake WebGL fingerprint params the space endpoints expect (mirrors Flutter). */
    private fun dmParams(): Map<String, Any?> {
        fun rnd(min: Int, max: Int): String {
            val bytes = Random.nextBytes(Random.nextInt(min, max + 1))
            val s = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return s.dropLast(2)
        }
        return mapOf(
            "dm_img_list" to "[]",
            "dm_img_str" to rnd(16, 64),
            "dm_cover_img_str" to rnd(32, 128),
            "dm_img_inter" to """{"ds":[],"wh":[0,0,0],"of":[0,0,0]}""",
        )
    }
}


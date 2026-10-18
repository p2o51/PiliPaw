package com.gpo.pilipala.feature.search

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.double
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── Models ──────────────────────────────────────────────────────────────────

/** Result tabs, in display order. `type` is the `search_type` the API expects. */
internal enum class SearchTab(val type: String, val label: String) {
    Video("video", "视频"),
    Bangumi("media_bangumi", "番剧"),
    Film("media_ft", "影视"),
    Live("live_room", "直播"),
    User("bili_user", "用户"),
    Article("article", "专栏"),
}

internal enum class HotMark { Hot, New, Live, First, Topic }

@Immutable
internal data class HotWord(val keyword: String, val showName: String, val mark: HotMark?)

/** [name] keeps the `<em class="suggest_high_light">` markup for highlighting. */
@Immutable
internal data class Suggestion(val term: String, val name: String)

internal sealed interface SearchHit {
    val key: String
}

@Immutable
internal data class VideoHit(val item: VideoItem) : SearchHit {
    override val key: String get() = "v${item.aid}${item.bvid}"
}

@Immutable
internal data class MediaEp(val id: Long, val label: String)

@Immutable
internal data class MediaHit(
    val seasonId: Long,
    val mediaId: Long,
    val title: String,
    val cover: String,
    val typeLabel: String,
    val badge: String,
    val score: Double,
    val scoreUsers: Long,
    val areas: String,
    val styles: String,
    val year: String,
    val indexShow: String,
    val buttonText: String,
    val eps: List<MediaEp>,
) : SearchHit {
    override val key: String get() = "m$seasonId-$mediaId"
}

@Immutable
internal data class LiveHit(
    val roomId: Long,
    val title: String,
    val cover: String,
    val uname: String,
    val uface: String,
    val online: Long,
    val area: String,
    val living: Boolean,
) : SearchHit {
    override val key: String get() = "l$roomId"
}

@Immutable
internal data class UserHit(
    val mid: Long,
    val name: String,
    val face: String,
    val sign: String,
    val fans: Long,
    val videos: Long,
    val level: Int,
    val verifyType: Int,
    val verifyDesc: String,
    val isLive: Boolean,
) : SearchHit {
    override val key: String get() = "u$mid"
}

@Immutable
internal data class ArticleHit(
    val id: Long,
    val title: String,
    val cover: String,
    val author: String,
    val category: String,
    val views: Long,
    val likes: Long,
    val replies: Long,
    val pubTime: Long,
) : SearchHit {
    override val key: String get() = "a$id"
}

internal data class SearchPage(val items: List<SearchHit>, val page: Int, val numPages: Int)

// ── Filters ─────────────────────────────────────────────────────────────────

internal data class Choice<T>(val value: T, val label: String)

internal val VideoOrders = listOf(
    Choice("totalrank", "综合"),
    Choice("click", "最多播放"),
    Choice("pubdate", "最新发布"),
    Choice("dm", "最多弹幕"),
    Choice("stow", "最多收藏"),
    Choice("scores", "最多评论"),
)

/** The search's own sort (its bottom row), sent as the video tab's `order`; default first. */
internal val SearchSortOrders = listOf(
    Choice("totalrank", "默认排序"),
    Choice("pubdate", "最新发布"),
    Choice("click", "最多播放"),
    Choice("dm", "最多弹幕"),
    Choice("stow", "最多收藏"),
    Choice("scores", "最多评论"),
)

internal const val DefaultSearchOrder = "totalrank"

/** A video order as one of [SearchSortOrders] (anything else reads as the default). */
internal fun searchSortOrder(order: String): String =
    order.takeIf { o -> SearchSortOrders.any { it.value == o } } ?: DefaultSearchOrder

/** Feeds keep keyword sources as 综合 or 最新 only: every other order joins as 综合. */
internal fun feedKeywordOrder(order: String): String = if (order == "pubdate") "pubdate" else "totalrank"

internal val ArticleOrders = listOf(
    Choice("totalrank", "综合"),
    Choice("pubdate", "最新发布"),
    Choice("click", "最多阅读"),
    Choice("attention", "最多喜欢"),
    Choice("scores", "最多评论"),
)

internal val DurationFilters = listOf(
    Choice(0, "全部时长"),
    Choice(1, "10分钟以下"),
    Choice(2, "10-30分钟"),
    Choice(3, "30-60分钟"),
    Choice(4, "60分钟以上"),
)

internal val ZoneFilters = listOf(
    Choice(-1, "全部"),
    Choice(1, "动画"),
    Choice(13, "番剧"),
    Choice(167, "国创"),
    Choice(3, "音乐"),
    Choice(129, "舞蹈"),
    Choice(4, "游戏"),
    Choice(36, "知识"),
    Choice(188, "科技"),
    Choice(234, "运动"),
    Choice(223, "汽车"),
    Choice(160, "生活"),
    Choice(211, "美食"),
    Choice(217, "动物"),
    Choice(119, "鬼畜"),
    Choice(155, "时尚"),
    Choice(202, "资讯"),
    Choice(5, "娱乐"),
    Choice(181, "影视"),
    Choice(177, "纪录片"),
    Choice(23, "电影"),
    Choice(11, "电视剧"),
)

// ── Network ─────────────────────────────────────────────────────────────────

internal object SearchApi {

    /** 热搜 (Flutter `hotSearchList`): s.search.bilibili.com/main/hotword. */
    suspend fun hot(): List<HotWord> {
        val root = BiliClient.get(BiliHosts.SEARCH + "/main/hotword")
        val code = root.int("code", 0)
        if (code != 0) throw com.gpo.pilipala.core.net.BiliException(code, root.str("message").ifEmpty { "热搜加载失败" })
        return root.arr("list").objects().map { o ->
            HotWord(
                keyword = o.str("keyword"),
                showName = o.str("show_name").ifEmpty { o.str("keyword") },
                mark = when (o.int("word_type")) {
                    5 -> HotMark.Hot
                    4 -> HotMark.New
                    7 -> HotMark.Live
                    11 -> HotMark.Topic
                    13 -> HotMark.First
                    else -> null
                },
            )
        }.filter { it.keyword.isNotBlank() }.distinctBy { it.keyword }
    }

    suspend fun suggest(term: String): List<Suggestion> {
        val root = BiliClient.get(
            BiliHosts.SEARCH + "/main/suggest",
            mapOf("term" to term, "main_ver" to "v1", "highlight" to term),
        )
        val result = root["result"].asObj ?: return emptyList()
        return result.arr("tag").objects().mapNotNull { o ->
            val t = o.str("term").ifEmpty { o.str("value") }
            if (t.isBlank()) null else Suggestion(t, o.str("name").ifEmpty { t })
        }.distinctBy { it.term }
    }

    /** Per-type result counts for the tab labels (`top_tlist`). */
    suspend fun counts(keyword: String): Map<String, Int> {
        val data = BiliClient.getData(
            "/x/web-interface/wbi/search/all/v2",
            mapOf("keyword" to keyword, "web_location" to "333.999"),
            wbi = true,
        ).asObj
        val top = data.obj("top_tlist") ?: return emptyMap()
        return SearchTab.entries.associate { it.type to top.int(it.type) }
    }

    suspend fun search(
        tab: SearchTab,
        keyword: String,
        page: Int,
        order: String?,
        duration: Int,
        tids: Int,
    ): SearchPage {
        val params = buildMap<String, Any?> {
            put("search_type", tab.type)
            put("keyword", keyword)
            put("page", page)
            when (tab) {
                SearchTab.Video -> {
                    if (!order.isNullOrEmpty()) put("order", order)
                    put("duration", duration)
                    if (tids != -1) put("tids", tids)
                }
                SearchTab.Article -> if (!order.isNullOrEmpty()) put("order", order)
                else -> Unit
            }
        }
        BiliClient.ensureBuvid()
        val data = BiliClient.getData("/x/web-interface/wbi/search/type", params, wbi = true).asObj
        val numPages = data.int("numPages")
        val raw = data?.get("result")
        val list: List<JsonObject> = when {
            raw.asArr != null -> raw.asArr!!.objects()
            raw.asObj != null -> raw.asObj.arr(tab.type).objects()
            else -> emptyList()
        }
        val items: List<SearchHit> = when (tab) {
            SearchTab.Video -> list.filter { it.str("type", "video") == "video" }.map(::parseVideo)
            SearchTab.Bangumi, SearchTab.Film -> list.map(::parseMedia)
            SearchTab.Live -> list.map(::parseLive)
            SearchTab.User -> list.map(::parseUser)
            SearchTab.Article -> list.map(::parseArticle)
        }
        return SearchPage(items, page, numPages)
    }

    /**
     * mid → relation attribute (0 none, 1 悄悄关注, 2 关注, 6 互相关注, 128 拉黑); every
     * asked mid gets an entry (absent from the answer = 0). Read-only GET.
     */
    suspend fun relations(mids: List<Long>): Map<Long, Int> {
        if (mids.isEmpty() || !BiliClient.isLoggedIn) return emptyMap()
        val data = BiliClient.getData("/x/relation/relations", mapOf("fids" to mids.joinToString(","))).asObj
        val known = data?.mapNotNull { (k, v) ->
            val mid = k.toLongOrNull() ?: return@mapNotNull null
            mid to v.asObj.int("attribute")
        }?.toMap().orEmpty()
        return mids.associateWith { known[it] ?: 0 }
    }

    suspend fun setFollow(mid: Long, follow: Boolean) {
        BiliClient.postData(
            "/x/relation/modify",
            mapOf("fid" to mid, "act" to if (follow) 1 else 2, "re_src" to 11),
        )
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private fun parseVideo(o: JsonObject) = VideoHit(
        VideoItem(
            bvid = o.str("bvid"),
            aid = o.long("aid").takeIf { it > 0 } ?: o.long("id"),
            title = Fmt.stripTags(o.str("title")),
            cover = o.str("pic"),
            duration = Fmt.parseDuration(o.str("duration")),
            ownerMid = o.long("mid"),
            ownerName = o.str("author"),
            ownerFace = o.str("upic"),
            views = o.long("play", -1),
            danmaku = o.long("video_review", -1).takeIf { it >= 0 } ?: o.long("danmaku", -1),
            pubdate = o.long("pubdate"),
        ),
    )

    private fun parseMedia(o: JsonObject): MediaHit {
        val score = o.obj("media_score")
        val badge = o.arr("badges").objects().firstOrNull()?.str("text").orEmpty()
        val eps = o.arr("eps").objects().mapNotNull { e ->
            val id = e.long("id")
            val label = e.str("index_title").ifEmpty { e.str("title") }
            if (id <= 0 || label.isBlank()) null else MediaEp(id, Fmt.stripTags(label))
        }.distinctBy { it.id }
        val typeLabel = o.str("season_type_name").ifEmpty {
            when (o.int("media_type")) {
                1 -> "番剧"
                2 -> "电影"
                3 -> "纪录片"
                4 -> "国创"
                5 -> "电视剧"
                7 -> "综艺"
                else -> ""
            }
        }
        val pub = o.long("pubtime")
        return MediaHit(
            seasonId = o.long("season_id"),
            mediaId = o.long("media_id"),
            title = Fmt.stripTags(o.str("title")),
            cover = o.str("cover"),
            typeLabel = typeLabel,
            badge = badge,
            score = score.double("score"),
            scoreUsers = score.long("user_count"),
            areas = o.str("areas"),
            styles = Fmt.stripTags(o.str("styles")),
            year = if (pub > 0) Fmt.date(pub, "yyyy") else "",
            indexShow = o.str("index_show"),
            buttonText = o.str("button_text"),
            eps = eps,
        )
    }

    private fun parseLive(o: JsonObject) = LiveHit(
        roomId = o.long("roomid").takeIf { it > 0 } ?: o.long("id"),
        title = Fmt.stripTags(o.str("title")),
        cover = o.str("cover").ifEmpty { o.str("user_cover") },
        uname = Fmt.stripTags(o.str("uname")),
        uface = o.str("uface"),
        online = o.long("online"),
        area = Fmt.stripTags(o.str("cate_name")),
        living = o.int("live_status", 1) == 1,
    )

    private fun parseUser(o: JsonObject): UserHit {
        val verify = o.obj("official_verify")
        return UserHit(
            mid = o.long("mid"),
            name = Fmt.stripTags(o.str("uname")),
            face = o.str("upic"),
            sign = o.str("usign").trim(),
            fans = o.long("fans"),
            videos = o.long("videos"),
            level = o.int("level"),
            verifyType = verify.int("type", -1),
            verifyDesc = verify.str("desc"),
            isLive = o.int("is_live") == 1,
        )
    }

    private fun parseArticle(o: JsonObject) = ArticleHit(
        id = o.long("id"),
        title = Fmt.stripTags(o.str("title")),
        cover = o.arr("image_urls").firstOrNull().asString(),
        author = o.str("author"),
        category = o.str("category_name"),
        views = o.long("view"),
        likes = o.long("like"),
        replies = o.long("reply"),
        pubTime = o.long("pub_time").takeIf { it > 0 } ?: o.long("pubdate"),
    )
}

// ── History (AppSettings.searchHistory, JSON array, newest first) ───────────

internal object SearchHistoryStore {
    private const val MAX = 20

    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { BiliJson.parseToJsonElement(raw) }.getOrNull().asArr
        return arr?.map { it.asString() }?.filter { it.isNotBlank() }
            ?: raw.lines().filter { it.isNotBlank() }
    }

    private fun current() = parse(AppSettings.searchHistory.value)

    private fun save(list: List<String>) {
        AppSettings.set(
            AppSettings.searchHistory,
            if (list.isEmpty()) "" else JsonArray(list.map { JsonPrimitive(it) }).toString(),
        )
    }

    fun add(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        save((listOf(w) + current().filterNot { it == w }).take(MAX))
    }

    fun remove(word: String) = save(current().filterNot { it == word })

    fun clear() = save(emptyList())
}

/**
 * Hand-off of the search's sort to the result page a submit opens: that page's
 * video tab starts on it (Route.SearchResult carries the keyword only).
 */
internal object SearchOrderHandoff {
    private var pending: Pair<String, String>? = null

    fun offer(keyword: String, order: String) {
        pending = keyword to order
    }

    /** The order offered for [keyword], else null. Consumes the offer either way. */
    fun take(keyword: String): String? {
        val p = pending
        pending = null
        return p?.takeIf { it.first == keyword }?.second
    }
}
